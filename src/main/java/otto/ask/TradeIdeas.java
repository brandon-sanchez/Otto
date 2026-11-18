package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdvicePrompt;
import otto.advice.AnswerDelivery;
import otto.advice.BundlePlayer;
import otto.advice.CheckedAdvice;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;
import otto.advice.RankConvention;
import otto.advice.ToolAnswer;
import otto.format.TelegramHtml;
import otto.habits.HabitLean;
import otto.habits.HabitService;
import otto.league.LeagueWeek;
import otto.offers.Offer;
import otto.offers.TradeOffers;
import otto.offers.TradeShape;
import otto.records.Candidate;
import otto.records.RunKind;
import otto.sleeper.SourceResult;
import otto.trade.TradeDeadline;
import otto.trade.TradeDeadlines;

/**
 * Answers "what trades should I make and why?" in chat with named
 * offers. {@link TradeOffers} finds and prices each offer, the model
 * explains them in one message, and {@link AdviceCheck} holds every name
 * and number to the evidence first. A message that pins a player on the
 * wrong manager's offer fails the check too, and so does one that names
 * the user's habits, which only tilt the search. Once trades are closed,
 * by the deadline or by the commissioner, there are no offers: the answer
 * is the weaknesses read, which says trades are closed and points to
 * waivers.
 */
@Component
public class TradeIdeas {

    /** Telegram's limit is 4096 characters; this keeps a long list inside it. */
    private static final int LONGEST = 4000;

    private static final int INTRO_LENGTH = 400;

    private static final int LENGTH_PER_OFFER = 550;

    private static final String RUN_ID_PREFIX = "trade-ideas-";

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private static final String INSTRUCTIONS = AdvicePrompt.preamble(
            "about trades he could offer", AdvicePrompt.Naming.LISTED_BY_FULL_NAME)
            + RankConvention.ADVICE_RULES + FriendVoice.RULE + QuietHabits.RULE + """

            When "trade-deadline" is present, open with it. Then give each "offer-" item
            in order, numbered 1, 2, 3, with a blank line between offers, laid out as:
            1. Send to <b>manager</b>
            You give: what "youGive" says, in those words
            You get: what "youGet" says, in those words
            Then one short line on why it works for him ("yourEdge", and "yourLineup"
            when it adds something), and one short line on why the other manager would
            say yes, drawn from the "why" facts and "partnerDirection". When an offer
            has "playoffWeeks", add it to that line with its weeks, opponents and ranks
            exactly as written. When
            a player in the offer has a buy-low or sell-high item, say so in a clause and
            why. When a player in the offer has an "injury-" item, say what he is worth
            while hurt from its "worth", give his timeline exactly as its "timeline" states
            it, naming who reported it, and say why the timing suits both sides from its
            "timing". Never give a return date the item doesn't state. Keep each player
            with the offer and manager the evidence puts him in. When "found" says only
            some offers fit, say so in one sentence. Never claim to send or accept a trade.
            Never write a confidence label such as High or Medium. Stay under "maxLength"
            characters.
            """ + AdvicePrompt.answerShape(AdvicePrompt.Naming.LISTED_BY_FULL_NAME);

    private static final CheckedAdvice.Fallbacks FALLBACKS = new CheckedAdvice.Fallbacks(
            "I cannot reach my language model to explain these trade ideas right now",
            "I found trade offers, but my explanation failed its fact check twice, so I am "
                    + "holding it back rather than risk a wrong name or figure");

    private final UserWeekLoader loader;
    private final TradeOffers offers;
    private final TeamWeaknesses weaknesses;
    private final HabitService habits;
    private final TradeDeadlines deadlines;
    private final CheckedAdvice advice;
    private final AdviceCheck check;

