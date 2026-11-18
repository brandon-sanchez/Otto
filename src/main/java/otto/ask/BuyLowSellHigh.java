package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdvicePrompt;
import otto.advice.AnswerDelivery;
import otto.advice.CheckedAdvice;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;
import otto.advice.ToolAnswer;
import otto.buysell.Assessment;
import otto.buysell.BuySellReader;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerLookup;
import otto.league.LeagueWeek;
import otto.records.Candidate;
import otto.records.RunKind;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

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

    private static final String INSTRUCTIONS = AdvicePrompt.preamble(
            "about buy-low and sell-high players", AdvicePrompt.Naming.LISTED_BY_FULL_NAME)
            + """

            Give each player his own line that leads with his call: "📉 Buy low:
            <b>Player Name</b>" for a buy-low, partial bounce-back or market buy-low, and
            "📈 Sell high: <b>Player Name</b>" for a sell-high, followed on the same line
            by the one number that makes the call, such as "4.6 pts/game below his usage".
            Then one short line on why. A buy-low is a player whose usage held while
            his points slumped; say how his usage held and how far below it he scored. For
            a partial bounce-back, say his usage slipped too, so only part of his old level
            should come back. A role decline is never a buy-low: say his role shrank. For a
            sell-high, say how far above his usage he has scored and give "levelToExpect"
            as the level to expect from here. When a market item is present, say it plainly,
            for example "his value is down 18% this month while his targets held". Explain
            xFP in plain words the first time you use it. When "rosteredBy" is present, say
            whose team he is on. Never write a confidence label such as High or Medium. Stay
            under "maxLength" characters. There is no lineup move, so "move" is null.
            """ + FriendVoice.RULE
            + AdvicePrompt.answerShape(AdvicePrompt.Naming.LISTED_BY_FULL_NAME);

    private static final CheckedAdvice.Fallbacks FALLBACKS = new CheckedAdvice.Fallbacks(
            "I cannot reach my language model to explain these calls right now",
            "I worked out the buy-low and sell-high calls, but my explanation failed its fact "
                    + "check twice, so I am holding it back rather than risk a wrong figure");

    private final UserWeekLoader loader;
    private final PlayerLookup lookup;
    private final BuySellReader reader;
    private final CheckedAdvice advice;
    private final AdviceForm<AdviceAnswer> form;

    public BuyLowSellHigh(UserWeekLoader loader, PlayerLookup lookup, BuySellReader reader,
            CheckedAdvice advice, AdviceCheck check) {
        this.loader = loader;
        this.lookup = lookup;
        this.reader = reader;
        this.advice = advice;
        this.form = AdviceForm.of(INSTRUCTIONS, check::check);
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
            AnswerDelivery delivery) {
        return switch (lookup.find(reference)) {
            case PlayerLookup.Match.NotFound notFound -> ToolAnswer.unavailable(notFound.reason());
            case PlayerLookup.Match.Found found -> ToolAnswer.from(loader.league(),
                    league -> player(found.player(), league, now, delivery));
        };
    }

    private ToolAnswer<Answer> player(DirectoryPlayer player, SleeperAdapter.League league,
            Instant now, AnswerDelivery delivery) {
        return switch (reader.board(league, now)) {
            case SourceResult.Unavailable<BuySellReader.Board> unavailable ->
                ToolAnswer.unavailable(unavailable);
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
    public ToolAnswer<Answer> scan(Side side, Instant now, AnswerDelivery delivery) {
        return ToolAnswer.from(loader.leagueWeek(), week -> ToolAnswer.from(
                reader.board(week.league(), now),
                board -> scan(side, week, board, now, delivery)));
    }

    private ToolAnswer<Answer> scan(Side side, LeagueWeek week, BuySellReader.Board board,
            Instant now, AnswerDelivery delivery) {
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
            AnswerDelivery delivery) {
        EvidenceBundle bundle = BuySellEvidence.bundle(task, judged, now);
        List<Candidate> candidates = judged.stream()
                .map(player -> new Candidate(player.playerId(), player.name(), true,
                        player.assessment().call().name()))
                .toList();
        return advice.write(bundle, form, new CheckedAdvice.Run(RUN_ID_PREFIX, List.of(),
                        RunKind.BUY_LOW_SELL_HIGH, league, candidates, "explanation"), now,
                        delivery)
                .answer(Answer::new, FALLBACKS);
    }
}
