package otto.ask;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdvicePrompt;
import otto.advice.AnswerDelivery;
import otto.advice.BundlePlayer;
import otto.advice.CheckedAdvice;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.advice.FriendVoice;
import otto.advice.ToolAnswer;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerLookup;
import otto.league.LeagueWeek;
import otto.records.Candidate;
import otto.records.RunKind;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
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

    private static final String INSTRUCTIONS = AdvicePrompt.preamble(
            "about whether to trade for an injured player",
            AdvicePrompt.Naming.LISTED_BY_FULL_NAME) + """

            Open with whether he is worth buying while hurt ("worthBuying") and what he is
            worth in this league's format ("worth"). Give his timeline exactly as
            "timeline" states it, and name who reported it from "reportedBy" and
            "reported" when they are present. Never give a return date or a number of
            weeks the evidence doesn't state. When "timeline" says there is no timetable
            or that you don't know his timeline, say that in those words. When "timing" is
            present, say why the timing suits both sides in its words. Never write a
            confidence label such as High or Medium. Stay under "maxLength" characters.
            There is no lineup move, so "move" is null.
            """ + FriendVoice.RULE
            + AdvicePrompt.answerShape(AdvicePrompt.Naming.LISTED_BY_FULL_NAME);

    private static final CheckedAdvice.Fallbacks FALLBACKS = new CheckedAdvice.Fallbacks(
            "I cannot reach my language model to explain this right now",
            "I worked out what he is worth while hurt, but my explanation failed its fact "
                    + "check twice, so I am holding it back rather than risk a wrong figure");

    private final UserWeekLoader loader;
    private final PlayerLookup lookup;
    private final RestOfSeasonProjections restOfSeason;
    private final InjuredPlayers injuredPlayers;
    private final InjuredValues injuredValues;
    private final CheckedAdvice advice;
    private final AdviceForm<AdviceAnswer> form;

    public InjuredBuyLow(UserWeekLoader loader, PlayerLookup lookup,
            RestOfSeasonProjections restOfSeason, InjuredPlayers injuredPlayers,
            InjuredValues injuredValues, CheckedAdvice advice, AdviceCheck check) {
        this.loader = loader;
        this.lookup = lookup;
        this.restOfSeason = restOfSeason;
        this.injuredPlayers = injuredPlayers;
        this.injuredValues = injuredValues;
        this.advice = advice;
        this.form = AdviceForm.of(INSTRUCTIONS, check::check);
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
            AnswerDelivery delivery) {
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
            AnswerDelivery delivery) {
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
            AnswerDelivery delivery) {
        InjuredPlayer player = value.player();
        EvidenceItem item = InjuryEvidence.item(value, now);
        EvidenceBundle bundle = new EvidenceBundle(("Tell the user whether %s is worth buying "
                + "while he is hurt, and why.").formatted(player.name()), List.of(item),
                List.of(new BundlePlayer(player.playerId(), player.name(), Optional.empty())),
                List.of(), true, Optional.empty(), MAX_LENGTH, Optional.empty(), List.of(),
                List.of());
        List<Candidate> candidates = List.of(new Candidate(player.playerId(), player.name(),
                value.worthBuying(), value.format().name()));
        return advice.write(bundle, form, new CheckedAdvice.Run(RUN_ID_PREFIX,
                        List.of(league.league().leagueId()), RunKind.INJURED_BUY_LOW,
                        league.league(), candidates, "explanation"), now, delivery)
                .answer(Answer::new, FALLBACKS);
    }
}
