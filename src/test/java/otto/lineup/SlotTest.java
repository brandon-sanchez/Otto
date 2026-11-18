package otto.lineup;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SlotTest {

    @ParameterizedTest
    @CsvSource({
        "SUPER_FLEX, Superflex",
        "FLEX, Flex",
        "REC_FLEX, WR/TE Flex",
        "WRRB_FLEX, WR/RB Flex",
        "IDP_FLEX, IDP Flex",
        "QB, QB",
        "DEF, DEF",
        "K, K",
    })
    void aSlotIsNamedInPlainWords(String sleeperName, String label) {
        assertThat(Slot.label(sleeperName)).isEqualTo(label);
    }
}
