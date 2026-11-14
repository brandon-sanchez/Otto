package otto.advice;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectoryStore;
import otto.storage.OttoJson;

/**
 * Holds a model-written answer to its evidence bundle before anything is
 * sent. It checks player identities and names, every number, every
 * comparison, the recommended move against the legal moves, any dollar
 * amount against the verified budget, that every claim cites evidence
 * that supports it, and the message's voice: no "lock", no confidence
 * labels, the kickoff phrase when one is required, and the length limit.
 *
 * <p>Each failure is written for the model to read, because the
 * correction attempt hands the failures back to it.
 */
@Component
public class AdviceCheck {

    private static final Pattern LOCK = Pattern.compile("(?i)\\block(s|ed|ing)?\\b");
    private static final Pattern CONFIDENCE_LABEL =
            Pattern.compile("(?i:\\bconfidence\\b)|\\b(HIGH|MEDIUM|LOW)\\b");
    private static final Pattern DOLLARS = Pattern.compile("\\$\\s?(\\d+)");
    private static final Pattern CODE_FENCE = Pattern.compile("(?s)^```(?:json)?\\s*(.*?)\\s*```$");
    private static final String PROJECTION = "projection";

    private final PlayerDirectoryStore directory;

    public AdviceCheck(PlayerDirectoryStore directory) {
        this.directory = directory;
    }

    /**
     * The outcome of checking one reply.
     *
     * @param <T> the shape the reply is read into
     * @param answer the parsed answer, empty when the reply could not be
     *        read as one
     * @param failures empty when the answer passed every check
     */
    public record Verdict<T>(Optional<T> answer, List<String> failures) {

        public Verdict {
            failures = List.copyOf(failures);
        }

        public boolean passed() {
            return answer.isPresent() && failures.isEmpty();
        }
    }

    /** Checks one raw lineup-advice reply against the bundle it was written from. */
    public Verdict<AdviceAnswer> check(EvidenceBundle bundle, String reply) {
        Optional<AdviceAnswer> parsed = parse(reply);
        if (parsed.isEmpty()) {
            return new Verdict<>(Optional.empty(), List.of(
                    "The answer is not the JSON object the instructions ask for, with a "
                            + "non-empty \"message\"."));
        }
        return new Verdict<>(parsed, checkAnswer(bundle, parsed.get()));
    }

    /**
     * Checks an answer another form of advice has already read from its
     * reply, so every form holds its text to the same rules.
     *
     * @return one failure per broken rule, written for the model; empty
     *         when the answer passed every check
     */
    public List<String> checkAnswer(EvidenceBundle bundle, AdviceAnswer answer) {
        List<String> failures = new ArrayList<>();
        checkPlayers(bundle, answer, failures);
        checkNumbers(bundle, answer, failures);
        checkClaims(bundle, answer, failures);
        checkComparisons(bundle, answer, failures);
        checkMove(bundle, answer, failures);
        checkBudget(bundle, answer, failures);
        checkVoice(bundle, answer, failures);
        return failures;
    }

    private void checkPlayers(EvidenceBundle bundle, AdviceAnswer answer, List<String> failures) {
        String message = answer.message();
        for (String playerId : answer.players()) {
            Optional<BundlePlayer> player = bundle.player(playerId);
            if (player.isEmpty()) {
                failures.add("Player id %s is not in the evidence.".formatted(playerId));
            } else if (!message.contains(player.get().fullName())) {
                failures.add("The message never names %s by full name."
                        .formatted(player.get().fullName()));
            }
        }
        for (BundlePlayer player : bundle.players()) {
            int fullName = message.indexOf(player.fullName());
            int surname = firstSurnameOutsideOtherNames(player, bundle, message);
            if (surname >= 0 && (fullName < 0 || surname < fullName)) {
                failures.add("Introduce %s by full name before using his surname alone."
                        .formatted(player.fullName()));
            }
        }
        Set<String> inBundle = bundle.players().stream()
                .map(BundlePlayer::fullName)
                .collect(Collectors.toSet());
        directoryNames().stream()
                .filter(name -> !inBundle.contains(name))
                .filter(name -> message.contains(name) && word(name).matcher(message).find())
                .forEach(name -> failures.add(
                        "The message names %s, who is not in the evidence.".formatted(name)));
    }

