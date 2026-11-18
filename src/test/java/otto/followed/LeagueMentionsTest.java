package otto.followed;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import otto.sleeper.LeagueRules.LeagueFormat;

import static org.assertj.core.api.Assertions.assertThat;

class LeagueMentionsTest {

    private static final FollowedLeague CANES = new FollowedLeague("1", "Raising Canes Demon",
            LeagueFormat.REDRAFT);
    private static final FollowedLeague MAFIA = new FollowedLeague("2", "Mahomes Mafia",
            LeagueFormat.DYNASTY);
    private static final List<FollowedLeague> LEAGUES = List.of(CANES, MAFIA);

    @ParameterizedTest
    @ValueSource(strings = {
        "should I start all of them?",
        "rank all the teams in my league",
        "what does each team need at RB",
        "both teams have byes, who wins?",
        "should I start both of them"})
    void aQuestionAboutPlayersOrTeamsIsNotAboutEveryLeague(String question) {
        assertThat(LeagueMentions.everyLeague(question)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"all of them", "both", "every league", "all my leagues",
        "what are the weaknesses on all my teams?", "how are both leagues doing"})
    void aReplyOrAnExplicitPhraseIsAboutEveryLeague(String message) {
        assertThat(LeagueMentions.everyLeague(message)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"should I start Mahomes?", "what's his dynasty value?",
        "is he a keeper?"})
    void aPlayerNameOrAFormatWordInAQuestionNamesNoLeague(String question) {
        assertThat(LeagueMentions.named(question, LEAGUES)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"who should I start in Mahomes Mafia?",
        "who should I start in the mafia league?", "who should I start in my dynasty league?",
        "the dynasty one", "dynasty", "mafia"})
    void aWholeNameANamedLeagueWordOrAShortReplyNamesTheLeague(String message) {
        assertThat(LeagueMentions.named(message, LEAGUES)).containsExactly(MAFIA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"who should I start in my redraft league?"})
    void aFormatTwoLeaguesShareNamesNeither(String question) {
        FollowedLeague other = new FollowedLeague("3", "Office Pool", LeagueFormat.REDRAFT);

        assertThat(LeagueMentions.named(question, List.of(CANES, other))).isEmpty();
    }
}
