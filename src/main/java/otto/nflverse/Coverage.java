package otto.nflverse;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import otto.storage.OttoJson;

/**
 * Which units a feed document holds - a game, or a team's week - and what
 * has happened to each since Otto first saw it.
 *
 * nflverse republishes a whole season file under one timestamp, often
 * daily, so a moved timestamp says nothing about which week changed. A
 * unit's fingerprint is taken over the rows Otto keeps for it, after the
 * reader has trimmed and translated them, so a correction is a change
 * to numbers Otto reads and nothing else: not a reordered file, not a
 * column Otto drops, and not a re-pull that only moved a stamp.
 *
 * It lives inside the feed's document rather than beside it, so the rows
 * and what is known about them are written in one store write and can
 * never disagree.
 *
 * @param contentVersion the spec's {@link FeedSpec#contentVersion()} the
 *        fingerprints were taken under; fingerprints from another
 *        version measure different content and are never compared
 */
public record Coverage(int contentVersion, List<UnitRecord> units) {

    /**
     * One slot in a feed: a game id or a team code, in a week. Week 0
     * for a feed whose file has no week, such as the depth charts; its
     * stamp places it instead.
     */
    public record Unit(int week, String key) {

        static Unit undated(String key) {
            return new Unit(0, key);
        }
    }

    /**
     * @param fingerprint order-independent hash of the unit's content
     * @param publishedAt the feed's own stamp for the unit when it carries
     *        one, the newest over its rows; otherwise the asset timestamp
     *        of the release this copy came from
     * @param firstSeenAt the asset timestamp of the release that first held the unit
     * @param changedAt the asset timestamp of the last release whose
     *        content for the unit differed; firstSeenAt until then
     * @param corrections how many releases changed the unit's content
     *        after Otto first saw it
     */
    public record UnitRecord(Unit unit, long fingerprint, Instant publishedAt,
            Instant firstSeenAt, Instant changedAt, int corrections) {
    }

    private static final Comparator<Unit> UNIT_ORDER =
            Comparator.comparingInt(Unit::week).thenComparing(Unit::key);

    /** Map keys sorted, so equal content always serialises to equal bytes. */
    private static final ObjectWriter CANONICAL = OttoJson.MAPPER.copy()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .writer();

    /** The coverage a freshly read file describes, before any history. */
    static <R> Coverage of(List<R> rows, FeedSpec<R, ?> spec, Instant assetUpdatedAt) {
        Map<Unit, Accumulator> byUnit = new TreeMap<>(UNIT_ORDER);
        rows.forEach(row -> byUnit.computeIfAbsent(spec.unit(row), unit -> new Accumulator())
                .add(fingerprint(spec.content(row)), spec.stamp(row)));
        List<UnitRecord> units = byUnit.entrySet().stream()
                .map(entry -> new UnitRecord(entry.getKey(), entry.getValue().fingerprint,
                        Objects.requireNonNullElse(entry.getValue().newestStamp, assetUpdatedAt),
                        assetUpdatedAt, assetUpdatedAt, 0))
                .toList();
        return new Coverage(spec.contentVersion(), units);
    }

    /**
     * This file's coverage, with each unit's history carried over from the
     * stored copy. A unit whose content moved counts one more correction;
     * a unit that only moved its stamp keeps its history and takes the new
     * stamp. A unit the file no longer holds is dropped, so it reads as
     * missing rather than as still there.
     *
     * When the previous fingerprints were taken under another content
     * version they are replaced without being compared: the content they
     * measure changed shape, not value. Merging the same file twice
     * yields the same records.
     *
     * @param previous the stored copy's coverage for the same season, or
     *        null when there is none
     */
    Coverage carriedForward(Coverage previous, Instant assetUpdatedAt) {
        if (previous == null) {
            return this;
        }
        boolean comparable = previous.contentVersion() == contentVersion;
        Map<Unit, UnitRecord> before = previous.units().stream()
                .collect(Collectors.toMap(UnitRecord::unit, Function.identity()));
        List<UnitRecord> merged = units.stream().map(fresh -> {
            UnitRecord old = before.get(fresh.unit());
            if (old == null) {
                return fresh;
            }
            boolean corrected = comparable && old.fingerprint() != fresh.fingerprint();
            return new UnitRecord(fresh.unit(), fresh.fingerprint(), fresh.publishedAt(),
                    old.firstSeenAt(),
                    corrected ? assetUpdatedAt : old.changedAt(),
                    corrected ? old.corrections() + 1 : old.corrections());
        }).toList();
        return new Coverage(contentVersion, merged);
    }

    /**
     * A row's share of its unit's fingerprint. The shares are summed, so
     * the order rows arrive in cannot move the total while a changed,
     * added or removed row always does.
     */
    private static long fingerprint(Object content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(CANONICAL.writeValueAsBytes(content));
            return ByteBuffer.wrap(digest).getLong();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot fingerprint " + content, e);
        }
    }

    private static final class Accumulator {

        private long fingerprint;
        private Instant newestStamp;

        void add(long rowFingerprint, Optional<Instant> stamp) {
            fingerprint += rowFingerprint;
            stamp.filter(instant -> newestStamp == null || instant.isAfter(newestStamp))
                    .ifPresent(instant -> newestStamp = instant);
        }
    }
}