    private void checkNumbers(EvidenceBundle bundle, AdviceAnswer answer, List<String> failures) {
        Set<BigDecimal> known = bundle.numbers();
        EvidenceBundle.numbersIn(answer.message()).stream()
                .filter(number -> !known.contains(EvidenceBundle.normalized(number)))
                .distinct()
                .forEach(number -> failures.add(
                        "The number %s is not in the evidence.".formatted(number)));
    }

    private void checkClaims(EvidenceBundle bundle, AdviceAnswer answer, List<String> failures) {
        for (AdviceAnswer.Claim claim : answer.claims()) {
            if (claim.evidence().isEmpty()) {
                failures.add("The claim \"%s\" cites no evidence.".formatted(claim.text()));
                continue;
            }
            List<EvidenceItem> cited = new ArrayList<>();
            for (String id : claim.evidence()) {
                bundle.item(id).ifPresentOrElse(cited::add, () -> failures.add(
                        "The claim \"%s\" cites evidence %s, which does not exist."
                                .formatted(claim.text(), id)));
            }
            Set<BigDecimal> supported = EvidenceBundle.numbersOf(cited.stream()
                    .flatMap(item -> item.facts().values().stream()));
            EvidenceBundle.numbersIn(claim.text()).stream()
                    .filter(number -> !supported.contains(EvidenceBundle.normalized(number)))
                    .distinct()
                    .forEach(number -> failures.add(
                            "The claim \"%s\" states %s, which its cited evidence does not."
                                    .formatted(claim.text(), number)));
        }
    }

    private void checkComparisons(EvidenceBundle bundle, AdviceAnswer answer,
            List<String> failures) {
        for (AdviceAnswer.Comparison comparison : answer.comparisons()) {
            if (!PROJECTION.equals(comparison.basis())) {
                failures.add("The evidence supports only projection comparisons, not \"%s\"."
                        .formatted(comparison.basis()));
                continue;
            }
            Optional<BigDecimal> higher = projection(bundle, comparison.higher(), failures);
            Optional<BigDecimal> lower = projection(bundle, comparison.lower(), failures);
            if (higher.isPresent() && lower.isPresent()
                    && higher.get().compareTo(lower.get()) <= 0) {
                failures.add("%s does not project higher than %s.".formatted(
                        name(bundle, comparison.higher()), name(bundle, comparison.lower())));
            }
        }
    }

    private static Optional<BigDecimal> projection(EvidenceBundle bundle, String playerId,
            List<String> failures) {
        Optional<BundlePlayer> player = bundle.player(playerId);
        if (player.isEmpty()) {
            failures.add("Player id %s is not in the evidence.".formatted(playerId));
            return Optional.empty();
        }
        if (player.get().projectedPoints().isEmpty()) {
            failures.add("%s has no projection, so no projection comparison can name him."
                    .formatted(player.get().fullName()));
            return Optional.empty();
        }
        return player.get().projectedPoints().map(BigDecimal::new);
    }

    private static void checkMove(EvidenceBundle bundle, AdviceAnswer answer,
            List<String> failures) {
        if (answer.move().isEmpty()) {
            if (!bundle.noChangeAllowed()) {
                failures.add("The answer recommends no change, but the lineup must change: "
                        + "choose one of the legal moves.");
            }
            return;
        }
        LineupMove move = answer.move().get();
        if (!bundle.legalMoves().contains(move)) {
            failures.add("The move \"%s\" is not legal. The legal moves are: %s.".formatted(
                    move.describe(), bundle.legalMoves().isEmpty() ? "none"
                            : bundle.legalMoves().stream().map(LineupMove::describe)
                                    .collect(Collectors.joining("; "))));
        }
    }

