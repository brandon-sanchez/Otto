package otto.records;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * The retained account of one recommendation run: what Otto knew,
 * calculated and sent, kept so the advice can be reconstructed and
 * judged later.
 *
 * <p>A record is read back years after it was written, by code that
 * has moved on. The envelope and the parts this package owns are
 * typed; snapshots of other domain objects - the league and its rules,
 * the roster, the preferences, the comparisons and the model's request
 * and response - are captured as JSON at write time, so a field renamed
 * in those types later never makes an old record unreadable.
 *
 * <p>There is no field for conversation history. Unrelated chat is
 * kept out of a record by its shape, not by a filter that could miss
 * something.
 *
 * @param schemaVersion the shape this record was written in,
 *        {@link #SCHEMA_VERSION} for every record written now
 * @param runId derived from what identifies the run (see {@link RunIds#stable}),
 *        so every Check that repeats the run finds this record
 * @param league the league's identity and rules as Otto read them
 * @param preferences the preferences active for this run
 * @param comparisons what Otto computed between the candidates
 * @param exchanges every model call the run made, in order
 */
public record DecisionRecord(
        int schemaVersion,
        String runId,
        RunKind kind,
        Instant startedAt,
        String userId,
        String leagueId,
        JsonNode league,
        RosterContext roster,
        JsonNode preferences,
        List<Candidate> candidates,
        JsonNode comparisons,
        List<Evidence> evidence,
        Versions versions,
        List<ModelExchange> exchanges,
        Outcome outcome) {

    public static final int SCHEMA_VERSION = 1;

    public DecisionRecord {
        candidates = List.copyOf(candidates);
        evidence = List.copyOf(evidence);
        exchanges = List.copyOf(exchanges);
        if (evidence.stream().map(Evidence::id).distinct().count() != evidence.size()) {
            throw new IllegalArgumentException("Evidence ids repeat within run " + runId);
        }
    }

    /**
     * Starts the record of one run. A part left unset stays empty: no
     * roster, preferences or comparisons, no candidates, evidence or
     * model exchanges, and {@link Versions#withoutModel} over no data.
     *
     * @param league the league document as Otto read it
     */
    public static Builder forRun(String runId, RunKind kind, Instant startedAt, String userId,
            String leagueId, JsonNode league) {
        return new Builder(runId, kind, startedAt, userId, leagueId, league);
    }

    /** Assembles a {@link DecisionRecord}; {@link #outcome} finishes it. */
    public static final class Builder {

        private final String runId;
        private final RunKind kind;
        private final Instant startedAt;
        private final String userId;
        private final String leagueId;
        private final JsonNode league;
        private RosterContext roster = new RosterContext(NullNode.getInstance(), Optional.empty());
        private JsonNode preferences = NullNode.getInstance();
        private List<Candidate> candidates = List.of();
        private JsonNode comparisons = NullNode.getInstance();
        private List<Evidence> evidence = List.of();
        private Versions versions = Versions.withoutModel(Versions.NONE);
        private List<ModelExchange> exchanges = List.of();

        private Builder(String runId, RunKind kind, Instant startedAt, String userId,
                String leagueId, JsonNode league) {
            this.runId = runId;
            this.kind = kind;
            this.startedAt = startedAt;
            this.userId = userId;
            this.leagueId = leagueId;
            this.league = league;
        }

        /** Sets the user's roster as the run saw it. */
        public Builder roster(RosterContext value) {
            roster = value;
            return this;
        }

        /** Sets the preferences active for the run. */
        public Builder preferences(JsonNode value) {
            preferences = value;
            return this;
        }

        /** Sets the players the run considered. */
        public Builder candidates(List<Candidate> value) {
            candidates = value;
            return this;
        }

        /** Sets what the run computed between the candidates. */
        public Builder comparisons(JsonNode value) {
            comparisons = value;
            return this;
        }

        /** Sets the sources the run looked at. */
        public Builder evidence(List<Evidence> value) {
            evidence = value;
            return this;
        }

        /** Sets the versions of everything that could have changed the answer. */
        public Builder versions(Versions value) {
            versions = value;
            return this;
        }

        /** Sets every model call the run made, in order. */
        public Builder exchanges(List<ModelExchange> value) {
            exchanges = value;
            return this;
        }

        /** Returns the record of the run, ended with this outcome. */
        public DecisionRecord outcome(Outcome outcome) {
            return new DecisionRecord(SCHEMA_VERSION, runId, kind, startedAt, userId, leagueId,
                    league, roster, preferences, candidates, comparisons, evidence, versions,
                    exchanges, outcome);
        }
    }

    DecisionRecord withEvidence(List<Evidence> evidence) {
        return new DecisionRecord(schemaVersion, runId, kind, startedAt, userId, leagueId,
                league, roster, preferences, candidates, comparisons, evidence, versions,
                exchanges, outcome);
    }

    /** Returns this record with another outcome in place of its own. */
    public DecisionRecord withOutcome(Outcome replaced) {
        return new DecisionRecord(schemaVersion, runId, kind, startedAt, userId, leagueId,
                league, roster, preferences, candidates, comparisons, evidence, versions,
                exchanges, replaced);
    }
}
