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
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;

import otto.storage.OttoJson;

/**
 * Which units a feed document holds - a game, a team's week, or a team's
 * current chart - and what has happened to each since Otto first saw it.
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

    /** One slot in a feed. Which kind a feed uses is its {@link Grain}. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Unit.Game.class, name = "game"),
            @JsonSubTypes.Type(value = Unit.TeamWeek.class, name = "teamWeek"),
            @JsonSubTypes.Type(value = Unit.Team.class, name = "team")})
    public sealed interface Unit {

        record Game(int week, String gameId) implements Unit {
        }

        record TeamWeek(int week, String team) implements Unit {
        }

        /**
         * A team's current snapshot in a file with no week, such as its
         * depth chart. The record's {@code publishedAt} is the snapshot's
         * own date and names which snapshot this is: a newer one is new
         * data, and only the same date republished can be a correction.
         */
        record Team(String team) implements Unit {
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
     * @param held false once a release dropped the unit. The record stays
     *        as it last was, so a unit that returns is measured against
     *        what Otto saw before rather than read as new.
     */
    public record UnitRecord(Unit unit, long fingerprint, Instant publishedAt,
            Instant firstSeenAt, Instant changedAt, int corrections, boolean held) {
    }

    private static final Comparator<Unit> UNIT_ORDER = Comparator
            .comparingInt((Unit unit) -> switch (unit) {
                case Unit.Game game -> game.week();
                case Unit.TeamWeek teamWeek -> teamWeek.week();
                case Unit.Team _ -> 0;
            })
            .thenComparing(unit -> switch (unit) {
                case Unit.Game game -> game.gameId();
                case Unit.TeamWeek teamWeek -> teamWeek.team();
                case Unit.Team team -> team.team();
            });

    /** Map keys sorted, so equal content always serialises to equal bytes. */
    private static final ObjectWriter CANONICAL = OttoJson.MAPPER.copy()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .writer();

    /** The coverage a freshly read file describes, before any history. */
    static <R> Coverage of(List<R> rows, FeedSpec<R, ?> spec, Instant assetUpdatedAt) {
        MessageDigest digest = sha256();
        Map<Unit, Accumulator> byUnit = new TreeMap<>(UNIT_ORDER);
        rows.forEach(row -> byUnit.computeIfAbsent(spec.grain().unit(row), unit -> new Accumulator())
                .add(fingerprint(digest, spec.content(row)), spec.stamp(row)));
        List<UnitRecord> units = byUnit.entrySet().stream()
                .map(entry -> new UnitRecord(entry.getKey(), entry.getValue().fingerprint,
                        Objects.requireNonNullElse(entry.getValue().newestStamp, assetUpdatedAt),
                        assetUpdatedAt, assetUpdatedAt, 0, true))
                .toList();
        return new Coverage(spec.contentVersion(), units);
    }

    /**
     * This file's coverage, with each unit's history carried over from the
     * stored copy. A unit whose content moved counts one more correction;
     * a unit that only moved its stamp keeps its history and takes the new
     * stamp. A {@link Unit.Team} whose stamp moved is a newer snapshot, so
     * it starts afresh instead. A unit the file no longer holds stays as a
     * tombstone.
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
        Map<Unit, UnitRecord> fresh = units.stream()
                .collect(Collectors.toMap(UnitRecord::unit, Function.identity()));
        Stream<UnitRecord> merged = units.stream().map(record -> {
            UnitRecord old = before.get(record.unit());
            if (old == null || newerSnapshot(old, record)) {
                return record;
            }
            boolean corrected = comparable && old.fingerprint() != record.fingerprint();
            return new UnitRecord(record.unit(), record.fingerprint(), record.publishedAt(),
                    old.firstSeenAt(),
                    corrected ? assetUpdatedAt : old.changedAt(),
                    corrected ? old.corrections() + 1 : old.corrections(),
                    true);
        });
        Stream<UnitRecord> dropped = previous.units().stream()
                .filter(old -> !fresh.containsKey(old.unit()))
                .map(old -> new UnitRecord(old.unit(), old.fingerprint(), old.publishedAt(),
                        old.firstSeenAt(), old.changedAt(), old.corrections(), false));
        return new Coverage(contentVersion, Stream.concat(merged, dropped)
                .sorted(Comparator.comparing(UnitRecord::unit, UNIT_ORDER))
                .toList());
    }

    private static boolean newerSnapshot(UnitRecord old, UnitRecord fresh) {
        return fresh.unit() instanceof Unit.Team
                && !old.publishedAt().equals(fresh.publishedAt());
    }

    List<UnitRecord> held() {
        return units.stream().filter(UnitRecord::held).toList();
    }

    /**
     * A row's share of its unit's fingerprint. The shares are summed, so
     * the order rows arrive in cannot move the total while a changed,
     * added or removed row always does.
     */
    static long fingerprint(MessageDigest digest, Object content) {
        try {
            return ByteBuffer.wrap(digest.digest(CANONICAL.writeValueAsBytes(content))).getLong();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot fingerprint " + content, e);
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
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
