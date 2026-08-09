package otto.settings;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import otto.alerts.MuteStore;
import otto.ask.ToolAnswer;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerLookup;

/**
 * The chat side of the Settings document and the mute list. There is no
 * settings screen by design, so this is the only way the user shapes
 * how loud the assistant is: which triggers fire, how big a point edge
 * is worth a message, where the Notable Player line sits, and what is
 * silenced.
 *
 * Settings and mutes answer through one view because the user thinks of
 * them as one question - "what will you tell me about?" - and the spec
 * asks for both in compact list form.
 */
@Component
public class SettingsService {

    /** The setting name for the edge threshold, as the chat spells it. */
    private static final String EDGE_THRESHOLD = "edge_threshold";

    /** The prefix of a Notable Player cutoff name: notable_qb and kin. */
    private static final String NOTABLE = "notable_";

    /** The quiet-hours field exists in v1 and stays empty. */
    private static final String QUIET_HOURS = "quiet_hours";

    private static final String QUIET_HOURS_REFUSAL =
            "quiet hours are not part of v1, so the field stays empty";

    private final SettingsStore store;
    private final MuteStore muteStore;
    private final PlayerLookup lookup;

    public SettingsService(SettingsStore store, MuteStore muteStore, PlayerLookup lookup) {
        this.store = store;
        this.muteStore = muteStore;
        this.lookup = lookup;
    }

    /**
     * Everything the chat can see and change. Every name in it is a
     * name this tool accepts back, so the user can quote what he was
     * shown instead of translating it.
     *
     * @param outcome what the requested action did, in one line
     * @param triggers each Alert trigger and whether it is on
     * @param muted the mute list in compact form
     */
    public record View(
            String outcome,
            Map<String, String> triggers,
            @JsonProperty("edge_threshold") String edgeThreshold,
            @JsonProperty("notable_player_cutoffs") Map<String, Integer> notablePlayerCutoffs,
            @JsonProperty("quiet_hours") String quietHours,
            List<String> muted) {
    }

