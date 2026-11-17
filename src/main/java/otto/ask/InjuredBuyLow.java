package otto.ask;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.advice.FriendVoice;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerLookup;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.storage.OttoJson;
import otto.trade.InjuredPlayer;
import otto.trade.InjuredPlayers;
import otto.trade.InjuredValue;
import otto.trade.InjuredValues;
import otto.trade.RestOfSeason;
import otto.trade.RestOfSeasonProjections;

/**
 * Answers "should I buy him while he's hurt?" in chat. Code reads the
 * player's return timeline from cited news, counts the games he likely
 * plays after it, and prices him by the league's format; the model
 * explains, and {@link AdviceCheck} holds every name and number to the
 * evidence before the answer is sent.
 */
@Component
public class InjuredBuyLow {

    private static final int MAX_LENGTH = 1000;

    private static final String RUN_ID_PREFIX = "injured-buy-low-";

    private static final String INSTRUCTIONS = """
            You are Otto, a fantasy football assistant texting your one user about whether
            to trade for an injured player, the way a knowledgeable friend would.

            The user message is an evidence bundle as JSON. State facts only from the
            evidence: never from memory, never invented. Every number you write must appear
            in the evidence, written the same way. Name only players listed in "players",
            and introduce each by full name before using his surname alone.

            Open with whether he is worth buying while hurt ("worthBuying") and what he is
            worth in this league's format ("worth"). Give his timeline exactly as
            "timeline" states it, and name who reported it from "reportedBy" and
            "reported" when they are present. Never give a return date or a number of
            weeks the evidence doesn't state. When "timeline" says there is no timetable
            or that you don't know his timeline, say that in those words. When "timing" is
            present, say why the timing suits both sides in its words. Never write a
            confidence label such as High or Medium. Stay under "maxLength" characters.
            There is no lineup move, so "move" is null.
            """ + FriendVoice.RULE + """

            Answer with only this JSON object:
            {
              "message": "the text the user receives",
              "move": null,
              "players": ["<playerId of every player the message names>"],
              "claims": [{"text": "<one factual statement>", "evidence": ["<evidence id>"]}],
              "comparisons": []
            }
            """;

    private final UserWeekLoader loader;
    private final PlayerLookup lookup;
    private final RestOfSeasonProjections restOfSeason;
    private final InjuredPlayers injuredPlayers;
    private final InjuredValues injuredValues;
    private final AdviceWriter writer;
    private final RunOutcomes runs;
    private final AdviceForm<AdviceAnswer> form;
    private final String userId;

    public InjuredBuyLow(UserWeekLoader loader, PlayerLookup lookup,
            RestOfSeasonProjections restOfSeason, InjuredPlayers injuredPlayers,
            InjuredValues injuredValues, AdviceWriter writer, AdviceCheck check,
            RunOutcomes runs, OttoProperties properties) {
        this.loader = loader;
        this.lookup = lookup;
        this.restOfSeason = restOfSeason;
        this.injuredPlayers = injuredPlayers;
        this.injuredValues = injuredValues;
        this.writer = writer;
        this.runs = runs;
        this.userId = properties.userId();
        this.form = new AdviceForm<>() {
            @Override
            public String instructions() {
                return INSTRUCTIONS;
            }

            @Override
            public AdviceCheck.Verdict<AdviceAnswer> check(EvidenceBundle bundle,
                    String reply) {
                return check.check(bundle, reply);
            }
        };
    }

    /**
     * The checked answer.
     *
     * @param message the checked text, which the user receives word for
     *        word unless the chat model gathered other tool results for
     *        the same question
     */
    public record Answer(String message) {
    }

    /**
     * Judges one injured player for the user.
     *
     * <p>Side effects: one live read of the player's news, which the
     * research cache keeps; the rest-of-season projection reads; in
     * keeper and dynasty leagues, the market value reads; one or two
     * model calls; and one Decision Record of kind
     * {@link RunKind#INJURED_BUY_LOW} whatever the outcome.
     *
     * @param reference the player, by name or Sleeper player id
     * @param delivery how a checked message reaches the user, which the
     *        Decision Record keeps as its outcome
     * @return the checked message, or why there is none, including when
     *         the player isn't out or on a reserve list
     */
    public ToolAnswer<Answer> answer(String reference, Instant now,
            CheckedAnswers.Delivery delivery) {
        return switch (lookup.find(reference)) {
            case PlayerLookup.Match.NotFound notFound -> ToolAnswer.unavailable(notFound.reason());
            case PlayerLookup.Match.Found found -> switch (loader.leagueWeek()) {
                case SourceResult.Unavailable<LeagueWeek> unavailable -> ToolAnswer.unavailable(
                        "%s: %s".formatted(unavailable.source(), unavailable.reason()));
                case SourceResult.Ok<LeagueWeek> league ->
                    answer(found.player(), league.value(), now, delivery);
            };
        };
    }

