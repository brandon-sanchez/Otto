package otto.waivers;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.alerts.AlertDeliveryOutbox;
import otto.alerts.AlertPhraser;
import otto.alerts.Confidence;
import otto.alerts.MuteStore;
import otto.alerts.Recommendation;
import otto.ask.LeagueWeek;
import otto.events.Event;
import otto.events.EventType;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.sleeper.LeagueRules;
import otto.sleeper.SourceResult;

/**
 * The waiver Alert: the top five free agents, their reasons, their
 * role tags and a FAAB range each, in the user's chat at 18:00
 * America/Los_Angeles the evening before the league's own claim
 * deadline. On Sleeper's default schedule that is Tuesday evening.
 *
 * <p>It runs no scheduler of its own. Every Check asks the same
 * question - has the evening before the next claim deadline begun, and
 * does the Event Log already hold that evening's Alert? - so the
 * 1-minute loop plus one Event Log key is the whole timer. The instant
 * is derived by putting 18:00 on the evening's own date in the
 * America/Los_Angeles zone, so the summer Alert lands at 01:00Z and the
 * winter one at 02:00Z without anybody writing an offset down.
 *
 * <p>An Alert that missed its evening is dropped rather than sent late:
 * once the claim deadline passes, the next deadline is a week away and
 * its evening has not begun. A league whose claim deadline is unknown
 * gets no scheduled board rather than one timed to another league's
 * schedule.
 */
@Component
public class WaiverAlertService {

    /** The user's zone. Pinned here, not read from the clock: the Check runs in UTC. */
    private static final ZoneId WAIVER_ZONE = ZoneId.of("America/Los_Angeles");

    private static final LocalTime WAIVER_TIME = LocalTime.of(18, 0);

    private final WaiverScorer scorer;
    private final AlertPhraser phraser;
    private final MuteStore muteStore;
    private final SettingsStore settings;
    private final AlertDeliveryOutbox outbox;

    public WaiverAlertService(WaiverScorer scorer, AlertPhraser phraser,
            MuteStore muteStore, SettingsStore settings,
            AlertDeliveryOutbox outbox) {
        this.scorer = scorer;
        this.phraser = phraser;
        this.muteStore = muteStore;
        this.settings = settings;
        this.outbox = outbox;
    }

    /**
     * Sends this week's waiver Alert if it is due and has not gone out
     * yet.
     *
     * @return the Event recorded for a sent Alert, empty otherwise
     */
    public Optional<Event> considerWaiverAlert(LeagueWeek league, Instant now) {
        Optional<ZonedDateTime> deadline = league.league().rules().nextClaimDeadline(now);
        if (deadline.isEmpty()) {
            return Optional.empty();
        }
        ZonedDateTime due = eveningBefore(deadline.get());
        if (now.isBefore(due.toInstant())) {
            return Optional.empty();
        }
        String key = "alert:waiver:" + due.toLocalDate();
        // Switched off in Settings, muted, or already sent: the board
        // is not computed at all, so a quiet week costs nothing.
        if (outbox.alreadySent(key)
                || !settings.enabled(Trigger.WAIVER)
                || muteStore.muted(Trigger.WAIVER.muteTarget())) {
            return Optional.empty();
        }

        // Nothing is recorded unless a board goes out, so a Check that
        // could not build one - or built an empty one - tries again on
        // the next minute inside the window. A board that never
        // computed is not a board that was sent.
        return switch (scorer.rank(league, now,
                WaiverQuery.everyPosition(WaiverQuery.ALERT_COUNT))) {
            case SourceResult.Unavailable<WaiverBoard> unavailable -> Optional.empty();
            case SourceResult.Ok<WaiverBoard> ok -> ok.value().candidates().isEmpty()
                    ? Optional.empty()
                    : send(key, due, deadline.get(), ok.value(), now);
        };
    }

    /**
     * 18:00 America/Los_Angeles on the day before the claim day. The
     * claim day is the deadline's own date in Sleeper's claim zone, so
     * an early-morning deadline that falls on the previous date in Los
     * Angeles still gets the evening before its claim day, and the
     * zone's own rules decide the offset across daylight saving.
     */
    static ZonedDateTime eveningBefore(ZonedDateTime deadline) {
        return deadline.withZoneSameInstant(LeagueRules.CLAIM_ZONE).toLocalDate().minusDays(1)
                .atTime(WAIVER_TIME)
                .atZone(WAIVER_ZONE);
    }

    private Optional<Event> send(String key, ZonedDateTime due, ZonedDateTime deadline,
            WaiverBoard board, Instant now) {
        String claimDay = deadline.getDayOfWeek()
                .getDisplayName(TextStyle.FULL, Locale.US);
        Map<String, String> facts = facts(board, due, deadline);
        Recommendation recommendation = new Recommendation(
                null,
                "your waiver board",
                "%s's claims: %d target%s, $%d of FAAB left".formatted(
                        claimDay,
                        board.candidates().size(),
                        board.candidates().size() == 1 ? "" : "s",
                        board.remainingBudget()),
                // A board is a set of projections, so it states the
                // doubt rather than an action: the user picks.
                Confidence.MEDIUM,
                board.candidates().stream().map(WaiverAlertService::headline).toList(),
                caveats(board, claimDay));

        String text = phraser.phrase(facts, recommendation);
        Map<String, String> recorded = new HashMap<>(facts);
        recorded.put("text", text);
        return outbox.deliver(text, List.of(
                        new Event(key, EventType.ALERT_SENT, now, Map.copyOf(recorded))))
                .stream().findFirst();
    }

    /** One target, as the outbound message would say it. */
    private static String headline(WaiverCandidate candidate) {
        return "%s (%s, %s) - score %d, %s, bid %s: %s".formatted(
                candidate.player(),
                candidate.position(),
                candidate.team(),
                candidate.score(),
                candidate.role(),
                candidate.faab(),
                String.join("; ", candidate.reasons()));
    }

    /**
     * What the board could not see, plus the standing caveat: a claim
     * is a projection, and the user is the one who places the bid.
     */
    private static List<String> caveats(WaiverBoard board, String claimDay) {
        List<String> caveats = new ArrayList<>(board.notes());
        caveats.add("Every number here is a projection for the coming week, not a promise");
        caveats.add("Place the claims yourself in Sleeper before %s's run".formatted(claimDay));
        return List.copyOf(caveats);
    }

    private static Map<String, String> facts(WaiverBoard board, ZonedDateTime due,
            ZonedDateTime deadline) {
        Map<String, String> facts = new HashMap<>();
        facts.put("trigger", "waiver board");
        facts.put("waiverEvening", due.toString());
        facts.put("claimDeadline", deadline.toString());
        facts.put("week", board.week() == null ? "unknown" : board.week());
        // A board that leads with a plain answer leads with it here too,
        // so the phrasing model never buries it under the ranking.
        if (board.answer() != null) {
            facts.put("answer", board.answer());
        }
        facts.put("remainingBudget", String.valueOf(board.remainingBudget()));
        facts.put("basis", String.join("; ", board.basis()));
        List<WaiverCandidate> candidates = board.candidates();
        for (int index = 0; index < candidates.size(); index++) {
            facts.put("target" + (index + 1), headline(candidates.get(index)));
        }
        return Map.copyOf(facts);
    }
}
