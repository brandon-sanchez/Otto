package otto.advice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;

import otto.records.Evidence;
import otto.records.Versions;
import otto.storage.OttoJson;

/**
 * Everything the model may know when it writes one piece of advice.
 * Facts may come only from here, and {@link AdviceCheck} holds every
 * answer to it.
 *
 * @param task what the model is asked to write, in plain words
 * @param legalMoves every lineup change code verified as legal
 * @param noChangeAllowed whether recommending no change is legal
 * @param kickoffPhrase the words the message must use for the time
 *        left before the game, or empty when the message need not say
 * @param maxLength the longest message allowed, in characters
 * @param budget the verified waiver budget left, in dollars, or empty
 *        when none was verified, in which case no dollar amount may be
 *        stated
 */
public record EvidenceBundle(
        String task,
        List<EvidenceItem> evidence,
        List<BundlePlayer> players,
        List<LineupMove> legalMoves,
        boolean noChangeAllowed,
        Optional<String> kickoffPhrase,
        int maxLength,
        Optional<Integer> budget) {

    private static final Pattern NON_DOLLAR_NUMBER = Pattern.compile("(?<![\\w.$])\\d+(?:\\.\\d+)?(?!\\w)");

    public EvidenceBundle {
        evidence = List.copyOf(evidence);
        players = List.copyOf(players);
        legalMoves = List.copyOf(legalMoves);
        if (evidence.stream().map(EvidenceItem::id).distinct().count() != evidence.size()) {
            throw new IllegalArgumentException("Evidence ids repeat within a bundle");
        }
    }

    public Optional<EvidenceItem> item(String id) {
        return evidence.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    public Optional<BundlePlayer> player(String playerId) {
        return players.stream().filter(player -> player.playerId().equals(playerId)).findFirst();
    }

    /**
     * A hash of what the evidence says, leaving out when it was read and
     * how long is left before kickoff. Two runs over the same facts share
     * it, so advice is never retried against unchanged evidence.
     */
    public String identity() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("task", task);
        facts.put("evidence", evidence.stream()
                .map(item -> List.of(item.id(), item.source(), item.scoringBasis(),
                        new TreeMap<>(item.facts())))
                .toList());
        facts.put("players", players);
        facts.put("legalMoves", legalMoves);
        facts.put("noChangeAllowed", noChangeAllowed);
        facts.put("budget", budget);
        try {
            return Sha256.hex(OttoJson.MAPPER.writeValueAsString(facts));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint an evidence bundle", e);
        }
    }

    /**
     * Returns the bundle's evidence as a Decision Record keeps it: every
     * item read, with no copied text.
     */
    public List<Evidence> recorded() {
        return evidence.stream()
                .map(item -> Evidence.read(item.id(), item.source(), item.publishedAt(),
                        item.retrievedAt(), item.scoringBasis()))
                .toList();
    }

    /**
     * Returns the versions of a run that asked the model about this
     * bundle: its {@link #identity} as the data version, the form's
     * prompt version and the model that answered.
     */
    public Versions versions(AdviceForm<?> form, AdviceResult<?> result) {
        return Versions.ofThisBuild(identity(), form.promptVersion(), result.model());
    }

    Set<BigDecimal> numbers() {
        return numbersOf(Stream.concat(
                evidence.stream().flatMap(item -> item.facts().values().stream()),
                kickoffPhrase.stream()));
    }

    static Set<BigDecimal> numbersOf(Stream<String> texts) {
        return texts.flatMap(text -> numbersIn(text).stream())
                .map(EvidenceBundle::normalized)
                .collect(Collectors.toSet());
    }

    static List<String> numbersIn(String text) {
        Matcher matcher = NON_DOLLAR_NUMBER.matcher(text);
        List<String> numbers = new ArrayList<>();
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    static BigDecimal normalized(String number) {
        return new BigDecimal(number).stripTrailingZeros();
    }
}