    private static void checkBudget(EvidenceBundle bundle, AdviceAnswer answer,
            List<String> failures) {
        Matcher dollars = DOLLARS.matcher(answer.message());
        while (dollars.find()) {
            int amount = Integer.parseInt(dollars.group(1));
            if (bundle.budget().isEmpty()) {
                failures.add("The message states $%d, but no budget is in the evidence."
                        .formatted(amount));
            } else if (amount > bundle.budget().get()) {
                failures.add("The message states $%d, more than the verified budget of $%d."
                        .formatted(amount, bundle.budget().get()));
            }
        }
    }

    private static void checkVoice(EvidenceBundle bundle, AdviceAnswer answer,
            List<String> failures) {
        String message = answer.message();
        if (LOCK.matcher(message).find()) {
            failures.add("Never say \"lock\"; say when the game starts.");
        }
        if (CONFIDENCE_LABEL.matcher(message).find()) {
            failures.add("Never write a confidence label; name the specific uncertainty.");
        }
        bundle.kickoffPhrase()
                .filter(phrase -> !message.toLowerCase().contains(phrase.toLowerCase()))
                .ifPresent(phrase -> failures.add(
                        "The message must say \"%s\".".formatted(phrase)));
        if (message.length() > bundle.maxLength()) {
            failures.add("The message is %d characters; the limit is %d."
                    .formatted(message.length(), bundle.maxLength()));
        }
    }

    private Collection<String> directoryNames() {
        return directory.read()
                .map(stored -> stored.players().values().stream()
                        .map(DirectoryPlayer::fullName)
                        .filter(name -> name != null && name.contains(" "))
                        .collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    private static Optional<AdviceAnswer> parse(String reply) {
        String text = reply.strip();
        Matcher fence = CODE_FENCE.matcher(text);
        if (fence.matches()) {
            text = fence.group(1);
        }
        JsonNode root;
        try {
            root = OttoJson.MAPPER.readTree(text);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (!root.isObject() || !root.path("message").isTextual()
                || root.path("message").asText().isBlank()) {
            return Optional.empty();
        }
        JsonNode move = root.path("move");
        Optional<LineupMove> lineupMove = move.isObject()
                ? Optional.of(new LineupMove(text(move, "start"), text(move, "bench"),
                        text(move, "slot")))
                : Optional.empty();
        List<AdviceAnswer.Claim> claims = new ArrayList<>();
        root.path("claims").forEach(claim -> claims.add(new AdviceAnswer.Claim(
                claim.path("text").asText(""), strings(claim.path("evidence")))));
        List<AdviceAnswer.Comparison> comparisons = new ArrayList<>();
        root.path("comparisons").forEach(comparison -> comparisons.add(
                new AdviceAnswer.Comparison(text(comparison, "higher"),
                        text(comparison, "lower"), text(comparison, "basis"))));
        return Optional.of(new AdviceAnswer(root.path("message").asText(), lineupMove,
                strings(root.path("players")), claims, comparisons));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() && !value.isNull() ? value.asText() : null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static String name(EvidenceBundle bundle, String playerId) {
        return bundle.player(playerId).map(BundlePlayer::fullName).orElse(playerId);
    }

    private static int firstSurnameOutsideOtherNames(BundlePlayer player, EvidenceBundle bundle,
            String message) {
        Matcher surname = word(lastName(player.fullName())).matcher(message);
        while (surname.find()) {
            int at = surname.start();
            boolean insideAnotherName = bundle.players().stream()
                    .map(BundlePlayer::fullName)
                    .filter(name -> !name.equals(player.fullName()))
                    .anyMatch(name -> {
                        Matcher other = word(name).matcher(message);
                        while (other.find()) {
                            if (other.start() <= at && at < other.end()) {
                                return true;
                            }
                        }
                        return false;
                    });
            if (!insideAnotherName) {
                return at;
            }
        }
        return -1;
    }

    private static String lastName(String fullName) {
        return fullName.substring(fullName.lastIndexOf(' ') + 1);
    }

    private static Pattern word(String text) {
        return Pattern.compile("\\b" + Pattern.quote(text) + "\\b");
    }
}
