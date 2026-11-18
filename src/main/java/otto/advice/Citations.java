package otto.advice;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Evidence ids a model leaves in text the user reads, such as
 * "[usage-12499, news-12499-1]" at the end of a paragraph. They are
 * citations, not prose: a bracketed list of them is taken out before
 * the text is checked or sent, and the digits inside an id are never a
 * number the text states.
 */
public final class Citations {

    /** The instruction every advice form gives the model about where evidence ids go. */
    public static final String RULE = "Cite evidence ids only in \"claims\". Never write an "
            + "evidence id, or a bracketed list of them, in any text the user reads.\n";

    /** One bracketed or parenthesised group, with the space before it. */
    private static final Pattern GROUP = Pattern.compile("\\s*[\\[(]([^\\[\\]()]*)[\\])]");

    private static final Pattern SEPARATOR = Pattern.compile("[,;\\s]+");

    /** An id only evidence would be written as: one word joined by hyphens, or with digits. */
    private static final Pattern DISTINCTIVE = Pattern.compile(".*[-\\d].*");

    private Citations() {
    }

    /**
     * A text with its citation lists taken out.
     *
     * @param text the text without them
     * @param cited the evidence ids the lists named, in order
     */
    public record Stripped(String text, Set<String> cited) {

        public Stripped {
            cited = Set.copyOf(cited);
        }
    }

    /**
     * Returns the text without every bracketed or parenthesised list
     * that holds nothing but evidence ids of the bundle. Any other
     * bracketed text stays, including a list that names an id the
     * bundle does not have.
     */
    public static Stripped strip(String text, EvidenceBundle bundle) {
        Set<String> ids = ids(bundle);
        Set<String> cited = new LinkedHashSet<>();
        StringBuilder stripped = new StringBuilder();
        Matcher group = GROUP.matcher(text);
        while (group.find()) {
            List<String> tokens = SEPARATOR.splitAsStream(group.group(1).strip())
                    .filter(token -> !token.isEmpty())
                    .toList();
            if (!tokens.isEmpty() && ids.containsAll(tokens)) {
                cited.addAll(tokens);
                group.appendReplacement(stripped, "");
            }
        }
        group.appendTail(stripped);
        return new Stripped(stripped.toString().strip(), cited);
    }

    /** Returns the claims with every citation list taken out of their text. */
    public static List<AdviceAnswer.Claim> strip(List<AdviceAnswer.Claim> claims,
            EvidenceBundle bundle) {
        return claims.stream()
                .map(claim -> new AdviceAnswer.Claim(strip(claim.text(), bundle).text(),
                        claim.evidence()))
                .toList();
    }

    /**
     * Returns the evidence ids the text still writes out, such as
     * "usage-12499" in the middle of a sentence. An id that is also an
     * everyday word, such as "roster", is never counted.
     */
    static List<String> quoted(String text, EvidenceBundle bundle) {
        return distinctive(ids(bundle)).stream()
                .filter(id -> occurrence(id).matcher(text).find())
                .toList();
    }

    /**
     * Returns the text with every evidence id it writes out blanked, so
     * the digits inside one are not read as a number.
     */
    static String masked(String text, EvidenceBundle bundle) {
        String masked = text;
        for (String id : distinctive(ids(bundle))) {
            masked = occurrence(id).matcher(masked).replaceAll(" ".repeat(id.length()));
        }
        return masked;
    }

    private static Set<String> ids(EvidenceBundle bundle) {
        return bundle.evidence().stream().map(EvidenceItem::id).collect(Collectors.toSet());
    }

    /** Longest first, so an id that starts another id never masks only part of it. */
    private static List<String> distinctive(Collection<String> ids) {
        return ids.stream()
                .filter(id -> DISTINCTIVE.matcher(id).matches())
                .sorted(Comparator.comparingInt(String::length).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static Pattern occurrence(String id) {
        return Pattern.compile("(?<![\\w-])" + Pattern.quote(id) + "(?![\\w-])");
    }
}
