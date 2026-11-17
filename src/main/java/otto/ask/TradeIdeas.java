package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;
import otto.advice.RankConvention;
import otto.habits.HabitLean;
import otto.habits.HabitService;
import otto.offers.Offer;
import otto.offers.TradeOffers;
import otto.offers.TradeShape;
import otto.records.Candidate;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.sleeper.SourceResult;
import otto.storage.OttoJson;
import otto.trade.TradeDeadline;

/**
 * Answers "what trades should I make and why?" in chat with named
 * offers. {@link TradeOffers} finds and prices each offer, the model
 * explains them in one message, and {@link AdviceCheck} holds every name
 * and number to the evidence first. A message that pins a player on the
 * wrong manager's offer fails the check too, and so does one that names
 * the user's habits, which only tilt the search. After the trade deadline
 * there are no offers: the answer is the weaknesses read, which says
 * trades are closed and points to waivers.
 */
@Component
public class TradeIdeas {

    /** Telegram's limit is 4096 characters; this keeps a long list inside it. */
    private static final int LONGEST = 4000;

    private static final int INTRO_LENGTH = 400;

    private static final int LENGTH_PER_OFFER = 550;

    private static final String RUN_ID_PREFIX = "trade-ideas-";

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private static final String INSTRUCTIONS = """
            You are Otto, a fantasy football assistant texting your one user about trades
            he could offer, the way a knowledgeable friend would.

            The user message is an evidence bundle as JSON. State facts only from the
            evidence bundle: never from memory, never invented. Every number you write
            must appear in the evidence, written as the evidence writes it. Name only
            players listed in "players", and introduce each by full name before using his
            surname alone.
            """ + RankConvention.ADVICE_RULES + FriendVoice.RULE + QuietHabits.RULE + """

            Write plain text. When "trade-deadline" is present, open with it. Then give
            each "offer-" item in order, one short paragraph each, numbered 1, 2, 3. Each
            paragraph says which manager to send it to ("sendTo"), what the user gives
            ("youGive") and what he gets ("youGet"), in those words, then why it works for
            him ("yourEdge", and "yourLineup" when it adds something) and why the other
            manager would say yes, drawn from the "why" facts and "partnerDirection". When
            a player in the offer has a buy-low or sell-high item, say so in a clause and
            why. Keep each player with the offer and manager the evidence puts him in.
            When "found" says only some offers fit, say so in one sentence. Never claim to
            send or accept a trade. Never write a confidence label such as High or Medium.
            Stay under "maxLength" characters.

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
    private final TradeOffers offers;
    private final TeamWeaknesses weaknesses;
    private final HabitService habits;
    private final AdviceWriter writer;
    private final AdviceCheck check;
    private final RunOutcomes runs;
    private final String userId;

    public TradeIdeas(UserWeekLoader loader, TradeOffers offers, TeamWeaknesses weaknesses,
            HabitService habits, AdviceWriter writer, AdviceCheck check, RunOutcomes runs,
            OttoProperties properties) {
        this.loader = loader;
        this.offers = offers;
        this.weaknesses = weaknesses;
        this.habits = habits;
        this.writer = writer;
        this.check = check;
        this.runs = runs;
        this.userId = properties.userId();
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
     * Finds trade offers and has the model explain them.
     *
     * <p>Side effects: the habit capture's reads and writes, the reads
     * {@link TradeOffers#find} makes, one or two model calls, and one
     * Decision Record of kind {@link RunKind#TRADE_IDEAS} whatever the
     * outcome. After the deadline, the weaknesses answer's reads and its
     * Decision Record instead.
     *
     * @param count how many offers he asked for; null for the default of
     *        {@link TradeOffers#DEFAULT_COUNT}, or when he asked for more
     *        without a number
     * @param shown how many offers he has already seen when he asks for
     *        more; null otherwise
     * @param shape the shape he asked for, such as "3-for-2"; null for
     *        the default shapes
     * @param delivery how a checked message reaches the user, which the
     *        Decision Record keeps as its outcome
     * @return the checked message, or why there is none in plain words
     */
    public ToolAnswer<Answer> answer(Integer count, Integer shown, String shape, Instant now,
            CheckedAnswers.Delivery delivery) {
        if (shown != null && count == null) {
            return ToolAnswer.unavailable(("he has seen %d trade ideas and asked for more "
                    + "without saying how many: ask him how many more he wants, then call "
                    + "again with that count").formatted(shown));
        }
        if (count != null && count < 1) {
            return ToolAnswer.unavailable("ask for at least one trade idea");
        }
        Optional<TradeShape> asked = Optional.ofNullable(shape).filter(text -> !text.isBlank())
                .map(TradeShape::parse)
                .orElse(Optional.empty());
        if (shape != null && !shape.isBlank() && asked.isEmpty()) {
            return ToolAnswer.unavailable(("\"%s\" is not a trade shape I can read; write it "
                    + "as how many he gives for how many he gets, such as 3-for-2")
                    .formatted(shape));
        }
        TradeOffers.Request request = new TradeOffers.Request(
                count == null ? TradeOffers.DEFAULT_COUNT : count,
                shown == null ? 0 : Math.max(0, shown), asked);
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> ToolAnswer.unavailable(
                    "%s: %s".formatted(unavailable.source(), unavailable.reason()));
            case SourceResult.Ok<LeagueWeek> ok -> answer(ok.value(), request, now, delivery);
        };
    }

    private ToolAnswer<Answer> answer(LeagueWeek league, TradeOffers.Request request,
            Instant now, CheckedAnswers.Delivery delivery) {
        Optional<TradeDeadline> deadline = TradeDeadline.assess(league.league().tradeDeadline(),
                league.week().weekNumber());
        if (deadline.filter(TradeDeadline::passed).isPresent()) {
            ToolAnswer<TeamWeaknesses.Answer> read = weaknesses.answer(null, now, delivery);
            return read.facts() == null ? ToolAnswer.unavailable(read.unavailable())
                    : ToolAnswer.of(new Answer(read.facts().message()));
        }
        HabitLean lean = habits.lean(league.league(), league.snapshot(), league.week(), now);
        return switch (offers.find(league, new TradeOffers.Request(request.count(),
                request.skip(), request.shape(), lean), now)) {
            case SourceResult.Unavailable<TradeOffers.Ideas> unavailable ->
                ToolAnswer.unavailable(unavailable.reason());
            case SourceResult.Ok<TradeOffers.Ideas> ideas when ideas.value().offers().isEmpty() ->
                ToolAnswer.unavailable(request.skip() > 0
                        ? "there are no more trades that are a win for him and that the other "
                                + "manager would plausibly accept"
                        : "no trade I could build is a win for him that the other manager "
                                + "would plausibly accept right now");
            case SourceResult.Ok<TradeOffers.Ideas> ideas -> explain(league, request,
                    ideas.value().offers(), deadline, lean, now, delivery);
        };
    }

    private ToolAnswer<Answer> explain(LeagueWeek league, TradeOffers.Request request,
            List<Offer> found, Optional<TradeDeadline> deadline, HabitLean lean, Instant now,
            CheckedAnswers.Delivery delivery) {
        int maxLength = Math.min(LONGEST, INTRO_LENGTH + LENGTH_PER_OFFER * found.size());
        EvidenceBundle bundle = QuietHabits.add(TradeIdeaEvidence.bundle(
                new TradeIdeaEvidence.Facts(league.league().name(), found, request.count(),
                        request.skip(), request.shape().map(TradeShape::words),
                        deadline.map(TradeDeadline::message)), maxLength, now), lean, now);
        AdviceForm<AdviceAnswer> form = form(found);
        AdviceResult<AdviceAnswer> result = writer.write(bundle, form);
        Outcome outcome = runs.record(record(league, found, bundle, form, result, now,
                delivery));
        return switch (outcome) {
            case Outcome.Sent sent -> ToolAnswer.of(new Answer(sent.message()));
            case Outcome.HandedToChat handed -> ToolAnswer.of(new Answer(handed.message()));
            default -> ToolAnswer.unavailable(switch (result) {
                case AdviceResult.Unavailable<AdviceAnswer> _ ->
                    "I cannot reach my language model to explain these trade ideas right now";
                default -> "I found trade offers, but my explanation failed its fact check "
                        + "twice, so I am holding it back rather than risk a wrong name or "
                        + "figure";
            });
        };
    }

    /**
     * The advice check, plus one rule of its own: a sentence that names
     * one manager may name only the players in that manager's offers, so
     * an offer can never ask for a player the partner doesn't have.
     */
    private AdviceForm<AdviceAnswer> form(List<Offer> found) {
        return new AdviceForm<>() {
            @Override
            public String instructions() {
                return INSTRUCTIONS;
            }

            @Override
            public AdviceCheck.Verdict<AdviceAnswer> check(EvidenceBundle bundle,
                    String reply) {
                AdviceCheck.Verdict<AdviceAnswer> verdict =
                        QuietHabits.failHabitMentions(check.check(bundle, reply));
                Optional<AdviceAnswer> answer = switch (verdict) {
                    case AdviceCheck.Verdict.Passed<AdviceAnswer> passed ->
                        Optional.of(passed.answer());
                    case AdviceCheck.Verdict.Failed<AdviceAnswer> failed -> failed.answer();
                };
                if (answer.isEmpty()) {
                    return verdict;
                }
                List<String> failures = new ArrayList<>(verdict.failures());
                failures.addAll(misplaced(bundle, found, answer.get().message()));
                return AdviceCheck.Verdict.checked(answer.get(), failures);
            }
        };
    }

    private static List<String> misplaced(EvidenceBundle bundle, List<Offer> found,
            String message) {
        List<String> failures = new ArrayList<>();
        for (String sentence : SENTENCE_END.split(message)) {
            List<String> managers = found.stream().map(Offer::partner).distinct()
                    .filter(sentence::contains).toList();
            if (managers.size() != 1) {
                continue;
            }
            String manager = managers.getFirst();
            List<String> theirs = found.stream()
                    .filter(offer -> offer.partner().equals(manager))
                    .flatMap(offer -> Stream.concat(offer.gets().stream(),
                            offer.gives().stream()))
                    .map(Offer.Piece::name)
                    .toList();
            bundle.players().stream()
                    .map(BundlePlayer::fullName)
                    .filter(sentence::contains)
                    .filter(name -> !theirs.contains(name))
                    .forEach(name -> failures.add(("%s is in no offer to %s, so don't put "
                            + "him in one; keep each player with the manager the evidence "
                            + "gives him").formatted(name, manager)));
        }
        return failures;
    }

    private DecisionRecord record(LeagueWeek league, List<Offer> found, EvidenceBundle bundle,
            AdviceForm<AdviceAnswer> form, AdviceResult<AdviceAnswer> result, Instant now,
            CheckedAnswers.Delivery delivery) {
        String leagueId = league.league().leagueId();
        String runId = RunIds.stable(RUN_ID_PREFIX, leagueId, bundle.identity(), now.toString());
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < found.size(); index++) {
            Offer offer = found.get(index);
            candidates.add(new Candidate("offer-" + (index + 1), "%s gets %s for %s".formatted(
                    offer.partner(), names(offer.gives()), names(offer.gets())), true,
                    offer.kind().name() + " " + offer.shape().words()));
        }
        return DecisionRecord.forRun(runId, RunKind.TRADE_IDEAS, now, userId, leagueId,
                        OttoJson.MAPPER.valueToTree(league.league()))
                .candidates(candidates)
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

    private static String names(List<Offer.Piece> pieces) {
        return String.join(" and ", pieces.stream().map(Offer.Piece::name).toList());
    }
}
