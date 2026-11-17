package otto.sleeper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Reads a league's completed drafts, this season's and the seasons
 * before it, by following each league's {@code previous_league_id}.
 * A finished draft never changes, so the reads are cached for hours
 * rather than polled.
 */
@Component
public class SleeperDrafts {

    private static final Duration CACHED_FOR = Duration.ofHours(12);

    private static final String COMPLETE = "complete";

    private final SleeperAdapter sleeper;

    public SleeperDrafts(SleeperAdapter sleeper) {
        this.sleeper = sleeper.cachedWithin(CACHED_FOR);
    }

    /** How a draft hands out players. */
    public enum DraftType {
        /** Rounds; a keeper costs a round. Sleeper's snake and linear drafts. */
        ROUNDS,
        /** Bids against a budget; a keeper costs dollars. */
        AUCTION
    }

    /**
     * One season's completed draft.
     *
     * @param season the season it stocked, such as "2026"
     * @param rounds how many rounds it ran
     * @param teams how many teams drafted
     * @param budget each team's auction budget in dollars, empty in a
     *        rounds draft
     * @param picks every pick, keepers included
     */
    public record Draft(String season, DraftType type, int rounds, int teams,
            Optional<Integer> budget, List<Pick> picks) {

        public Draft {
            picks = List.copyOf(picks);
        }

        /** Returns the pick that took this player, or empty when nobody drafted him. */
        public Optional<Pick> pickOf(String playerId) {
            return picks.stream().filter(pick -> pick.playerId().equals(playerId)).findFirst();
        }
    }

    /**
     * One draft pick.
     *
     * @param rosterId the roster that made the pick, 0 when Sleeper did
     *        not say
     * @param pickNumber the pick's place in the whole draft, from 1
     * @param keeper true when the commissioner placed a kept player on
     *        this pick, so its round or price is what keeping him cost
     * @param price what an auction paid in dollars, empty in a rounds draft
     */
    public record Pick(String playerId, int rosterId, int round, int pickNumber, boolean keeper,
            Optional<Integer> price) {
    }

    /**
     * Returns the league's own newest completed draft.
     *
     * <p>Side effects: up to two Sleeper requests.
     *
     * @return the draft, empty when the league has not finished one, or
     *         unavailable when Sleeper can't be read or its answer has
     *         drifted
     */
    public SourceResult<Optional<Draft>> latest(String leagueId) {
        return completedDraft(leagueId);
    }

    /**
     * Returns the league's completed drafts, newest first: this season's
     * when it is done, then one for each earlier season the league
     * links back to, up to {@code seasons} seasons in all. A season
     * whose draft has not finished is skipped.
     *
     * <p>Side effects: up to three Sleeper requests per season read.
     *
     * @return the drafts, empty when the league has never finished one,
     *         or unavailable when Sleeper can't be read or its answer
     *         has drifted
     */
    public SourceResult<List<Draft>> completedDrafts(String leagueId, int seasons) {
        List<Draft> drafts = new ArrayList<>();
        Optional<String> next = Optional.of(leagueId);
        for (int read = 0; read < seasons && next.isPresent(); read++) {
            String id = next.get();
            switch (completedDraft(id)) {
                case SourceResult.Unavailable<Optional<Draft>> none -> {
                    return new SourceResult.Unavailable<>(none.source(), none.reason());
                }
                case SourceResult.Ok<Optional<Draft>> ok -> ok.value().ifPresent(drafts::add);
            }
            switch (previousLeague(id)) {
                case SourceResult.Unavailable<Optional<String>> none -> {
                    return new SourceResult.Unavailable<>(none.source(), none.reason());
                }
                case SourceResult.Ok<Optional<String>> ok -> next = ok.value();
            }
        }
        return new SourceResult.Ok<>(List.copyOf(drafts));
    }

    private SourceResult<Optional<String>> previousLeague(String leagueId) {
        return sleeper.leagueDocument(leagueId).flatMap(body -> {
            JsonNode previous = body.path("previous_league_id");
            // Sleeper writes "0" as well as null on a league's first season.
            boolean none = !previous.isTextual() || previous.asText().isBlank()
                    || previous.asText().equals("0");
            return new SourceResult.Ok<>(none ? Optional.empty() : Optional.of(previous.asText()));
        });
    }

    /**
     * Reads the league's newest completed draft by season. A league lists
     * one draft a season, so the newest season is this one's.
     */
    private SourceResult<Optional<Draft>> completedDraft(String leagueId) {
        String path = "/v1/league/" + leagueId + "/drafts";
        return sleeper.drafts(leagueId).flatMap(body -> {
            if (!body.isArray()) {
                return drift(path, "drafts is not an array");
            }
            Optional<JsonNode> newest = Optional.empty();
            for (JsonNode draft : body) {
                boolean complete = COMPLETE.equals(draft.path("status").asText())
                        && !draft.path("draft_id").asText("").isBlank();
                if (complete && draft.path("season").asText("").compareTo(newest
                        .map(found -> found.path("season").asText("")).orElse("")) >= 0) {
                    newest = Optional.of(draft);
                }
            }
            return newest.isEmpty()
                    ? new SourceResult.Ok<>(Optional.<Draft>empty())
                    : draft(newest.get());
        });
    }

    private SourceResult<Optional<Draft>> draft(JsonNode draft) {
        String id = draft.get("draft_id").asText();
        JsonNode settings = draft.path("settings");
        DraftType type = "auction".equals(draft.path("type").asText().toLowerCase(Locale.ROOT))
                ? DraftType.AUCTION : DraftType.ROUNDS;
        if (!whole(settings.path("rounds")) || !whole(settings.path("teams"))) {
            return drift("/v1/draft/" + id, "a completed draft's rounds or teams is missing");
        }
        if (type == DraftType.AUCTION && !whole(settings.path("budget"))) {
            return drift("/v1/draft/" + id, "a completed auction's budget is missing");
        }
        Optional<Integer> budget = type == DraftType.AUCTION
                ? Optional.of(settings.get("budget").asInt()) : Optional.empty();
        String path = "/v1/draft/" + id + "/picks";
        return sleeper.draftPicks(id).flatMap(body -> {
            if (!body.isArray()) {
                return drift(path, "picks is not an array");
            }
            List<Pick> picks = new ArrayList<>();
            for (JsonNode pick : body) {
                if (!pick.hasNonNull("player_id") || !whole(pick.path("round"))
                        || !whole(pick.path("pick_no"))) {
                    return drift(path, "a pick's player_id, round or pick_no is missing");
                }
                picks.add(new Pick(pick.get("player_id").asText(),
                        pick.path("roster_id").asInt(0), pick.get("round").asInt(),
                        pick.get("pick_no").asInt(), pick.path("is_keeper").asBoolean(false),
                        price(pick.path("metadata").path("amount"))));
            }
            return new SourceResult.Ok<>(Optional.of(new Draft(draft.path("season").asText(),
                    type, settings.get("rounds").asInt(), settings.get("teams").asInt(), budget,
                    picks)));
        });
    }

    /** Sleeper writes an auction price as a string, such as "20". */
    private static Optional<Integer> price(JsonNode amount) {
        if (amount.isIntegralNumber()) {
            return Optional.of(amount.asInt());
        }
        if (amount.isTextual() && amount.asText().matches("\\d{1,4}")) {
            return Optional.of(Integer.parseInt(amount.asText()));
        }
        return Optional.empty();
    }

    private static boolean whole(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt();
    }

    private static <T> SourceResult<T> drift(String path, String reason) {
        return new SourceResult.Unavailable<>("sleeper:" + path, "schema drift: " + reason);
    }
}
