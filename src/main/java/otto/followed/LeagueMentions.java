package otto.followed;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import otto.sleeper.LeagueRules.LeagueFormat;

/**
 * Reads which of the owner's leagues a chat message names, in code and
 * without a model call. A message names a league by its whole name, by
 * a distinctive word of its name ("the Canes league"), or by its format
 * ("my dynasty league"). It names every league with words such as "all
 * of them", "every league" or "both".
 */
public final class LeagueMentions {

    /**
     * "Both of them" alone is not here: in a question it is as likely to
     * mean two players as two leagues.
     */
    private static final Pattern EVERY_LEAGUE = Pattern.compile(
            "\\b(all|every|each|both)( of)?( my| the)? (leagues?|teams?)\\b|\\ball of them\\b");

    /** A reply made of these and filler alone, such as "both of them", picks every league. */
    private static final Set<String> EVERY_WORDS = Set.of("all", "both", "every", "each");

    /** Name words too common to tell one league from another. */
    private static final Set<String> COMMON_WORDS = Set.of("league", "leagues", "fantasy",
            "football", "team", "teams", "club", "the", "and", "of", "my", "ff", "nfl");

    /** Words a short reply to "which league?" carries besides the league itself. */
    private static final Set<String> FILLER = Set.of("the", "my", "one", "league", "leagues",
            "in", "for", "of", "about", "please", "them", "that", "this", "team", "teams", "a",
            "it", "is", "its", "oh", "ok", "okay", "sorry", "both", "all", "every", "each");

    private static final int DISTINCTIVE_WORD_LENGTH = 4;

    private LeagueMentions() {
    }

    /** True when the message is about every league the owner has. */
    public static boolean everyLeague(String message) {
        String words = words(message);
        List<String> each = Arrays.asList(words.split(" "));
        return EVERY_LEAGUE.matcher(words).find()
                || each.stream().anyMatch(EVERY_WORDS::contains)
                        && each.stream().allMatch(FILLER::contains);
    }

    /**
     * Returns the leagues among {@code leagues} that the message names,
     * in their order: by whole name first, then by a distinctive name
     * word that only one of them has, then by format. Empty when the
     * message names none.
     */
    public static List<FollowedLeague> named(String message, List<FollowedLeague> leagues) {
        String words = " " + words(message) + " ";
        List<FollowedLeague> byName = leagues.stream()
                .filter(league -> league.name() != null && !words(league.name()).isBlank())
                .filter(league -> words.contains(" " + words(league.name()) + " "))
                .toList();
        if (!byName.isEmpty()) {
            return byName;
        }
        List<FollowedLeague> byWord = leagues.stream()
                .filter(league -> distinctiveWords(league).stream()
                        .anyMatch(word -> words.contains(" " + word + " ")
                                && leagues.stream().filter(other -> other != league)
                                        .noneMatch(other -> distinctiveWords(other)
                                                .contains(word))))
                .toList();
        if (!byWord.isEmpty()) {
            return byWord;
        }
        return leagues.stream()
                .filter(league -> league.format() != null)
                .filter(league -> words.contains(" " + formatWord(league.format()) + " "))
                .toList();
    }

    /**
     * True when the message is nothing but a league choice, such as "the
     * dynasty one" or "all of them": the reply to "which league?", not a
     * new question.
     */
    public static boolean onlyAChoice(String message, List<FollowedLeague> leagues) {
        Set<String> choiceWords = leagues.stream()
                .flatMap(league -> Arrays.stream(words(league.label()).split(" ")))
                .collect(Collectors.toSet());
        leagues.stream().filter(league -> league.format() != null)
                .map(league -> formatWord(league.format()))
                .forEach(choiceWords::add);
        return Arrays.stream(words(message).split(" "))
                .filter(word -> !word.isBlank())
                .allMatch(word -> FILLER.contains(word) || choiceWords.contains(word));
    }

    private static List<String> distinctiveWords(FollowedLeague league) {
        if (league.name() == null) {
            return List.of();
        }
        return Arrays.stream(words(league.name()).split(" "))
                .filter(word -> word.length() >= DISTINCTIVE_WORD_LENGTH)
                .filter(word -> !COMMON_WORDS.contains(word))
                .toList();
    }

    private static String formatWord(LeagueFormat format) {
        return format.name().toLowerCase(Locale.ROOT);
    }

    private static String words(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll(" +", " ")
                .strip();
    }
}