    public ToolAnswer<View> apply(String action, String name, String value, Instant now) {
        String verb = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "show", "" -> ToolAnswer.of(view("these are your current settings"));
            case "set" -> set(name, value);
            case "mute" -> silence(name, true, now);
            case "unmute" -> silence(name, false, now);
            default -> ToolAnswer.unavailable(
                    "I do not know the settings action %s; I can show, set, mute or unmute"
                            .formatted(action));
        };
    }

    // Settings

    private ToolAnswer<View> set(String name, String value) {
        String setting = normalize(name);
        if (setting.isEmpty()) {
            return ToolAnswer.unavailable("I need the name of the setting to change");
        }
        if (QUIET_HOURS.equals(setting)) {
            return ToolAnswer.of(view(QUIET_HOURS_REFUSAL));
        }

        Optional<Trigger> trigger = Trigger.byChatName(setting);
        if (trigger.isPresent()) {
            return switchTrigger(trigger.get(), value);
        }
        if (EDGE_THRESHOLD.equals(setting)) {
            return setEdgeThreshold(value);
        }
        if (setting.startsWith(NOTABLE)) {
            return setNotableCutoff(setting, value);
        }
        return ToolAnswer.unavailable(
                "I have no setting called %s. I keep %s.".formatted(setting, settingNames()));
    }

    private ToolAnswer<View> switchTrigger(Trigger trigger, String value) {
        Optional<Boolean> on = onOff(value);
        if (on.isEmpty()) {
            return ToolAnswer.unavailable(
                    "%s is on or off; I cannot read %s".formatted(trigger.chatName(), value));
        }
        store.change(settings -> settings.withTrigger(trigger, on.get()));
        return ToolAnswer.of(view("%s alerts are now %s".formatted(
                trigger.chatName(), on.get() ? "on" : "off")));
    }

    private ToolAnswer<View> setEdgeThreshold(String value) {
        Optional<Double> points = points(value);
        if (points.isEmpty() || points.get() < 0) {
            return ToolAnswer.unavailable(
                    "the edge threshold is a number of points; I cannot read %s"
                            .formatted(value));
        }
        store.change(settings -> settings.withEdgeThreshold(points.get()));
        return ToolAnswer.of(view("the edge threshold is now %s points"
                .formatted(format(points.get()))));
    }

    private ToolAnswer<View> setNotableCutoff(String setting, String value) {
        String position = setting.substring(NOTABLE.length()).toUpperCase(Locale.ROOT);
        if (!Settings.CUTOFF_POSITIONS.contains(position)) {
            return ToolAnswer.unavailable(
                    "the Notable Player cutoffs cover %s".formatted(
                            String.join(", ", Settings.CUTOFF_POSITIONS)));
        }
        Optional<Integer> rank = rank(value);
        if (rank.isEmpty()) {
            return ToolAnswer.unavailable(
                    "a Notable Player cutoff is a rank of 1 or more; I cannot read %s"
                            .formatted(value));
        }
        store.change(settings -> settings.withNotablePlayerCutoff(position, rank.get()));
        return ToolAnswer.of(view("a %s inside the top %d now counts as notable"
                .formatted(position, rank.get())));
    }

    // Mutes

    private ToolAnswer<View> silence(String name, boolean muting, Instant now) {
        return muteTarget(name).map(target -> {
            if (muting) {
                muteStore.mute(target.target(), now);
            } else {
                muteStore.unmute(target.target());
            }
            return ToolAnswer.of(view("%s %s".formatted(
                    muting ? "muted" : "unmuted", target.description())));
        }).orElseGet(() -> unknownMuteTarget(name));
    }

    /** A mute target and the words the chat shows for it. */
    private record MuteTarget(String target, String description) {
    }

    /**
     * What the user meant by a name: a class of Alerts, or one player's
     * news. A trigger name wins over a player name, because that is
     * what the settings view lists back to them.
     *
     * The words the mute list shows are accepted straight back, so an
     * unmute can quote the list rather than translate it.
     */
    private Optional<MuteTarget> muteTarget(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String wanted = withoutListSuffix(name);
        Optional<Trigger> trigger = Trigger.byChatName(wanted);
        if (trigger.isPresent()) {
            return Optional.of(new MuteTarget(trigger.get().muteTarget(),
                    alertsLabel(trigger.get())));
        }
        if (lookup.find(wanted) instanceof PlayerLookup.Match.Found found) {
            DirectoryPlayer player = found.player();
            return Optional.of(new MuteTarget(MuteStore.playerTarget(player.playerId()),
                    newsLabel(player.fullName())));
        }
        return Optional.empty();
    }

    /** Drops the trailing word the mute list adds: "bench_edge alerts". */
    private static String withoutListSuffix(String name) {
        String trimmed = name.trim();
        for (String suffix : List.of(" alerts", " news")) {
            if (trimmed.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                return trimmed.substring(0, trimmed.length() - suffix.length()).trim();
            }
        }
        return trimmed;
    }

    private static String alertsLabel(Trigger trigger) {
        return "%s alerts".formatted(trigger.chatName());
    }

    private static String newsLabel(String player) {
        return "%s news".formatted(player);
    }

    private ToolAnswer<View> unknownMuteTarget(String name) {
        return ToolAnswer.unavailable(
                "I can silence a class of alerts (%s) or one player's news; %s is neither"
                        .formatted(triggerNames(), name));
    }

    // The view

    private View view(String outcome) {
        Settings settings = store.current();
        Map<String, String> triggers = new LinkedHashMap<>();
        settings.triggers().forEach((trigger, on) ->
                triggers.put(trigger.chatName(), on ? "on" : "off"));
        Map<String, Integer> cutoffs = new LinkedHashMap<>();
        for (String position : Settings.CUTOFF_POSITIONS) {
            cutoffs.put(cutoffName(position), settings.notableCutoff(position));
        }
        return new View(outcome, triggers, format(settings.edgeThreshold()),
                cutoffs, settings.quietHours(), muteList());
    }

    /**
     * The mute list in compact form. A Mute set by a button under an
     * Alert and one set by chat are the same record, so both read back
     * the same way.
     */
    private List<String> muteList() {
        List<String> muted = new ArrayList<>();
        for (MuteStore.Mute mute : muteStore.all()) {
            Optional<Trigger> trigger = Trigger.byMuteTarget(mute.target());
            Optional<String> playerId = MuteStore.playerIdOf(mute.target());
            if (trigger.isPresent()) {
                muted.add(alertsLabel(trigger.get()));
            } else if (playerId.isPresent()) {
                muted.add(newsLabel(playerName(playerId.get())));
            } else {
                muted.add(mute.target());
            }
        }
        return muted;
    }

    private String playerName(String playerId) {
        return lookup.find(playerId) instanceof PlayerLookup.Match.Found found
                ? found.player().fullName()
                : playerId;
    }

    private String settingNames() {
        return "%s, %s, %s and %s".formatted(triggerNames(), EDGE_THRESHOLD,
                String.join(", ", notableNames()), QUIET_HOURS);
    }

    private static String triggerNames() {
        List<String> names = new ArrayList<>();
        for (Trigger trigger : Trigger.values()) {
            names.add(trigger.chatName());
        }
        return String.join(", ", names);
    }

    private static List<String> notableNames() {
        return Settings.CUTOFF_POSITIONS.stream()
                .map(SettingsService::cutoffName)
                .toList();
    }

    /** The name the chat uses for one position's Notable Player cutoff. */
    private static String cutoffName(String position) {
        return NOTABLE + position.toLowerCase(Locale.ROOT);
    }

    private static String normalize(String name) {
        return Trigger.normalize(name);
    }

    private static Optional<Boolean> onOff(String value) {
        String normalized = normalize(value);
        return switch (normalized) {
            case "on", "true", "yes", "enabled" -> Optional.of(true);
            case "off", "false", "no", "disabled" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    private static Optional<Double> points(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(value.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> rank(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            int rank = Integer.parseInt(value.trim());
            return rank >= 1 ? Optional.of(rank) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
