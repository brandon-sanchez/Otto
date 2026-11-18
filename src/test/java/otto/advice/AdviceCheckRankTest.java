package otto.advice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import otto.directory.PlayerDirectoryStore;
import otto.storage.DocumentBackend;
import otto.storage.JsonStore;

import static org.assertj.core.api.Assertions.assertThat;

class AdviceCheckRankTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
    private static final String ITEM = "matchup-defense-pressure-rate-4881";

    private final AdviceCheck check = new AdviceCheck(new PlayerDirectoryStore(
            new JsonStore(new DocumentBackend() {
                @Override
                public Optional<byte[]> load(String name) {
                    return Optional.empty();
                }

                @Override
                public void store(String name, byte[] json) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void delete(String name) {
                    throw new UnsupportedOperationException();
                }
            })));

    private static EvidenceBundle bundle(int rank, int fromBottom) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("subject", "MIN defense");
        facts.put("stat", "pressure rate");
        facts.put(Ordinal.RANK_FACT, Ordinal.of(rank));
        facts.put(Ordinal.FROM_BOTTOM_FACT, Ordinal.fromBottom(fromBottom));
        facts.put("population", "of 32 defenses");
        facts.put("rankMeaning", "1st is the toughest matchup for quarterbacks");
        EvidenceItem item = new EvidenceItem(ITEM, "nflverse:pfr_advstats", Optional.empty(), NOW,
                "how his opponent's defense ranks against quarterbacks", facts);
        EvidenceItem draft = new EvidenceItem("profile-4881", "nflverse:players",
                Optional.empty(), NOW, "his profile",
                Map.of(Ordinal.DRAFT_FACT, Ordinal.draft(2017, 1, 10, "KC")));
        return new EvidenceBundle("Write lineup advice.", List.of(item, draft),
                List.of(new BundlePlayer("4881", "Patrick Mahomes", Optional.empty())),
                List.of(), true, Optional.empty(), 1000, Optional.empty(), List.of(), List.of());
    }

    private List<String> failures(EvidenceBundle bundle, String text) {
        return check.checkAnswer(bundle, new AdviceAnswer(text, Optional.empty(),
                List.of("4881"), List.of(new AdviceAnswer.Claim(text, List.of(ITEM))),
                List.of(), Optional.empty(), List.of()));
    }

    @Test
    void aRankOnlyTheRankMeaningMentionsIsRejected() {
        List<String> failures = failures(bundle(3, 30),
                "Patrick Mahomes faces MIN, 1st in pressure rate.");

        assertThat(failures)
                .anySatisfy(failure -> assertThat(failure).contains("The rank 1st"))
                .anySatisfy(failure -> assertThat(failure).contains("states the rank 1st"));
    }

    @Test
    void theRankFromTheBottomDoesNotSupportTheSameNumberCountedFromTheTop() {
        List<String> failures = failures(bundle(28, 5),
                "Patrick Mahomes faces MIN, 5th in pressure rate.");

        assertThat(failures).anySatisfy(failure -> assertThat(failure).contains("The rank 5th"));
    }

    @Test
    void theRankFromTheBottomIsAcceptedWrittenAsOnePhrase() {
        List<String> failures = failures(bundle(28, 5),
                "Patrick Mahomes faces MIN, 28th of 32 in pressure rate, 5th from the bottom.");

        assertThat(failures).isEmpty();
    }

    @Test
    void aDraftRoundIsNotARank() {
        EvidenceBundle bundle = bundle(3, 30);

        assertThat(check.checkAnswer(bundle, new AdviceAnswer(
                "Patrick Mahomes, a 1st-round pick, faces MIN, 3rd in pressure rate.",
                Optional.empty(), List.of("4881"), List.of(), List.of(), Optional.empty(),
                List.of()))).isEmpty();
        assertThat(check.checkAnswer(bundle, new AdviceAnswer(
                "Patrick Mahomes faces MIN, 12th in pressure rate.",
                Optional.empty(), List.of("4881"), List.of(), List.of(), Optional.empty(),
                List.of()))).anySatisfy(failure -> assertThat(failure).contains("The rank 12th"));
    }

    @Test
    void aDraftPickIsBackedOnlyByTheDraftFact() {
        EvidenceBundle bundle = bundle(3, 30);

        assertThat(check.checkAnswer(bundle, new AdviceAnswer(
                "Patrick Mahomes, the 10th overall pick, faces MIN, 3rd in pressure rate.",
                Optional.empty(), List.of("4881"), List.of(), List.of(), Optional.empty(),
                List.of()))).isEmpty();
        assertThat(check.checkAnswer(bundle, new AdviceAnswer(
                "Patrick Mahomes, the 3rd overall pick, faces MIN, 3rd in pressure rate.",
                Optional.empty(), List.of("4881"), List.of(), List.of(), Optional.empty(),
                List.of()))).isNotEmpty();
    }

    private List<String> messageFailures(String text) {
        return check.checkAnswer(bundle(3, 30), new AdviceAnswer(text, Optional.empty(),
                List.of("4881"), List.of(), List.of(), Optional.empty(), List.of()));
    }

    @Test
    void theOverallPickIsAcceptedAsAPlainPickNumber() {
        assertThat(messageFailures(
                "Patrick Mahomes, pick 10 in 2017, faces MIN, 3rd in pressure rate."))
                .isEmpty();
        assertThat(messageFailures(
                "Patrick Mahomes, taken 10th overall, faces MIN, 3rd in pressure rate."))
                .isEmpty();
        assertThat(messageFailures(
                "Patrick Mahomes, the 10th pick, faces MIN, 3rd in pressure rate."))
                .isEmpty();
    }

    @Test
    void aWrongPlainPickNumberIsRejected() {
        assertThat(messageFailures(
                "Patrick Mahomes, pick 12 in 2017, faces MIN, 3rd in pressure rate."))
                .anySatisfy(failure -> assertThat(failure).contains("number 12"));
    }

    private static EvidenceItem defenseRank(String playerId, int rank, int fromBottom) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("subject", "opponent defense");
        facts.put("stat", "pressure rate");
        facts.put(Ordinal.RANK_FACT, Ordinal.of(rank));
        facts.put(Ordinal.FROM_BOTTOM_FACT, Ordinal.fromBottom(fromBottom));
        return new EvidenceItem("matchup-defense-pressure-rate-" + playerId,
                "nflverse:pfr_advstats", Optional.empty(), NOW,
                "how his opponent's defense ranks against quarterbacks", facts);
    }

    private static EvidenceBundle twoQuarterbacks() {
        return new EvidenceBundle("Write lineup advice.",
                List.of(defenseRank("4881", 30, 3), defenseRank("6904", 2, 31)),
                List.of(new BundlePlayer("4881", "Patrick Mahomes", Optional.empty()),
                        new BundlePlayer("6904", "Jalen Hurts", Optional.empty())),
                List.of(), true, Optional.empty(), 1000, Optional.empty(), List.of(), List.of());
    }

    @Test
    void anotherPlayersRankIsRejectedInASentenceAboutHim() {
        List<String> failures = check.checkAnswer(twoQuarterbacks(), new AdviceAnswer(
                "Patrick Mahomes faces the 2nd pass rush. Jalen Hurts is fine.",
                Optional.empty(), List.of("4881"), List.of(), List.of(), Optional.empty(),
                List.of()));

        assertThat(failures).anySatisfy(failure -> assertThat(failure)
                .contains("2nd").contains("about Patrick Mahomes"));
    }

    @Test
    void aClaimCitingAnotherPlayersMatchupItemIsRejected() {
        List<String> failures = check.checkAnswer(twoQuarterbacks(), new AdviceAnswer(
                "Patrick Mahomes faces the 2nd pass rush. Jalen Hurts is fine.",
                Optional.empty(), List.of("4881"),
                List.of(new AdviceAnswer.Claim("Patrick Mahomes faces the 2nd pass rush.",
                        List.of("matchup-defense-pressure-rate-6904"))),
                List.of(), Optional.empty(), List.of()));

        assertThat(failures).anySatisfy(failure -> assertThat(failure)
                        .contains("matchup-defense-pressure-rate-6904")
                        .contains("Jalen Hurts"));
    }

    @Test
    void eachPlayersOwnRankIsAccepted() {
        List<String> failures = check.checkAnswer(twoQuarterbacks(), new AdviceAnswer(
                "Patrick Mahomes faces the 30th pass rush. Jalen Hurts faces the 2nd.",
                Optional.empty(), List.of("4881", "6904"),
                List.of(new AdviceAnswer.Claim("Jalen Hurts faces the 2nd pass rush.",
                        List.of("matchup-defense-pressure-rate-6904"))),
                List.of(), Optional.empty(), List.of()));

        assertThat(failures).isEmpty();
    }

    @Test
    void aStandingWrittenWithItsPopulationSupportsTheRank() {
        EvidenceItem needs = new EvidenceItem("needs", "otto:roster-needs", Optional.empty(), NOW,
                "where the user's roster needs help",
                Map.of("weaknessThisWeek", "RB: 9.5 points projected, 9th of 12, league median 12.0"));
        EvidenceBundle bundle = new EvidenceBundle("Write the waiver board.", List.of(needs),
                List.of(), List.of(), true, Optional.empty(), 1000, Optional.empty(), List.of(),
                List.of());

        assertThat(check.checkAnswer(bundle, new AdviceAnswer(
                "Your RB room is 9th of 12 this week.", Optional.empty(), List.of(), List.of(),
                List.of(), Optional.empty(), List.of()))).isEmpty();
        assertThat(check.checkAnswer(bundle, new AdviceAnswer(
                "Your RB room is 8th of 12 this week.", Optional.empty(), List.of(), List.of(),
                List.of(), Optional.empty(), List.of())))
                .anySatisfy(failure -> assertThat(failure).contains("rank 8th"));
    }

    @Test
    void aWrongRankWrittenInWordsIsRejected() {
        assertThat(failures(bundle(3, 30), "Patrick Mahomes faces MIN, the second-toughest "
                + "pass rush.")).anySatisfy(failure -> assertThat(failure)
                        .contains("rank second"));
        assertThat(failures(bundle(3, 30), "Patrick Mahomes faces MIN, the third-toughest "
                + "pass rush.")).isEmpty();
    }

    @Test
    void aVagueRankIsSentBackToBeWrittenAsTheEvidenceWritesIt() {
        assertThat(failures(bundle(3, 30), "Patrick Mahomes faces MIN, a top-3 pass rush."))
                .anySatisfy(failure -> assertThat(failure).contains("\"top-3\"")
                        .contains("3rd"));
    }

    @Test
    void formattingAndColouredDotsNeverFailACheck() {
        List<String> failures = failures(bundle(3, 30),
                "🔴 **Patrick Mahomes** faces MIN, <b>3rd</b> of 32 in pressure rate (tough).");

        assertThat(failures).isEmpty();
    }

    @Test
    void theLengthLimitCountsOnlyTheVisibleText() {
        String text = "<b>Patrick Mahomes</b> faces MIN, <b>3rd</b> in pressure rate."
                + " <b>He</b> is fine.".repeat(70);

        assertThat(text.length()).isGreaterThan(1000);
        assertThat(failures(bundle(3, 30), text)).isEmpty();
    }
}
