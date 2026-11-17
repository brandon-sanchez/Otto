package otto.ask;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdvicePrompt;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;
import otto.advice.RankConvention;
import otto.advice.ToolAnswer;
import otto.directory.PlayerLookup;
import otto.market.DraftPick;
import otto.market.KeeperCost;
import otto.market.KeeperCosts;
import otto.market.MarketQuote;
import otto.market.MarketValuation;
import otto.records.DecisionRecord;
import otto.records.DecisionRecords;
import otto.records.Outcome;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.storage.OttoJson;

/**
 * Answers "what is he worth in my league?" in chat. Code prices the
 * player or pick in the league's format, the model explains the price
 * in a few sentences, and {@link AdviceCheck} holds every name, number
 * and rank in those sentences to the evidence before the answer reaches
 * the chat. An answer that fails the check twice is withheld.
 */
@Component
public class MarketValueAnswers {

    private static final String INSTRUCTIONS = AdvicePrompt.preamble(
            "about what one player or draft pick is worth on the trade market in his league",
            AdvicePrompt.Naming.LISTED) + RankConvention.ADVICE_RULES + FriendVoice.RULE + """

            Write every number with no thousands separators. Answer in two to four sentences: his value in the league's format and the
            source it comes from, his rank and tier when the evidence has them, and how his
            value has moved. Write a rank on the list as "35th on the list" or "12th among
            WRs", never with the word "overall". When "staleness" is present, say how old
            the list is. When "fallback" is present, say the value is DynastyProcess's and
            why. In a keeper league, say whether he is a likely keeper, what keeping him
            costs and what that adds, and say plainly what "keeperRule" says about where
            the rule comes from. When "askForKeeperRule" is present, end with that question.
            Mention his age, roster share or trade share only when they help explain the
            value. Never write a confidence label such as High or Medium. Stay under
            "maxLength" characters. There is no lineup move, so "move" is null.
            """ + AdvicePrompt.answerShape(AdvicePrompt.Naming.LISTED);

    private static final String WITHHELD = "I worked out that value, but my answer failed "
            + "its fact check twice, so I'm not sending numbers I can't stand behind. "
            + "Ask me again in a moment.";

    private static final String MODEL_UNAVAILABLE = "I have the market value, but I can't reach "
            + "the model that explains it right now, and I don't send numbers unexplained. "
            + "Ask me again in a moment.";

    private static final Logger log = LoggerFactory.getLogger(MarketValueAnswers.class);

    private final UserWeekLoader loader;
    private final PlayerLookup players;
    private final MarketValuation valuation;
    private final KeeperCosts keeperCosts;
    private final AdviceWriter writer;
    private final AdviceForm<AdviceAnswer> form;
    private final DecisionRecords records;
    private final String userId;
    private final Clock clock;

    public MarketValueAnswers(UserWeekLoader loader, PlayerLookup players,
            MarketValuation valuation, KeeperCosts keeperCosts, AdviceWriter writer,
            AdviceCheck check, DecisionRecords records, OttoProperties properties,
            Clock clock) {
        this.loader = loader;
        this.players = players;
        this.valuation = valuation;
        this.keeperCosts = keeperCosts;
        this.writer = writer;
        this.form = AdviceForm.of(INSTRUCTIONS, check::check);
        this.records = records;
        this.userId = properties.userId();
        this.clock = clock;
    }

    /**
     * The checked answer.
     *
     * @param message the text to send the user as written
     * @param source where the value comes from, FantasyCalc or
     *        DynastyProcess
     */
    public record Reply(String message, String source) {
    }

