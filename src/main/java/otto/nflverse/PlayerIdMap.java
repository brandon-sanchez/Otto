package otto.nflverse;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class PlayerIdMap {

    @JsonProperty("etag")
    private final String etag;
    @JsonProperty("checkedAt")
    private final Instant checkedAt;
    @JsonProperty("sleeperToGsis")
    private final Map<String, String> gsisBySleeper;
    @JsonProperty("sleeperToPfr")
    private final Map<String, String> pfrBySleeper;
    private final Map<String, String> sleeperByGsis;
    private final Map<String, String> sleeperByPfr;

    @JsonCreator
    public PlayerIdMap(@JsonProperty("etag") String etag,
            @JsonProperty("checkedAt") Instant checkedAt,
            @JsonProperty("sleeperToGsis") Map<String, String> gsisBySleeper,
            @JsonProperty("sleeperToPfr") Map<String, String> pfrBySleeper) {
        this.etag = etag;
        this.checkedAt = checkedAt;
        this.gsisBySleeper = copy(gsisBySleeper);
        this.pfrBySleeper = copy(pfrBySleeper);
        this.sleeperByGsis = invert(this.gsisBySleeper);
        this.sleeperByPfr = invert(this.pfrBySleeper);
    }

    public String etag() {
        return etag;
    }

    public Instant checkedAt() {
        return checkedAt;
    }

    public Optional<String> gsisFor(String sleeperId) {
        return Optional.ofNullable(gsisBySleeper.get(sleeperId));
    }

    public Optional<String> pfrFor(String sleeperId) {
        return Optional.ofNullable(pfrBySleeper.get(sleeperId));
    }

    public Optional<String> sleeperForGsis(String gsisId) {
        return Optional.ofNullable(sleeperByGsis.get(gsisId));
    }

    public Optional<String> sleeperForPfr(String pfrId) {
        return Optional.ofNullable(sleeperByPfr.get(pfrId));
    }

    public PlayerIdMap withCheckedAt(Instant newCheckedAt) {
        return new PlayerIdMap(etag, newCheckedAt, gsisBySleeper, pfrBySleeper);
    }

    private static Map<String, String> copy(Map<String, String> mapping) {
        return mapping == null ? Map.of() : Map.copyOf(mapping);
    }

    private static Map<String, String> invert(Map<String, String> mapping) {
        Map<String, String> inverted = new HashMap<>();
        mapping.forEach((sleeperId, externalId) -> inverted.put(externalId, sleeperId));
        return Map.copyOf(inverted);
    }
}
