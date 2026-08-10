package otto.waivers;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.alerts.AlertIdSequence;
import otto.alerts.AlertPhraser;
import otto.alerts.Confidence;
import otto.alerts.MuteStore;
import otto.alerts.Recommendation;
import otto.ask.LeagueWeek;
import otto.events.Event;
import otto.events.EventLog;
import otto.events.EventType;
import otto.settings.SettingsStore;
import otto.settings.Trigger;
import otto.sleeper.SourceResult;
import otto.telegram.TelegramClient;

/**
 * The Tuesday waiver Alert: the top five free agents, their reasons,
 * their role tags and a FAAB range each, in the user's chat at 18:00
 * America/Los_Angeles the evening before Wednesday's claims clear.
 *
 * <p>It runs no scheduler of its own. Every Check asks the same
 * question - has the most recent Tuesday 18:00 passed, and does the
 * Event Log already hold that Tuesday's Alert? - so the 1-minute loop
 * plus one Event Log key is the whole timer. The instant is derived by
 * putting 18:00 on the Tuesday's own date in the America/Los_Angeles
 * zone, so the summer Alert lands at 01:00Z and the winter one at
 * 02:00Z without anybody writing an offset down.
 *
 * <p>An Alert that missed its evening is dropped rather than sent late.
 * Waivers clear on Wednesday, so the board goes out on the Tuesday it
 * is due or not at all - and the Event Log stays empty for that
 * Tuesday, which is the honest record of a board that never went out.
 */
@Component
public class WaiverAlertService {

    /** The user's zone. Pinned here, not read from the clock: the Check runs in UTC. */
    private static final ZoneId WAIVER_ZONE = ZoneId.of("America/Los_Angeles");

    private static final DayOfWeek WAIVER_DAY = DayOfWeek.TUESDAY;
    private static final LocalTime WAIVER_TIME = LocalTime.of(18, 0);

    private final WaiverScorer scorer;
    private final AlertPhraser phraser;
    private final TelegramClient telegram;
    private final EventLog eventLog;
    private final MuteStore muteStore;
    private final SettingsStore settings;
    private final AlertIdSequence idSequence;

    public WaiverAlertService(WaiverScorer scorer, AlertPhraser phraser, TelegramClient telegram,
            EventLog eventLog, MuteStore muteStore, SettingsStore settings,
            AlertIdSequence idSequence) {
        this.scorer = scorer;
        this.phraser = phraser;
        this.telegram = telegram;
        this.eventLog = eventLog;
        this.muteStore = muteStore;
        this.settings = settings;
        this.idSequence = idSequence;
    }

    /**
     * Sends this Tuesday's waiver Alert if it is due and has not gone
     * out yet.
     *
     * @return the Event recorded for a sent Alert, empty otherwise
     */
    public Optional<Event> considerWaiverAlert(LeagueWeek league, Instant now) {
        ZonedDateTime due = mostRecentWaiverEvening(now);
        if (!now.isBefore(lastCallFor(due))) {
            return Optional.empty();
        }
        String key = "alert:waiver:" + due.toLocalDate();
        // Switched off in Settings, muted, or already sent: the board
        // is not computed at all, so a quiet Tuesday costs nothing.
        if (eventLog.contains(key)
                || !settings.enabled(Trigger.WAIVER)
                || muteStore.muted(Trigger.WAIVER.muteTarget())) {
            return Optional.empty();
        }

        // Nothing is recorded unless a board goes out, so a Check that
        // could not build one - or built an empty one - tries again on
        // the next minute inside the window. A board that never
        // computed is not a board that was sent.
        return switch (scorer.rank(league, now,
                WaiverQuery.everyPosition(WaiverQuery.TUESDAY_COUNT))) {
            case SourceResult.Unavailable<WaiverBoard> unavailable -> Optional.empty();
            case SourceResult.Ok<WaiverBoard> ok -> ok.value().candidates().isEmpty()
                    ? Optional.empty()
                    : send(key, due, ok.value(), now);
        };
    }

    /**
     * The Tuesday 18:00 America/Los_Angeles that has most recently
     * passed. Derived from the local calendar date, so the zone's own
     * rules decide the offset and no daylight-saving change can move
     * the Alert an hour.
     */
    static ZonedDateTime mostRecentWaiverEvening(Instant now) {
        LocalDate today = now.atZone(WAIVER_ZONE).toLocalDate();
        ZonedDateTime thisWeek = today.with(TemporalAdjusters.previousOrSame(WAIVER_DAY))
                .atTime(WAIVER_TIME)
                .atZone(WAIVER_ZONE);
        return thisWeek.toInstant().isAfter(now) ? thisWeek.minusWeeks(1) : thisWeek;
    }

    /**
     * When the evening is over and the board is no longer worth
     * sending: midnight at the end of the Tuesday it was due.
     *
     * Claims clear on Wednesday, so a board that lands on Wednesday is
     * worse than no board - it reads as advice on bids that have
     * already processed. Ending at the local midnight keeps the whole
     * evening available to retry a Check that could not reach a source
     * at 18:00, and keeps the board off Wednesday entirely. The
     * calendar decides the instant, so a daylight-saving change moves
     * the cutoff with the evening rather than an hour away from it.
     */
    private static Instant lastCallFor(ZonedDateTime due) {
        return due.toLocalDate().plusDays(1).atStartOfDay(WAIVER_ZONE).toInstant();
    }

    private Optional<Event> send(String key, ZonedDateTime due, WaiverBoard board, Instant now) {
        Map<String, String> facts = facts(board, due);
        Recommendation recommendation = new Recommendation(
                null,
                "your waiver board",
                "Wednesday's claims: %d target%s, $%d of FAAB left".formatted(
                        board.candidates().size(),
                        board.candidates().size() == 1 ? "" : "s",
                        board.remainingBudget()),
                // A board is a set of projections, so it states the
                // doubt rather than an action: the user picks.
                Confidence.MEDIUM,
                board.candidates().stream().map(WaiverAlertService::headline).toList(),
                caveats(board));

        String text = phraser.phrase(facts, recommendation);
        long alertId = idSequence.next();
        if (!telegram.sendAlert(text, alertId)) {
            return Optional.empty();
        }
        Map<String, String> recorded = new HashMap<>(facts);
        recorded.put("alertId", String.valueOf(alertId));
        recorded.put("text", text);
        Event event = new Event(key, EventType.ALERT_SENT, now, Map.copyOf(recorded));
        return eventLog.append(event) ? Optional.of(event) : Optional.empty();
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
    private static List<String> caveats(WaiverBoard board) {
        List<String> caveats = new ArrayList<>(board.notes());
        caveats.add("Every number here is a projection for the coming week, not a promise");
        caveats.add("Place the claims yourself in Sleeper before Wednesday's run");
        return List.copyOf(caveats);
    }

    private static Map<String, String> facts(WaiverBoard board, ZonedDateTime due) {
        Map<String, String> facts = new HashMap<>();
        facts.put("trigger", "waiver board");
        facts.put("waiverEvening", due.toString());
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
