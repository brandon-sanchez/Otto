package otto.advice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.storage.DocumentBackend;
import otto.storage.JsonStore;

import static org.assertj.core.api.Assertions.assertThat;

class AdviceCheckSlipsTest {

    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

    private static final DocumentBackend NOTHING_STORED = new DocumentBackend() {
        @Override
        public Optional<byte[]> load(String name) {
            return Optional.empty();
        }

        @Override
        public void store(String name, byte[] json) {
        }
    };

    private final AdviceCheck check = new AdviceCheck(
            new PlayerDirectoryStore(new JsonStore(NOTHING_STORED)) {
                @Override
                public Optional<PlayerDirectory> read() {
                    return Optional.of(new PlayerDirectory("etag", NOW, Map.of(
                            "1", directoryPlayer("1", "Elic Ayomanor"),
                            "2", directoryPlayer("2", "Carnell Tate"),
                            "3", directoryPlayer("3", "Brock Bowers"),
                            "4", directoryPlayer("4", "Parker Washington")), Map.of()));
                }
            });

    private static DirectoryPlayer directoryPlayer(String id, String name) {
        return new DirectoryPlayer(id, name, "WR", "TEN", PlayerHealth.ACTIVE, Optional.empty());
    }

    private static EvidenceItem item(String id, Map<String, String> facts) {
        return new EvidenceItem(id, "test", Optional.empty(), NOW, "test evidence", facts);
    }

    private static EvidenceBundle bundle(BundlePlayer... players) {
        return new EvidenceBundle("Write lineup advice.", List.of(
                item("usage-1", Map.of("week 10", "95% of offensive snaps; 10 targets")),
                item("news-1-1", Map.of("report", "With Carnell Tate (shoulder) sidelined, "
                        + "Elic Ayomanor led the team. Tate had 4 catches the week before.")),
                item("matchup-opponent-1", Map.of("opponent", "WAS", "week", "11"))),
                List.of(players), List.of(), true, Optional.empty(), 1000, Optional.empty(),
                List.of(), List.of());
    }

    private static final BundlePlayer AYOMANOR =
            new BundlePlayer("1", "Elic Ayomanor", Optional.empty());

    private AdviceCheck.Verdict<AdviceAnswer> verdict(EvidenceBundle bundle, String message,
            String players, String claims) {
        return check.check(bundle, """
                {"message": "%s", "move": null, "players": [%s], "claims": [%s],
                 "comparisons": []}""".formatted(message, players, claims));
    }

    private static String claim(String text, String evidence) {
        return "{\"text\": \"%s\", \"evidence\": [\"%s\"]}".formatted(text, evidence);
    }

    @Test
    void aPlayerWrittenByFullNameWhereAnIdBelongsIsReadAsThatPlayer() {
        AdviceCheck.Verdict<AdviceAnswer> verdict = verdict(bundle(AYOMANOR),
                "Keep Elic Ayomanor in.", "\"Elic Ayomanor\"", "");

        assertThat(verdict.failures()).isEmpty();
        assertThat(((AdviceCheck.Verdict.Passed<AdviceAnswer>) verdict).answer().players())
                .containsExactly("1");
    }

    @Test
    void aNameTwoPlayersShareOrNobodyHasStillFails() {
        EvidenceBundle twins = bundle(AYOMANOR,
                new BundlePlayer("9", "Elic Ayomanor", Optional.empty()));

        assertThat(verdict(twins, "Keep Elic Ayomanor in.", "\"Elic Ayomanor\"", "").failures())
                .contains("Player id Elic Ayomanor is not in the evidence.");
        assertThat(verdict(bundle(AYOMANOR), "Keep Elic Ayomanor in.", "\"Elic Ayo\"", "")
                .failures()).contains("Player id Elic Ayo is not in the evidence.");
    }

    @Test
    void aListOfEvidenceIdsIsTakenOutOfTheMessageAndNeverReadAsANumber() {
        AdviceCheck.Verdict<AdviceAnswer> verdict = verdict(bundle(AYOMANOR),
                "Keep Elic Ayomanor in, he drew 10 targets (usage-1). He played 95% of the "
                        + "snaps. [usage-1, news-1-1]", "\"1\"", "");

        assertThat(verdict.failures()).isEmpty();
        assertThat(((AdviceCheck.Verdict.Passed<AdviceAnswer>) verdict).answer().message())
                .isEqualTo("Keep Elic Ayomanor in, he drew 10 targets. He played 95% of the "
                        + "snaps.");
    }

