package otto.followed;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import otto.sleeper.LeagueRules.LeagueFormat;

/**
 * Reads which of the owner's leagues a chat message names, in code and
 * without a model call.
 *
 * <p>A question names a league by its whole name, or by a distinctive
 * word of its name or its format followed by "league", "team" or "one"
 * ("the Canes league", "my dynasty league"). A bare "Mahomes" or
 * "dynasty value" names no league, so a league called "Mahomes Mafia"
 * does not take over a question about the player. A short reply to
 * "which league?" may use the bare word ("dynasty").
 *
 * <p>A question names every league with "every league", "all my
 * leagues", "both leagues" or "all my teams". "All of them" and "both"
 * name every league only as a short reply: in a question they are as
 * likely to mean players.
 */
public final class LeagueMentions {

    private static final Pattern EVERY_LEAGUE = Pattern.compile(
            "\\b(every|each) league\\b|\\b(all|both)( of)?( my| the)? leagues\\b"
                    + "|\\b(all|both)( of)? my teams\\b");

    private static final Set<String> EVERY_WORDS = Set.of("all", "both", "every", "each");

    /** The words after a name word or format word that make it name a league. */
    private static final Set<String> LEAGUE_NOUNS = Set.of("league", "team", "one");

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
     * word only one of them has, then by a format only one of them has.
     * Empty when the message names none.
     */
    public static List<FollowedLeague> named(String message, List<FollowedLeague> leagues) {
        List<String> tokens = Arrays.asList(words(message).split(" "));
        String spaced = " " + String.join(" ", tokens) + " ";
        boolean reply = onlyAChoice(message, leagues);
        List<FollowedLeague> byName = leagues.stream()
                .filter(league -> league.name() != null && !words(league.name()).isBlank())
                .filter(league -> spaced.contains(" " + words(league.name()) + " "))
                .toList();
        if (!byName.isEmpty()) {
            return byName;
        }
        Predicate<String> namesALeague = word -> tokens.contains(word)
                && (reply || followedByALeagueNoun(tokens, word));
        List<FollowedLeague> byWord = leagues.stream()
                .filter(league -> distinctiveWords(league).stream()
                        .filter(word -> leagues.stream().filter(other -> other != league)
                                .noneMatch(other -> distinctiveWords(other).contains(word)))
                        .anyMatch(namesALeague))
                .toList();
        if (!byWord.isEmpty()) {
            return byWord;
        }
        List<FollowedLeague> byFormat = leagues.stream()
                .filter(league -> league.format() != null)
                .filter(league -> namesALeague.test(formatWord(league.format())))
                .toList();
        return byFormat.size() == 1 ? byFormat : List.of();
    }

    /**
     * True when the message is nothing but a league choice, such as "the
     * dynasty one" or "all of them": the reply to "which league?", not a
     * new question.
     */
    public static boolean onlyAChoice(String message, List<FollowedLeague> leagues) {
        Set<String> choiceWords = new HashSet<>();
        leagues.forEach(league -> {
            choiceWords.addAll(Arrays.asList(words(league.label()).split(" ")));
            if (league.format() != null) {
                choiceWords.add(formatWord(league.format()));
            }
        });
        return Arrays.stream(words(message).split(" "))
                .filter(word -> !word.isBlank())
                .allMatch(word -> FILLER.contains(word) || choiceWords.contains(word));
    }

    private static boolean followedByALeagueNoun(List<String> tokens, String word) {
        for (int index = 0; index < tokens.size() - 1; index++) {
            if (tokens.get(index).equals(word) && LEAGUE_NOUNS.contains(tokens.get(index + 1))) {
                return true;
            }
        }
        return false;
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