    public TradeIdeas(UserWeekLoader loader, TradeOffers offers, TeamWeaknesses weaknesses,
            HabitService habits, TradeDeadlines deadlines, CheckedAdvice advice,
            AdviceCheck check) {
        this.loader = loader;
        this.offers = offers;
        this.weaknesses = weaknesses;
        this.habits = habits;
        this.deadlines = deadlines;
        this.advice = advice;
        this.check = check;
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
     * <p>Side effects: the habit capture's reads, the reads
     * {@link TradeOffers#find} makes, one or two model calls, and one
     * Decision Record of kind {@link RunKind#TRADE_IDEAS} whatever the
     * outcome. Once trades are closed, the weaknesses answer's reads and
     * its Decision Record instead.
     *
     * @param count how many offers he asked for; null for the default of
     *        {@link TradeOffers#DEFAULT_COUNT}, or when he asked for more
     *        without a number
     * @param shown how many offers he has already seen when he asks for
     *        more; null otherwise
     * @param shape the shape he asked for, such as "3-for-2"; null for
     *        the default shapes. A side of more than
     *        {@link TradeShape#MOST_PIECES} pieces is answered as
     *        unavailable
     * @param delivery how a checked message reaches the user, which the
     *        Decision Record keeps as its outcome
     * @return the checked message, or why there is none in plain words
     */
    public ToolAnswer<Answer> answer(Integer count, Integer shown, String shape, Instant now,
            AnswerDelivery delivery) {
        if (shown != null && count == null) {
            return ToolAnswer.unavailable(("he has seen %d trade ideas and asked for more "
                    + "without saying how many: ask him how many more he wants, then call "
                    + "again with that count").formatted(shown));
        }
        if (count != null && count < 1) {
            return ToolAnswer.unavailable("ask for at least one trade idea");
        }
        if (TradeShape.tooLarge(shape)) {
            return ToolAnswer.unavailable(("he asked for a %s trade, and I build trades of up "
                    + "to %d players a side: tell him that plainly, and offer to build one "
                    + "that size or smaller").formatted(shape.strip(), TradeShape.MOST_PIECES));
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
        return ToolAnswer.from(loader.leagueWeek(),
                league -> answer(league, request, now, delivery));
    }

    private ToolAnswer<Answer> answer(LeagueWeek league, TradeOffers.Request request,
            Instant now, AnswerDelivery delivery) {
        Optional<TradeDeadline> deadline = deadlines.assess(league);
        if (deadline.filter(TradeDeadline::closed).isPresent()) {
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
            AnswerDelivery delivery) {
        int maxLength = Math.min(LONGEST, INTRO_LENGTH + LENGTH_PER_OFFER * found.size());
        EvidenceBundle bundle = QuietHabits.add(TradeIdeaEvidence.bundle(
                new TradeIdeaEvidence.Facts(league.league().name(), found, request.count(),
                        request.skip(), request.shape().map(TradeShape::words),
                        deadline.map(TradeDeadline::message)), maxLength, now), lean, now);
        return advice.write(bundle, form(found), new CheckedAdvice.Run(RUN_ID_PREFIX,
                        List.of(league.league().leagueId()), RunKind.TRADE_IDEAS,
                        league.league(), candidates(found), "explanation"), now, delivery)
                .answer(Answer::new, FALLBACKS);
    }

    /**
     * The advice check, plus one rule of its own: a sentence that names
     * one manager may name only the players in that manager's offers, so
     * an offer can never ask for a player the partner doesn't have.
     */
    private AdviceForm<AdviceAnswer> form(List<Offer> found) {
        return AdviceForm.of(INSTRUCTIONS, (bundle, reply) -> {
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
            failures.addAll(misplaced(bundle, found,
                    TelegramHtml.words(answer.get().message())));
            return AdviceCheck.Verdict.checked(answer.get(), failures);
        });
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

    private static List<Candidate> candidates(List<Offer> found) {
        List<Candidate> candidates = new ArrayList<>();
        for (int index = 0; index < found.size(); index++) {
            Offer offer = found.get(index);
            candidates.add(new Candidate("offer-" + (index + 1), "%s gets %s for %s".formatted(
                    offer.partner(), names(offer.gives()), names(offer.gets())), true,
                    offer.kind().name() + " " + offer.shape().words()));
        }
        return candidates;
    }

    private static String names(List<Offer.Piece> pieces) {
        return String.join(" and ", pieces.stream().map(Offer.Piece::name).toList());
    }
}
