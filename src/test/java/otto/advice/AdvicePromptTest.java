package otto.advice;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdvicePromptTest {

    @Test
    void thePreambleHoldsEveryFactToTheEvidence() {
        String preamble = AdvicePrompt.preamble("about his team",
                AdvicePrompt.Naming.LISTED_BY_FULL_NAME);

        assertThat(preamble)
                .contains("texting your one user about his team, the way")
                .contains("State facts only from the\nevidence bundle")
                .contains("introduce each by full name");
    }

    @Test
    void anAnswerThatNamesNoPlayerListsNone() {
        assertThat(AdvicePrompt.preamble("", AdvicePrompt.Naming.NO_PLAYER))
                .contains("texting your one user, the way")
                .contains("Name no player.");
        assertThat(AdvicePrompt.answerShape(AdvicePrompt.Naming.NO_PLAYER))
                .contains("\"players\": [],");
    }
}
