package otto.trade;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import otto.league.LeagueWeek;
import otto.nflverse.NflverseStore;
import otto.nflverse.Schedule;

/** Reads where trading in a league stands right now, from the stored schedule and the clock. */
@Component
public class TradeDeadlines {

    private static final Logger log = LoggerFactory.getLogger(TradeDeadlines.class);

    private final NflverseStore nflverse;
    private final Clock clock;

    TradeDeadlines(NflverseStore nflverse, Clock clock) {
        this.nflverse = nflverse;
        this.clock = clock;
    }

    /**
     * Returns where trading stands for the league, as
     * {@link TradeDeadline#assess} reads it. A stored schedule that cannot
     * be read counts as no schedule, so the answer falls back to the
     * Tuesday rule instead of failing.
     *
     * <p>Side effects: reads the stored nflverse schedule, in the deadline
     * week only.
     *
     * @return empty when nothing limits trading this week
     */
    public Optional<TradeDeadline> assess(LeagueWeek league) {
        return TradeDeadline.assess(league.league(), league.week().seasonWeek(),
                this::schedule, clock.instant());
    }

    private Optional<Schedule> schedule() {
        try {
            return nflverse.schedule();
        } catch (RuntimeException unreadable) {
            log.warn("The stored schedule could not be read; the trade deadline falls back "
                    + "to Tuesday 08:00 UTC", unreadable);
            return Optional.empty();
        }
    }
}
