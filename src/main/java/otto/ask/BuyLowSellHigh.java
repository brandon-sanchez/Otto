package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;
import otto.buysell.Assessment;
import otto.buysell.BuySellReader;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerLookup;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.storage.OttoJson;

/**
 * Answers "who should I buy low?", "is anyone on my team a sell-high?"
 * and "should I sell this player?" in chat. Code judges every player by
 * the rules in {@link Assessment}, the model explains the calls, and
 * {@link AdviceCheck} holds every name and number to the evidence before
 * the answer is sent. An answer that fails the check twice is withheld.
 */
@Component
public class BuyLowSellHigh {

    /** The most players of each side one answer explains. */
    static final int MOST_PER_SIDE = 3;

    private static final String RUN_ID_PREFIX = "buy-sell-";

    private static final Set<Assessment.Call> BUYS = Set.of(Assessment.Call.BUY_LOW,
            Assessment.Call.MARKET_BUY_LOW, Assessment.Call.PARTIAL_BOUNCE_BACK);

    private static final String INSTRUCTIONS = """
            You are Otto, a fantasy football assistant texting your one user, the way a
            knowledgeable friend would.

            The user message is an evidence bundle as JSON about buy-low and sell-high
            players. State facts only from the evidence: never from memory, never invented.
            Every number you write must appear in the evidence, written the same way. Name
            only players listed in "players".

            For each player, lead with his call as the evidence names it, then explain why
            in one or two natural sentences. A buy-low is a player whose usage held while
            his points slumped; say how his usage held and how far below it he scored. For
            a partial bounce-back, say his usage slipped too, so only part of his old level
            should come back. A role decline is never a buy-low: say his role shrank. For a
            sell-high, say how far above his usage he has scored and give "levelToExpect"
            as the level to expect from here. When a market item is present, say it plainly,
            for example "his value is down 18% this month while his targets held". Explain
            xFP in plain words the first time you use it. When "rosteredBy" is present, say
            whose team he is on. Introduce every player by full name before using his
            surname alone. Never write a confidence label such as High or Medium. Stay under
            "maxLength" characters. There is no lineup move, so "move" is null.
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
    private final BuySellReader reader;
    private final AdviceWriter writer;
    private final RunOutcomes runs;
    private final AdviceForm<AdviceAnswer> form;
    private final String userId;

    public BuyLowSellHigh(UserWeekLoader loader, PlayerLookup lookup, BuySellReader reader,
            AdviceWriter writer, AdviceCheck check, RunOutcomes runs,
            OttoProperties properties) {
        this.loader = loader;
        this.lookup = lookup;
        this.reader = reader;
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

    /** Which players a league-wide answer looks for. */
    public enum Side {
        /** Buy-lows on the other managers' teams. */
        BUY,
        /** Sell-highs on the user's own team. */
        SELL,
        /** Both. */
        BOTH;

        /**
         * Reads the side a tool call named.
         *
         * @param side "buy", "sell", "both", or null for both
         * @return empty for any other word
         */
        public static Optional<Side> parse(String side) {
            if (side == null || side.isBlank()) {
                return Optional.of(BOTH);
            }
            return switch (side.trim().toLowerCase(Locale.ROOT)) {
                case "buy" -> Optional.of(BUY);
                case "sell" -> Optional.of(SELL);
                case "both" -> Optional.of(BOTH);
                default -> Optional.empty();
            };
        }
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
     * Judges one player the user named.
     *
     * <p>Side effects: at most one FantasyCalc request a day for the
     * league's format, one or two model calls, and one Decision Record
     * of kind {@link RunKind#BUY_LOW_SELL_HIGH} whatever the outcome.
     *
     * @param reference the player, by name or Sleeper player id
     * @param delivery how a checked message reaches the user, which the
     *        Decision Record keeps as its outcome
     * @return the checked message, or why there is none
     */
    public ToolAnswer<Answer> player(String reference, Instant now,
            CheckedAnswers.Delivery delivery) {
        return switch (lookup.find(reference)) {
            case PlayerLookup.Match.NotFound notFound -> ToolAnswer.unavailable(notFound.reason());
            case PlayerLookup.Match.Found found -> switch (loader.league()) {
                case SourceResult.Unavailable<SleeperAdapter.League> unavailable ->
                    unavailable(unavailable);
                case SourceResult.Ok<SleeperAdapter.League> league ->
                    player(found.player(), league.value(), now, delivery);
            };
        };
    }

    private ToolAnswer<Answer> player(DirectoryPlayer player, SleeperAdapter.League league,
            Instant now, CheckedAnswers.Delivery delivery) {
        return switch (reader.board(league, now)) {
            case SourceResult.Unavailable<BuySellReader.Board> unavailable ->
                unavailable(unavailable);
            case SourceResult.Ok<BuySellReader.Board> board -> board.value()
                    .assess(player.playerId())
                    .map(assessment -> explain(league, List.of(new BuySellEvidence.Judged(
                                    player.playerId(), player.fullName(), assessment,
                                    Optional.empty())),
                            ("Tell the user whether %s is a buy-low, a sell-high or neither, "
                                    + "and why.").formatted(player.fullName()), now, delivery))
                    .orElseGet(() -> ToolAnswer.unavailable(("I judge buy-lows and sell-highs "
                            + "for running backs, receivers and tight ends with a game this "
                            + "season in ffopportunity's data, and %s has none")
                            .formatted(player.fullName())));
        };
    }

    /**
     * Finds the league's buy-lows on other managers' teams, the
     * sell-highs on the user's team, or both, up to {@link #MOST_PER_SIDE}
     * of each.
     *
     * <p>Side effects: as {@link #player}.
     *
     * @param side which of the two to look for
     *
     * @return the checked message, or why there is none, including when
     *         no player fits
     */
    public ToolAnswer<Answer> scan(Side side, Instant now, CheckedAnswers.Delivery delivery) {
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> unavailable(unavailable);
            case SourceResult.Ok<LeagueWeek> week -> switch (reader.board(
                    week.value().league(), now)) {
                case SourceResult.Unavailable<BuySellReader.Board> unavailable ->
                    unavailable(unavailable);
                case SourceResult.Ok<BuySellReader.Board> board ->
                    scan(side, week.value(), board.value(), now, delivery);
            };
        };
    }

    private ToolAnswer<Answer> scan(Side side, LeagueWeek week, BuySellReader.Board board,
            Instant now, CheckedAnswers.Delivery delivery) {
        List<BuySellEvidence.Judged> judged = new ArrayList<>();
        if (side != Side.SELL) {
            judged.addAll(buys(week.rosters(), board));
        }
        if (side != Side.BUY) {
            judged.addAll(sells(week.rosters(), board));
        }
        if (judged.isEmpty()) {
            return ToolAnswer.unavailable(nobody(side));
        }
        return explain(week.league(), judged, "Tell the user which players are buy-lows on "
                + "other teams and which of his own players are sell-highs, and why.", now,
                delivery);
    }

    private static List<BuySellEvidence.Judged> buys(List<RosterSnapshot> rosters,
            BuySellReader.Board board) {
        return rosters.stream()
                .filter(roster -> !roster.userRoster())
                .flatMap(roster -> judged(roster, board, Optional.of(roster.manager())).stream())
                .filter(player -> BUYS.contains(player.assessment().call()))
                .sorted(Comparator.comparing((BuySellEvidence.Judged player) ->
                                player.assessment().call())
                        .thenComparing(player -> player.assessment().marketRead()
                                != Assessment.MarketRead.CHEAPER)
                        .thenComparing(Comparator.comparingDouble(BuyLowSellHigh::shortfall)
                                .reversed()))
                .limit(MOST_PER_SIDE)
                .toList();
    }

    private static List<BuySellEvidence.Judged> sells(List<RosterSnapshot> rosters,
            BuySellReader.Board board) {
        return rosters.stream()
                .filter(RosterSnapshot::userRoster)
                .flatMap(roster -> judged(roster, board, Optional.empty()).stream())
                .filter(player -> player.assessment().call() == Assessment.Call.SELL_HIGH)
                .sorted(Comparator.comparingDouble((BuySellEvidence.Judged player) ->
                        player.assessment().thisSeason().overExpectedPerGame()).reversed())
                .limit(MOST_PER_SIDE)
                .toList();
    }

    private static List<BuySellEvidence.Judged> judged(RosterSnapshot roster,
            BuySellReader.Board board, Optional<String> manager) {
        return roster.players().stream()
                .filter(roster.playerNames()::containsKey)
                .flatMap(id -> board.assess(id).stream().map(assessment ->
                        new BuySellEvidence.Judged(id, roster.playerNames().get(id), assessment,
                                manager)))
                .toList();
    }

    private static double shortfall(BuySellEvidence.Judged player) {
        Assessment assessment = player.assessment();
        return assessment.baseline().perGame() - assessment.thisSeason().pointsPerGame();
    }

    private static String nobody(Side side) {
        return switch (side) {
            case BUY -> "no player on another team fits a buy-low right now: none is a proven "
                    + "player whose usage held while his points slumped, and none has a falling "
                    + "market value while his usage held";
            case SELL -> "no player on the user's team fits a sell-high right now: none is "
                    + "scoring far enough above what his usage is worth";
            case BOTH -> "no player fits a buy-low on another team or a sell-high on the "
                    + "user's team right now";
        };
    }

    private ToolAnswer<Answer> explain(SleeperAdapter.League league,
            List<BuySellEvidence.Judged> judged, String task, Instant now,
            CheckedAnswers.Delivery delivery) {
        EvidenceBundle bundle = BuySellEvidence.bundle(task, judged, now);
        AdviceResult<AdviceAnswer> result = writer.write(bundle, form);
        Outcome outcome = runs.record(record(league, judged, bundle, result, now, delivery));
        return switch (outcome) {
            case Outcome.Sent sent -> ToolAnswer.of(new Answer(sent.message()));
            case Outcome.HandedToChat handed -> ToolAnswer.of(new Answer(handed.message()));
            default -> ToolAnswer.unavailable(switch (result) {
                case AdviceResult.Unavailable<AdviceAnswer> unavailable ->
                    "I cannot reach my language model to explain these calls right now";
                default -> "I worked out the buy-low and sell-high calls, but my explanation "
                        + "failed its fact check twice, so I am holding it back rather than "
                        + "risk a wrong figure";
            });
        };
    }

    private DecisionRecord record(SleeperAdapter.League league,
            List<BuySellEvidence.Judged> judged, EvidenceBundle bundle,
            AdviceResult<AdviceAnswer> result, Instant now, CheckedAnswers.Delivery delivery) {
        String runId = RunIds.stable(RUN_ID_PREFIX, bundle.identity(), now.toString());
        return DecisionRecord.forRun(runId, RunKind.BUY_LOW_SELL_HIGH, now, userId,
                        league.leagueId(), OttoJson.MAPPER.valueToTree(league))
                .candidates(judged.stream()
                        .map(player -> new Candidate(player.playerId(), player.name(), true,
                                player.assessment().call().name()))
                        .toList())
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

    private static <T> ToolAnswer<Answer> unavailable(SourceResult.Unavailable<T> unavailable) {
        return ToolAnswer.unavailable("%s: %s".formatted(unavailable.source(),
                unavailable.reason()));
    }
}
