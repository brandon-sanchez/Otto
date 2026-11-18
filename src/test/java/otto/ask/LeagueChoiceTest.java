package otto.ask;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.ask.LeagueChoice.Choice;
import otto.followed.FollowedLeague;
import otto.sleeper.LeagueRules.LeagueFormat;

class LeagueChoiceTest {

    private static final FollowedLeague CANES =
            new FollowedLeague("100", "Raising Canes Demon", LeagueFormat.REDRAFT);
    private static final FollowedLeague BROS =
            new FollowedLeague("200", "Dynasty Bros", LeagueFormat.DYNASTY);
    private static final List<FollowedLeague> BOTH = List.of(CANES, BROS);

    private static final String LINEUP = "who should I start this week?";

    private static Choice choose(String message, List<FollowedLeague> followed,
            String waiting, String chatLeague) {
        return LeagueChoice.of(message, followed, Optional.ofNullable(waiting),
                Optional.ofNullable(chatLeague));
    }

    @Test
    void withOneLeagueEveryMessageIsAboutIt() {
        assertThat(choose(LINEUP, List.of(CANES), null, null))
                .isEqualTo(new Choice.InOne(CANES, LINEUP, false, LeaguePick.ONLY_LEAGUE));
    }

    @Test
    void withOneLeagueAskingAboutEveryLeagueIsAnsweredThereAndSaysSo() {
        String message = "what are the weaknesses on all my teams?";

        assertThat(choose(message, List.of(CANES), null, null)).isEqualTo(
                new Choice.InOne(CANES, message, false, LeaguePick.EVERY_LEAGUE_ASKED));
    }

    @Test
    void withOneLeagueTheChatsMemoryHasNoSay() {
        assertThat(choose("the dynasty one", List.of(CANES), LINEUP, "200")).isEqualTo(
                new Choice.InOne(CANES, "the dynasty one", false, LeaguePick.ONLY_LEAGUE));
    }

    @Test
    void aMessageThatNamesALeagueIsAboutItOverTheChatsLeague() {
        String message = "who should I start in Dynasty Bros?";

        assertThat(choose(message, BOTH, null, "100"))
                .isEqualTo(new Choice.InOne(BROS, message, true, LeaguePick.CHOSEN));
    }

    @Test
    void aMessageThatSaysEveryLeagueIsAboutEachOne() {
        String message = "how do all my leagues look?";

        assertThat(choose(message, BOTH, null, "100"))
                .isEqualTo(new Choice.InEach(BOTH, message, false));
    }

    @Test
    void aMessageThatNamesNoLeagueIsAboutTheChatsLeague() {
        assertThat(choose(LINEUP, BOTH, null, "200"))
                .isEqualTo(new Choice.InOne(BROS, LINEUP, false, LeaguePick.CHOSEN));
    }

    @Test
    void aChatLeagueOttoNoLongerFollowsChoosesNothing() {
        assertThat(choose(LINEUP, List.of(CANES, new FollowedLeague("300", "Office", null)),
                null, "200")).isEqualTo(new Choice.NoneYet(LINEUP));
    }

    @Test
    void withNothingToGoOnNoLeagueIsChosen() {
        assertThat(choose(LINEUP, BOTH, null, null)).isEqualTo(new Choice.NoneYet(LINEUP));
    }

    @Test
    void aShortReplyThatPicksALeagueAnswersTheQuestionThatWaited() {
        assertThat(choose("the dynasty one", BOTH, LINEUP, null))
                .isEqualTo(new Choice.InOne(BROS, LINEUP, true, LeaguePick.CHOSEN));
    }

    @Test
    void allOfThemAsAReplyAnswersTheQuestionThatWaitedInEachLeague() {
        assertThat(choose("all of them", BOTH, LINEUP, null))
                .isEqualTo(new Choice.InEach(BOTH, LINEUP, true));
    }

    @Test
    void aNewQuestionLeavesTheWaitingOneAndIsAskedOnItsOwn() {
        String message = "what was Isaiah Likely's snap share?";

        assertThat(choose(message, BOTH, LINEUP, null))
                .isEqualTo(new Choice.NoneYet(message));
    }
}