    private ToolAnswer<Answer> answer(DirectoryPlayer player, LeagueWeek league, Instant now,
            CheckedAnswers.Delivery delivery) {
        if (!InjuredPlayers.SIDELINED.contains(player.health())) {
            return ToolAnswer.unavailable(("%s isn't out or on a reserve list, so there is no "
                    + "injury to judge; find_buy_low_sell_high judges a healthy player")
                    .formatted(player.fullName()));
        }
        return switch (restOfSeason.restOfSeason(league,
                Map.of(player.playerId(), player.position()),
                player.team() == null ? Map.of()
                        : Map.of(player.playerId(), player.team()))) {
            case SourceResult.Unavailable<RestOfSeason> unavailable -> ToolAnswer.unavailable(
                    "%s: %s".formatted(unavailable.source(), unavailable.reason()));
            case SourceResult.Ok<RestOfSeason> season -> {
                InjuredPlayer injured = injuredPlayers.refreshed(league, season.value(), player)
                        .orElseThrow();
                Optional<RosterSnapshot> holder = league.rosters().stream()
                        .filter(roster -> roster.players().contains(player.playerId())
                                || roster.reserve().contains(player.playerId())
                                || roster.taxi().contains(player.playerId()))
                        .findFirst();
                yield injuredValues.value(league, injured, holder, now)
                        .map(value -> explain(league, value, now, delivery))
                        .orElseGet(() -> ToolAnswer.unavailable("the league's settings don't "
                                + "say whether it is redraft, keeper or dynasty, and an injured "
                                + "player's worth depends on it"));
            }
        };
    }

    private ToolAnswer<Answer> explain(LeagueWeek league, InjuredValue value, Instant now,
            CheckedAnswers.Delivery delivery) {
        InjuredPlayer player = value.player();
        EvidenceItem item = InjuryEvidence.item(value, now);
        EvidenceBundle bundle = new EvidenceBundle(("Tell the user whether %s is worth buying "
                + "while he is hurt, and why.").formatted(player.name()), List.of(item),
                List.of(new BundlePlayer(player.playerId(), player.name(), Optional.empty())),
                List.of(), true, Optional.empty(), MAX_LENGTH, Optional.empty(), List.of(),
                List.of());
        AdviceResult<AdviceAnswer> result = writer.write(bundle, form);
        Outcome outcome = runs.record(record(league, value, bundle, result, now, delivery));
        return switch (outcome) {
            case Outcome.Sent sent -> ToolAnswer.of(new Answer(sent.message()));
            case Outcome.HandedToChat handed -> ToolAnswer.of(new Answer(handed.message()));
            default -> ToolAnswer.unavailable(switch (result) {
                case AdviceResult.Unavailable<AdviceAnswer> _ ->
                    "I cannot reach my language model to explain this right now";
                default -> "I worked out what he is worth while hurt, but my explanation failed "
                        + "its fact check twice, so I am holding it back rather than risk a "
                        + "wrong figure";
            });
        };
    }

    private DecisionRecord record(LeagueWeek league, InjuredValue value, EvidenceBundle bundle,
            AdviceResult<AdviceAnswer> result, Instant now, CheckedAnswers.Delivery delivery) {
        String leagueId = league.league().leagueId();
        String runId = RunIds.stable(RUN_ID_PREFIX, leagueId, bundle.identity(), now.toString());
        InjuredPlayer player = value.player();
        return DecisionRecord.forRun(runId, RunKind.INJURED_BUY_LOW, now, userId, leagueId,
                        OttoJson.MAPPER.valueToTree(league.league()))
                .candidates(List.of(new Candidate(player.playerId(), player.name(),
                        value.worthBuying(), value.format().name())))
                .evidence(bundle.recorded())
                .versions(bundle.versions(form, result))
                .exchanges(result.exchanges())
                .outcome(switch (result) {
                    case AdviceResult.Approved<AdviceAnswer> approved -> switch (delivery) {
                        case UNCHANGED -> new Outcome.Sent(approved.answer().message());
                        case THROUGH_CHAT_MODEL ->
                            new Outcome.HandedToChat(approved.answer().message());
                    };
                    case AdviceResult.Rejected<AdviceAnswer> rejected -> new Outcome.Withheld(
                            "The explanation failed its checks twice: "
                                    + String.join(" ", rejected.failures()));
                    case AdviceResult.Unavailable<AdviceAnswer> unavailable ->
                        new Outcome.Withheld(unavailable.reason());
                });
    }
}
