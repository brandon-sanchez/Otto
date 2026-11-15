package otto.board;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.ask.ToolAnswer;
import otto.claims.ClaimRound;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;
import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * The waiver preferences the user sets by chat, each scoped to the next
 * claim round of his league. The stored document is the only place a
 * preference lives: the board reads it, and the chat reads it back, so
 * a preference that has ended is gone for both however recently the
 * conversation mentioned it.
 */
@Component
public class WaiverPreferences {

    private static final String DOCUMENT = "waiver-preferences";

    private static final List<String> POSITIONS = List.of("QB", "RB", "WR", "TE");

    private static final DateTimeFormatter DEADLINE =
            DateTimeFormatter.ofPattern("EEEE, MMMM d 'at' h:mm a z", Locale.US);

    private static final String NONE_IN_FORCE = "no waiver preference is in force";

    private static final JavaType LIST_TYPE = OttoJson.MAPPER.getTypeFactory()
            .constructCollectionType(List.class, WaiverPreference.class);

    private final JsonStore store;

    public WaiverPreferences(JsonStore store) {
        this.store = store;
    }

    /**
     * What the chat sees after an action.
     *
     * @param outcome what the action did, in one line
     * @param inForce the preference in force for the league's next claim
     *        round, in words, or the line saying there is none
     */
    public record View(String outcome, String inForce) {
    }

    /**
     * Applies one chat action to the league's waiver preference.
     *
     * <p>Side effects: "set" and "clear" write the stored document.
     *
     * @param action set, show or clear; blank shows
     * @param positions the positions to set, any of QB, RB, WR and TE
     * @param only true to keep the board to the positions, otherwise
     *        they are ranked first
     * @return unavailable for an unknown action, for a set that names no
     *         position or an unknown one, or when
     *         the league's claim day is unknown, since a preference then
     *         has no round to apply to
     */
    public synchronized ToolAnswer<View> apply(SleeperAdapter.League league, String action,
            List<String> positions, boolean only, Instant now) {
        String verb = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "show", "" -> ToolAnswer.of(new View("these are your waiver preferences",
                    inForce(league.leagueId(), now)));
            case "set" -> set(league, positions, only, now);
            case "clear" -> {
                store.write(DOCUMENT, openForOtherLeagues(league.leagueId(), now));
                yield ToolAnswer.of(new View("cleared your waiver preference",
                        NONE_IN_FORCE));
            }
            default -> ToolAnswer.unavailable(
                    "unknown action \"%s\"; use set, show or clear".formatted(action));
        };
    }

    /**
     * Returns the preference that steers this claim round's board, or
     * empty. A preference set for any other round never applies, so one
     * that has ended cannot come back.
     */
    public Optional<WaiverPreference> forRound(ClaimRound round) {
        return all().stream()
                .filter(preference -> preference.leagueId().equals(round.leagueId()))
                .filter(preference -> preference.claimDeadline()
                        .equals(round.deadline().toInstant()))
                .findFirst();
    }

    private ToolAnswer<View> set(SleeperAdapter.League league, List<String> positions,
            boolean only, Instant now) {
        List<String> named = positions == null ? List.of() : positions.stream()
                .map(position -> position.strip().toUpperCase(Locale.ROOT))
                .toList();
        if (named.isEmpty()) {
            return ToolAnswer.unavailable("name at least one position: QB, RB, WR or TE");
        }
        Optional<String> unknown = named.stream()
                .filter(position -> !POSITIONS.contains(position))
                .findFirst();
        if (unknown.isPresent()) {
            return ToolAnswer.unavailable("%s is not a position the waiver board ranks; "
                    .formatted(unknown.get()) + "it ranks QB, RB, WR and TE");
        }
        Optional<ClaimRound> round = ClaimRound.next(league, now);
        if (round.isEmpty()) {
            return ToolAnswer.unavailable("the league's claim day is unknown, so there is "
                    + "no waiver round to apply a preference to");
        }
        WaiverPreference preference = new WaiverPreference(league.leagueId(),
                round.get().deadline().toInstant(),
                POSITIONS.stream().filter(named::contains).toList(),
                only ? WaiverPreference.Mode.ONLY : WaiverPreference.Mode.FOCUS, now);
        List<WaiverPreference> preferences = openForOtherLeagues(league.leagueId(), now);
        preferences.add(preference);
        store.write(DOCUMENT, preferences);
        return ToolAnswer.of(new View("set your waiver preference",
                describe(preference)));
    }

    private String inForce(String leagueId, Instant now) {
        return all().stream()
                .filter(preference -> preference.leagueId().equals(leagueId))
                .filter(preference -> preference.claimDeadline().isAfter(now))
                .findFirst()
                .map(WaiverPreferences::describe)
                .orElse(NONE_IN_FORCE);
    }

    private static String describe(WaiverPreference preference) {
        return "%s, for the waiver claims that close %s; it ends when those claims close"
                .formatted(preference.phrase(), DEADLINE.format(
                        preference.claimDeadline().atZone(LeagueRules.CLAIM_ZONE)));
    }

    private List<WaiverPreference> openForOtherLeagues(String leagueId, Instant now) {
        return new ArrayList<>(all().stream()
                .filter(preference -> !preference.leagueId().equals(leagueId))
                .filter(preference -> preference.claimDeadline().isAfter(now))
                .toList());
    }

    private List<WaiverPreference> all() {
        return store.<List<WaiverPreference>>read(DOCUMENT, LIST_TYPE).orElseGet(List::of);
    }
}