    @Test
    void anEvidenceIdLeftInASentenceFailsAsAnIdNotAsANumber() {
        assertThat(verdict(bundle(AYOMANOR), "Keep Elic Ayomanor in, per usage-1 he drew 10 "
                + "targets.", "\"1\"", "").failures())
                .singleElement().asString().contains("writes out evidence id usage-1");
    }

    @Test
    void aWeekTheEvidenceCoversIsNotAStatedNumberAndAnyOtherWeekIs() {
        assertThat(verdict(bundle(AYOMANOR), "Keep Elic Ayomanor in after Week 10, with "
                + "Washington next in week 11.", "\"1\"",
                claim("Elic Ayomanor drew 10 targets in Week 10.", "usage-1")).failures())
                .isEmpty();
        assertThat(verdict(bundle(AYOMANOR), "Keep Elic Ayomanor in after Week 7.", "\"1\"", "")
                .failures()).containsExactly("The number 7 is not in the evidence.");
    }

    @Test
    void aPersonNamedInCitedNewsMayBeNamedAndNumbersAboutHimStayStrict() {
        String tateOut = "Keep Elic Ayomanor in. Carnell Tate is sidelined.";

        assertThat(verdict(bundle(AYOMANOR), tateOut, "\"1\"",
                claim("Carnell Tate is sidelined.", "news-1-1")).failures()).isEmpty();
        assertThat(verdict(bundle(AYOMANOR), tateOut + " [news-1-1]", "\"1\"", "").failures())
                .isEmpty();
        assertThat(verdict(bundle(AYOMANOR), tateOut, "\"1\"", "").failures())
                .containsExactly("The message names Carnell Tate, who is not in the evidence.");
        assertThat(verdict(bundle(AYOMANOR), "Keep Elic Ayomanor in. Brock Bowers is out. "
                + "[news-1-1]", "\"1\"", "").failures())
                .containsExactly("The message names Brock Bowers, who is not in the evidence.");
        assertThat(verdict(bundle(AYOMANOR), "Keep Elic Ayomanor in. Carnell Tate drew 10 "
                + "targets. [news-1-1]", "\"1\"", "").failures())
                .containsExactly("The number 10 is not in the evidence about Carnell Tate.");
    }

    @Test
    void aSentenceThatOpensWithHeIsAboutThePlayerNamedLast() {
        assertThat(verdict(bundle(AYOMANOR), "Keep Elic Ayomanor in. He drew 10 targets with "
                + "Carnell Tate sidelined. [news-1-1]", "\"1\"", "").failures()).isEmpty();
    }

    @Test
    void aTeamCalledByItsPlaceIsNotARosteredPlayersSurname() {
        EvidenceBundle bundle = bundle(AYOMANOR,
                new BundlePlayer("4", "Parker Washington", Optional.empty()));

        assertThat(verdict(bundle, "Keep Elic Ayomanor in against Washington.", "\"1\"", "")
                .failures()).isEmpty();
        assertThat(verdict(bundle, "Keep Elic Ayomanor in. Washington drew 10 targets.",
                "\"1\"", "").failures())
                .contains("Introduce Parker Washington by full name before using his surname "
                        + "alone.");
    }

    @Test
    void aFigureInAFactThatOnlyEndsInWeekIsNotAWeekTheEvidenceCovers() {
        BundlePlayer other = new BundlePlayer("9", "Khalil Shakir", Optional.empty());
        EvidenceBundle bundle = new EvidenceBundle("Write lineup advice.", List.of(
                item("usage-9", Map.of("targetsPerWeek", "7"))), List.of(AYOMANOR, other),
                List.of(), true, Optional.empty(), 1000, Optional.empty(), List.of(), List.of());

        assertThat(verdict(bundle, "Keep Elic Ayomanor in after Week 7.", "\"1\"", "")
                .failures())
                .containsExactly("The number 7 is not in the evidence about Elic Ayomanor.");
    }
}