    /**
     * Prices a player or draft pick in the league's format and has the
     * model explain it under the advice check.
     *
     * <p>Side effects: the market reads (at most one request a day to
     * each source for each format), up to two model calls, and one
     * Decision Record write. In a keeper league it reads the league's
     * drafts, and a sent answer that asks for the league's keeper rule
     * records that it asked.
     *
     * @param reference a player's name or Sleeper id, or a pick such as
     *        "2027 1st"
     * @return the checked answer, or why there is none, in plain words
     */
    public ToolAnswer<Reply> answer(String reference) {
        Instant now = clock.instant();
        Optional<DraftPick> pick = DraftPick.parse(reference);
        SourceResult<MarketValuation.Subject> subject = pick
                .<SourceResult<MarketValuation.Subject>>map(drafted ->
                        new SourceResult.Ok<>(new MarketValuation.Subject.Pick(drafted)))
                .orElseGet(() -> player(reference));
        return switch (subject.flatMap(found -> leagueAndRosters().flatMap(league ->
                valuation.quote(league.league(), league.rosters(), found, now)
                        .flatMap(quote -> new SourceResult.Ok<>(
                                new Priced(league.league(), quote)))))) {
            case SourceResult.Unavailable<Priced> unavailable ->
                    ToolAnswer.unavailable(unavailable.reason());
            case SourceResult.Ok<Priced> priced -> explained(priced.value(), pick, now);
        };
    }

    private record Priced(SleeperAdapter.League league, MarketQuote quote) {
    }

    private record LeagueAndRosters(SleeperAdapter.League league, List<RosterSnapshot> rosters) {
    }

    private ToolAnswer<Reply> explained(Priced priced, Optional<DraftPick> pick, Instant now) {
        EvidenceBundle bundle = MarketValueEvidence.bundle(priced.quote(), pick, now);
        AdviceResult<AdviceAnswer> result = writer.write(bundle, form);
        Outcome outcome = switch (result) {
            case AdviceResult.Approved<AdviceAnswer> approved ->
                    new Outcome.Sent(approved.answer().message());
            case AdviceResult.Rejected<AdviceAnswer> rejected -> new Outcome.WithheldWithNotice(
                    "The answer failed its checks twice: "
                            + String.join(" ", rejected.failures()), WITHHELD);
            case AdviceResult.Unavailable<AdviceAnswer> unavailable ->
                    new Outcome.WithheldWithNotice(unavailable.reason(), MODEL_UNAVAILABLE);
        };
        recordBestEffort(priced, bundle, result, outcome, now);
        boolean askedForKeeperRule = priced.quote().keeper()
                .flatMap(MarketQuote.Keeper::cost)
                .flatMap(KeeperCost::question)
                .isPresent();
        if (outcome instanceof Outcome.Sent && askedForKeeperRule) {
            keeperCosts.asked(priced.league().leagueId());
        }
        String source = priced.quote().source().label();
        return switch (outcome) {
            case Outcome.Sent sent -> ToolAnswer.of(new Reply(sent.message(), source));
            case Outcome.WithheldWithNotice withheld ->
                    ToolAnswer.unavailable(withheld.notice());
            default -> throw new IllegalStateException("Unexpected outcome " + outcome);
        };
    }

    private void recordBestEffort(Priced priced, EvidenceBundle bundle,
            AdviceResult<AdviceAnswer> result, Outcome outcome, Instant now) {
        String leagueId = priced.league().leagueId();
        DecisionRecord record = DecisionRecord.forRun(
                        RunIds.stable("market-value-", now.toString(), priced.quote().id()),
                        RunKind.MARKET_VALUE, now, userId, leagueId,
                        OttoJson.MAPPER.valueToTree(priced.league()))
                .evidence(bundle.recorded())
                .versions(bundle.versions(form, result))
                .exchanges(result.exchanges())
                .outcome(outcome);
        try {
            records.saveFirst(record);
        } catch (RuntimeException e) {
            log.warn("Cannot save the market-value Decision Record {}", record.runId(), e);
        }
    }

    private SourceResult<MarketValuation.Subject> player(String reference) {
        return switch (players.findFresh(reference)) {
            case PlayerLookup.Match.Found found -> new SourceResult.Ok<>(
                    new MarketValuation.Subject.Player(found.player().playerId(),
                            found.player().fullName()));
            case PlayerLookup.Match.NotFound notFound ->
                    new SourceResult.Unavailable<>("player directory", notFound.reason());
        };
    }

    private SourceResult<LeagueAndRosters> leagueAndRosters() {
        return loader.league().flatMap(league -> {
            if (league.rules().format().filter(LeagueRules.LeagueFormat.KEEPER::equals)
                    .isEmpty()) {
                return new SourceResult.Ok<>(new LeagueAndRosters(league, List.of()));
            }
            return loader.leagueWeek().flatMap(week -> new SourceResult.Ok<>(
                    new LeagueAndRosters(league, week.rosters())));
        });
    }
}
