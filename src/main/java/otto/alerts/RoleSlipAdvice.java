package otto.alerts;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.advice.AdviceAnswer;
import otto.advice.LineupMove;
import otto.ask.LeagueWeek;
import otto.claims.BoardDelivery;
import otto.claims.ClaimRound;
import otto.claims.RoleSlipLedger;
import otto.decisions.RosterPlans;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectoryStore;
import otto.records.Outcome;
import otto.records.RunKind;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.snapshot.RosterSnapshot;

/**
 * Decides what becomes of advice about a role slip: sent alone, held
 * for the next waiver board, or withheld. The model writes and checks
 * the advice through {@link LineupAdvice}; this class owns the role-slip
 * ledger's part of it.
 */
@Component
public class RoleSlipAdvice {

    private final LineupAdvice lineupAdvice;
    private final RoleSlipLedger ledger;
    private final PlayerDirectoryStore directory;
    private final SettingsStore settings;
    private final MuteStore muteStore;
    private final BoardDelivery boards;

    public RoleSlipAdvice(LineupAdvice lineupAdvice, RoleSlipLedger ledger,
            PlayerDirectoryStore directory, SettingsStore settings, MuteStore muteStore,
            BoardDelivery boards) {
        this.lineupAdvice = lineupAdvice;
        this.ledger = ledger;
        this.directory = directory;
        this.settings = settings;
        this.muteStore = muteStore;
        this.boards = boards;
    }

    /**
     * The advice for a healthy player whose role may have slipped: what
     * to do with him, chosen from the roster plans legal in this league,
     * and whether to bench him this week, in one message.
     *
     * <p>Advice goes out alone only when waiting for the next waiver
     * board could cost the user a move: no board will come before this
     * round's claim deadline, or a lineup change is due before the
     * board. Otherwise it is held for that board. A hold with no lineup
     * change goes out only when a mistaken drop is plausible, and advice
     * whose action and reasons match the last advice about him is not
     * sent again. Every outcome but a sent Alert settles the slip, so
     * the same evidence is never evaluated twice.
     *
     * <p>Side effects: may call the model, saves a Decision Record for a
     * new run, and writes the role-slip ledger.
     *
     * @param research the facts read for him right before sending
     * @return the checked advice to send now; empty when it is held for
     *         the board, withheld, or no decision is open
     */
    public Optional<AlertText.Advised> forRoleSlip(String alertKey, AlertCandidate slip,
            Map<String, String> research, LeagueWeek leagueWeek, RosterSnapshot roster,
            Instant now) {
        if (ledger.settled(slip.key()) || !roster.players().contains(slip.playerId())) {
            return Optional.empty();
        }
        Collection<DirectoryPlayer> players = directory.read()
                .map(stored -> stored.players().values())
                .orElse(List.of());
        Optional<LineupEvidence.Draft> draft = DecisionEvidence.roleSlip(slip, leagueWeek, roster,
                players, lineupAdvice::profile, now);
        if (draft.isEmpty()) {
            ledger.settle(slip.key());
            return Optional.empty();
        }
        LineupEvidence.Draft decision = draft.get().withNews(slip.playerId(), research, now);
        LineupAdvice.Run run = lineupAdvice.run(alertKey, RunKind.PLAYER_DECISION, decision, leagueWeek, roster, now,
                answer -> roleSlipOutcome(answer, signature(answer, slip), decision, slip,
                        leagueWeek, roster, now));
        if (run.outcome() instanceof Outcome.HeldForBoard held) {
            ledger.hold(new RoleSlipLedger.Held(run.runId(), slip.playerId(), held.message(),
                    held.signature()));
        }
        if (!(run.outcome() instanceof Outcome.Sent)) {
            ledger.settle(slip.key());
        }
        return LineupAdvice.sent(run);
    }

    /**
     * True while a role slip has a decision open: no advice about it
     * has been withheld, held for the board, or found moot.
     */
    boolean slipOpen(AlertCandidate slip) {
        return !ledger.settled(slip.key());
    }

    /**
     * The outcome of checked advice about a role slip. Sent advice
     * becomes the last advice about him at once, since the outbox
     * carries it to delivery; held advice becomes it only once a board
     * carries it, so a board that never goes out cannot silence the
     * advice later.
     */
    private Outcome roleSlipOutcome(AdviceAnswer answer, String signature,
            LineupEvidence.Draft draft, AlertCandidate slip, LeagueWeek leagueWeek,
            RosterSnapshot roster, Instant now) {
        boolean hold = answer.plan().map(RosterPlans.HOLD::equals).orElse(true);
        if (hold && answer.move().isEmpty() && draft.holdStaysSilent()) {
            return new Outcome.Withheld("The model advised keeping him as he is, and no "
                    + "mistaken drop is plausible, so the hold is not sent unasked.");
        }
        if (ledger.lastAdvice(slip.playerId()).filter(signature::equals).isPresent()) {
            return new Outcome.Withheld("The action and its reasons match the last advice "
                    + "about him, which is not sent again.");
        }
        if (!waitingCostsAMove(answer, leagueWeek, roster, now)) {
            return new Outcome.HeldForBoard(answer.message(), signature);
        }
        ledger.advised(slip.playerId(), signature);
        return new Outcome.Sent(answer.message());
    }

    /**
     * What makes advice about a role slip the same advice: the roster
     * plan, the lineup change, and which kinds of evidence pointed to
     * the slip. A further week of the same usage drop is the same reason;
     * news of the slip, where there was only usage before, is a new one.
     */
    private static String signature(AdviceAnswer answer, AlertCandidate slip) {
        String move = answer.move()
                .map(chosen -> chosen.start() + ">" + chosen.bench())
                .orElse("none");
        String reasons = (slip.facts().containsKey("roleNews") ? "news" : "")
                + (slip.facts().keySet().stream()
                        .anyMatch(key -> key.startsWith(RoleSlipDetector.USAGE_PREFIX))
                        ? "+usage" : "");
        return answer.plan().orElse(RosterPlans.HOLD) + "|" + move + "|" + reasons;
    }

    /**
     * True when holding the advice for the next waiver board could cost
     * the user a move: no board is coming before the next claim
     * deadline, or the advice changes a lineup whose first kickoff is
     * not known or comes before the board is due.
     */
    private boolean waitingCostsAMove(AdviceAnswer answer, LeagueWeek leagueWeek,
            RosterSnapshot roster, Instant now) {
        Optional<ClaimRound> round = ClaimRound.next(leagueWeek.league(), now);
        if (round.isEmpty() || !settings.enabled(Trigger.WAIVER)
                || muteStore.muted(Trigger.WAIVER.muteTarget())
                || boards.alreadySent(round.get())) {
            return true;
        }
        if (answer.move().isEmpty()) {
            return false;
        }
        Instant boardDue = round.get().due().toInstant();
        LineupMove move = answer.move().get();
        Optional<Instant> firstKickoff = leagueWeek.week().games().flatMap(games -> Stream
                .of(move.start(), move.bench())
                .filter(Objects::nonNull)
                .map(id -> games.lockFor(roster.playerTeams().get(id)))
                .flatMap(Optional::stream)
                .min(Comparator.naturalOrder()));
        return firstKickoff.map(kickoff -> kickoff.isBefore(boardDue)).orElse(true);
    }
}
