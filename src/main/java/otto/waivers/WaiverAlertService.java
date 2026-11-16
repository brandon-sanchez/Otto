package otto.waivers;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.alerts.AlertPhraser;
import otto.alerts.Confidence;
import otto.alerts.MuteStore;
import otto.alerts.Recommendation;
import otto.ask.LeagueWeek;
import otto.claims.BoardDelivery;
import otto.claims.ClaimRound;
import otto.events.Event;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.sleeper.SourceResult;

/**
 * The waiver Alert: the top five free agents, their reasons, their
 * role tags and a FAAB range each, in the user's chat at 18:00
 * America/Los_Angeles the evening before the league's own claim
 * deadline. On Sleeper's default schedule that is Tuesday evening.
 *
 * <p>Role-slip advice held for the board goes out under it, through
 * {@link BoardDelivery}.
 */
@Component
public class WaiverAlertService {

    private final WaiverScorer scorer;
    private final AlertPhraser phraser;
    private final MuteStore muteStore;
    private final SettingsStore settings;
    private final BoardDelivery delivery;

    public WaiverAlertService(WaiverScorer scorer, AlertPhraser phraser,
            MuteStore muteStore, SettingsStore settings, BoardDelivery delivery) {
        this.scorer = scorer;
        this.phraser = phraser;
        this.muteStore = muteStore;
        this.settings = settings;
        this.delivery = delivery;
    }

    /**
     * Returns this Check's scored board, ranked on its first read. The
     * Check makes one and hands it to the waiver Alert and the waiver
     * baselines, so they rank once between them.
     */
    public ScoredBoard scoredBoard(LeagueWeek league, Instant now) {
        return new ScoredBoard(scorer, league, now);
    }

    /**
     * Sends this round's waiver Alert if it is due and has not gone out
     * yet.
     *
     * <p>Side effects: the scored board's reads, the phrasing model
     * call, and {@link BoardDelivery}'s writes and sends.
     *
     * @return the Event recorded for a sent Alert, empty otherwise
     */
    public Optional<Event> considerWaiverAlert(ClaimRound round, ScoredBoard scored) {
        if (!round.open()
                || delivery.alreadySent(round)
                || !settings.enabled(Trigger.WAIVER)
                || muteStore.muted(Trigger.WAIVER.muteTarget())) {
            return Optional.empty();
        }

        // Nothing is recorded unless a board goes out, so a Check that
        // could not build one - or built an empty one - tries again on
        // the next minute inside the window. A board that never
        // computed is not a board that was sent.
        return switch (scored.get()) {
            case SourceResult.Unavailable<WaiverBoard> unavailable -> Optional.empty();
            case SourceResult.Ok<WaiverBoard> ok -> ok.value().candidates().isEmpty()
                    ? Optional.empty()
                    : send(round, ok.value());
        };
    }

    private Optional<Event> send(ClaimRound round, WaiverBoard board) {
        String claimDay = round.deadline().getDayOfWeek()
                .getDisplayName(TextStyle.FULL, Locale.US);
        Map<String, String> facts = facts(board, round.due(), round.deadline());
        Recommendation recommendation = new Recommendation(
                null,
                "your waiver board",
                "%s's claims: %d target%s, $%d of FAAB left".formatted(
                        claimDay,
                        board.candidates().size(),
                        board.candidates().size() == 1 ? "" : "s",
                        board.remainingBudget()),
                // A board is a set of projections, so it states the
                // doubt rather than an action: the user picks.
                Confidence.MEDIUM,
                board.candidates().stream().map(WaiverAlertService::headline).toList(),
                caveats(board, claimDay));
        return delivery.deliverPhrased(round, phraser.basis(facts, recommendation),
                () -> phraser.phrase(facts, recommendation), facts);
    }


    /** One target, as the outbound message would say it. */
    private static String headline(WaiverCandidate candidate) {
        return "%s (%s, %s) - score %d, %s, bid %s: %s".formatted(
                candidate.player(),
                candidate.position(),
                candidate.team(),
                candidate.score(),
                candidate.role(),
                candidate.faab(),
                String.join("; ", candidate.reasons()));
    }

    /**
     * What the board could not see, plus the standing caveat: a claim
     * is a projection, and the user is the one who places the bid.
     */
    private static List<String> caveats(WaiverBoard board, String claimDay) {
        List<String> caveats = new ArrayList<>(board.notes());
        caveats.add("Every number here is a projection for the coming week, not a promise");
        caveats.add("Place the claims yourself in Sleeper before %s's run".formatted(claimDay));
        return List.copyOf(caveats);
    }

    private static Map<String, String> facts(WaiverBoard board, ZonedDateTime due,
            ZonedDateTime deadline) {
        Map<String, String> facts = new HashMap<>();
        facts.put("trigger", "waiver board");
        facts.put("waiverEvening", due.toString());
        facts.put("claimDeadline", deadline.toString());
        facts.put("week", board.week() == null ? "unknown" : board.week());
        // A board that leads with a plain answer leads with it here too,
        // so the phrasing model never buries it under the ranking.
        if (board.answer() != null) {
            facts.put("answer", board.answer());
        }
        facts.put("remainingBudget", String.valueOf(board.remainingBudget()));
        facts.put("basis", String.join("; ", board.basis()));
        List<WaiverCandidate> candidates = board.candidates();
        for (int index = 0; index < candidates.size(); index++) {
            facts.put("target" + (index + 1), headline(candidates.get(index)));
        }
        return Map.copyOf(facts);
    }
}
