package otto.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramHtmlTest {

    @Test
    void escapesTheCharactersHtmlReserves() {
        assertThat(TelegramHtml.escape("Bros & <Hoes>")).isEqualTo("Bros &amp; &lt;Hoes&gt;");
        assertThat(TelegramHtml.bold("A&B")).isEqualTo("<b>A&amp;B</b>");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "**4th** of 79|<b>4th</b> of 79",
            "__WR4__ this week|<b>WR4</b> this week",
            "## Your lineup|Your lineup",
            "* Start him|• Start him",
            "use `snap share` here|use snap share here",
            "a stray ** mark|a stray  mark",
            "**a** and **b**|<b>a</b> and <b>b</b>",
            "snake_case__name stays|snake_case__name stays"})
    void markdownBecomesTelegramHtml(String markdown, String html) {
        assertThat(TelegramHtml.normalize(markdown)).isEqualTo(html);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Team <3 & co|Team &lt;3 &amp; co",
            "Bros &amp; Co|Bros &amp; Co",
            "Smith <-- You|Smith &lt;-- You",
            "<b>bold</b> and <i>it</i>|<b>bold</b> and <i>it</i>",
            "<strong>x</strong><em>y</em>|<b>x</b><i>y</i>",
            "<p>para</p>|para",
            "<b>never closed|<b>never closed</b>",
            "closed </b> alone|closed  alone",
            "<b><i>crossed</b></i>|<b><i>crossed</i></b>",
            "<script>x</script>|&lt;script&gt;x&lt;/script&gt;",
            "<b onclick=\"x\">y</b>|&lt;b onclick=\"x\"&gt;y",
            "<blockquote expandable>why</blockquote>|<blockquote expandable>why</blockquote>",
            "<a href=\"https://sleeper.com\">Sleeper</a>|<a href=\"https://sleeper.com\">Sleeper</a>",
            "Team <b/> rest|Team  rest",
            "bad &#xD800; and &#0; entities|bad &amp;#xD800; and &amp;#0; entities",
            "<a>no link</a>|&lt;a&gt;no link"})
    void onlyBalancedSupportedTagsSurvive(String text, String html) {
        assertThat(TelegramHtml.normalize(text)).isEqualTo(html);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "**Bucky Irving** & <b>WR4</b> <3",
            "<b>open <i>nested",
            "## Head\n* bullet **bold**\n```\ncode\n```",
            "plain words only"})
    void normalisingTwiceChangesNothing(String text) {
        String once = TelegramHtml.normalize(text);

        assertThat(TelegramHtml.normalize(once)).isEqualTo(once);
    }

    @Test
    void theVisibleTextHasNoTagsAndDecodedEntities() {
        assertThat(TelegramHtml.visible("🟢 Carries: <b>4th</b> of 79 &amp; &lt;3 &#127944;"))
                .isEqualTo("🟢 Carries: 4th of 79 & <3 🏈");
    }

    @Test
    void theWordsAFactCheckReadsKeepSymbolsThatCarryFacts() {
        assertThat(TelegramHtml.words("It is 72° and windy; Kelce🔴out")).isEqualTo(
                "It is 72° and windy; Kelce out");
    }

    @Test
    void theWordsAFactCheckReadsHaveNoFormattingOrEmoji() {
        assertThat(TelegramHtml.words("🟢 Carries per game: **4th** of 79 (18.6)\n"
                + "⬆️ Start <b>Bucky Irving</b> 🔁"))
                .isEqualTo("Carries per game: 4th of 79 (18.6)\nStart Bucky Irving ");
    }
}
