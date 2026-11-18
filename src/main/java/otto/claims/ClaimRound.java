package otto.claims;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import otto.records.RunIds;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;

/**
 * The league's next claim round as one Check sees it: the claim
 * deadline, when the round's waiver board is due and the latest it goes
 * out, the key its Alert is sent under, and the run ids its board and
 * baselines are recorded under. A board that fails its checks is tried
 * again, up to {@link #BOARD_TRIES} times in all, and each try has its
 * own run id. Both waiver boards, the baselines and
 * the role-slip advice held for a board read the round here, so none of
 * them depends on another.
 *
 * @param leagueId the Sleeper league the round belongs to
 * @param deadline when the league's claims run, in the zone Sleeper runs them in
 * @param now when the Check runs
 */
public record ClaimRound(String leagueId, ZonedDateTime deadline, Instant now) {

    /** The user's zone. Pinned here, not read from the clock: the Check runs in UTC. */
    private static final ZoneId BOARD_ZONE = ZoneId.of("America/Los_Angeles");

    private static final LocalTime BOARD_TIME = LocalTime.of(18, 0);

    /**
     * How long before the claim deadline the evidence-based board goes
     * out even though the latest week's data is late.
     */
    public static final Duration FALLBACK_BUFFER = Duration.ofHours(3);

    /**
     * How many times a round's board is written when it keeps failing
     * its checks. Each try is up to two model calls over the whole
     * evidence bundle, so the cap bounds what one round can cost.
     */
    public static final int BOARD_TRIES = 3;

    /** How long after one try was due the next one is. */
    public static final Duration BOARD_TRY_SPACING = Duration.ofHours(1);

    private static final String TRY_SUFFIX = "-try-";

    private static final String BOARD_RUN_PREFIX = "waiver-board-";
    private static final String BASELINES_RUN_PREFIX = "waiver-baselines-";

    /**
     * Returns the league's next claim round, empty when its claim day
     * is unknown.
     */
    public static Optional<ClaimRound> next(SleeperAdapter.League league, Instant now) {
        return league.rules().nextClaimDeadline(now)
                .map(deadline -> new ClaimRound(league.leagueId(), deadline, now));
    }

    /**
     * Returns when the round's waiver board is due: 18:00
     * America/Los_Angeles on the calendar day before the claim deadline,
     * read in the zone Sleeper runs claims in.
     */
    public ZonedDateTime due() {
        return deadline.withZoneSameInstant(LeagueRules.CLAIM_ZONE).toLocalDate().minusDays(1)
                .atTime(BOARD_TIME)
                .atZone(BOARD_ZONE);
    }

    /** True from the evening the board is due until the claim deadline. */
    public boolean open() {
        return !now.isBefore(due().toInstant());
    }

    /**
     * Returns the latest the round's board goes out: a board waiting for
     * late data is built at {@link #FALLBACK_BUFFER} before the deadline
     * at the latest.
     */
    public Instant latest() {
        return deadline.minus(FALLBACK_BUFFER).toInstant();
    }

    /** True once the Check is inside {@link #FALLBACK_BUFFER} of the claim deadline. */
    public boolean inFallbackBuffer() {
        return !now.isBefore(latest());
    }

    /**
     * True once a try of the round's board is due: the first as soon as
     * the round is open, each later one {@link #BOARD_TRY_SPACING} after
     * the one before it was due.
     *
     * @param boardTry which try, from 1 to {@link #BOARD_TRIES}
     */
    public boolean boardTryDue(int boardTry) {
        return !now.isBefore(due().toInstant()
                .plus(BOARD_TRY_SPACING.multipliedBy(boardTry - 1L)));
    }

    /**
     * Returns the Alert key the round's waiver board, or the notice
     * that none is coming, is sent under.
     */
    public String alertKey() {
        return "alert:waiver:" + due().toLocalDate();
    }

    /**
     * Returns the run id of the round's evidence-based board, derived
     * from the league and the claim deadline so every Check in the
     * window finds the same record.
     */
    public String boardRunId() {
        return RunIds.stable(BOARD_RUN_PREFIX, leagueId, deadline.toInstant().toString());
    }

    /**
     * Returns the run id one try of the round's board is recorded
     * under: {@link #boardRunId()} for the first.
     *
     * @param boardTry which try, from 1 to {@link #BOARD_TRIES}
     */
    public String boardRunId(int boardTry) {
        return boardRunIds(boardRunId()).get(boardTry - 1);
    }

    /**
     * Returns the run id of every try of a round's board, first try
     * first, from the run id of its first try.
     */
    public static List<String> boardRunIds(String firstTryRunId) {
        return IntStream.rangeClosed(1, BOARD_TRIES)
                .mapToObj(boardTry -> boardTry == 1 ? firstTryRunId
                        : firstTryRunId + TRY_SUFFIX + boardTry)
                .toList();
    }

    /** Returns the run id of the round's baselines, derived as {@link #boardRunId} is. */
    public String baselinesRunId() {
        return RunIds.stable(BASELINES_RUN_PREFIX, leagueId, deadline.toInstant().toString());
    }
}
