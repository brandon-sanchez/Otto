package otto.trade;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TradeCallTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2.0|you|⚖️ <b>About even</b>",
            "10.0|you|⚖️ <b>Slight edge for you</b>",
            "10.0|Tacos & Beer|⚖️ <b>Slight edge for Tacos &amp; Beer</b>",
            "20.0|you|✅ <b>Clear win</b>",
            "20.0|GridironGoblin|❌ <b>Clear loss</b>"})
    void theVerdictOpensWithOneBoldLine(double gap, String favours, String headline) {
        VerdictBand band = VerdictBand.of(100, 100 - gap);
        TradeCall call = new TradeCall(band, favours, false, "", null);

        assertThat(call.headline()).isEqualTo(headline);
    }
}
