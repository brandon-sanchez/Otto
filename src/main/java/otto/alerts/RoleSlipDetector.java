package otto.alerts;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.claims.RoleSlipLedger;
import otto.decisions.UsageDrop;
import otto.directory.PlayerHealth;
import otto.nflverse.NflverseStore;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.PlayerUsage;
import otto.nflverse.WeeklyStats;
import otto.research.PreSendResearch;
import otto.research.ResearchCache;
import otto.research.ResearchItem;
import otto.research.ResearchTopic;
import otto.snapshot.RosterSnapshot;

/**
 * Finds healthy players on the user's active roster whose role may have
 * slipped: credible news of a smaller role, or a real {@link UsageDrop}
 * in his latest game. Either one starts a player decision; fantasy
 * points alone never do.
 *
 * <p>News is credible when it is an original report credited to a
 * named outlet and published inside {@link PreSendResearch#REPORT_WINDOW}.
 * It is read from the research cache only, which research discovery
 * keeps filled for every rostered player.
 *
 * <p>Usage is recomputed at most once per {@link #USAGE_SCAN_INTERVAL},
 * because it parses every stored usage feed and those change a few
 * times a week. The candidate key names the evidence behind it, so new
 * evidence about the same player is a new candidate and the same
 * evidence never is.
 */
@Component
public class RoleSlipDetector {

    static final Duration USAGE_SCAN_INTERVAL = Duration.ofHours(1);

    static final String USAGE_PREFIX = "usage:";

    private static final String UNKNOWN_SOURCE = "unknown";
    private static final String NO_PLAYER = "0";

    private final ResearchCache cache;
    private final NflverseStore nflverse;
    private final RoleSlipLedger ledger;

    public RoleSlipDetector(ResearchCache cache, NflverseStore nflverse, RoleSlipLedger ledger) {
        this.cache = cache;
        this.nflverse = nflverse;
        this.ledger = ledger;
    }

    /**
     * Returns a candidate for each healthy active player whose role may
     * have slipped.
     *
     * <p>Side effects: one research cache read per active player, and,
     * once per {@link #USAGE_SCAN_INTERVAL}, a parse of the stored
     * usage feeds and a ledger write.
     */
    public List<AlertCandidate> detect(RosterSnapshot roster, Instant now) {
        Map<String, UsageDrop> drops = usageDrops(roster, now);
        List<AlertCandidate> candidates = new ArrayList<>();
        for (String playerId : roster.players()) {
            if (!healthyAndActive(roster, playerId)) {
                continue;
            }
            Optional<ResearchItem> news = roleNews(playerId, now);
            Optional<UsageDrop> drop = Optional.ofNullable(drops.get(playerId));
            if (news.isPresent() || drop.isPresent()) {
                candidates.add(candidate(roster, playerId, news, drop));
            }
        }
        return candidates;
    }

    private static boolean healthyAndActive(RosterSnapshot roster, String playerId) {
        if (NO_PLAYER.equals(playerId) || roster.reserve().contains(playerId)
                || roster.taxi().contains(playerId)) {
            return false;
        }
        PlayerHealth health = roster.playerHealth().get(playerId);
        return health == null || !health.isWorseThan(PlayerHealth.PROBABLE);
    }

    private Optional<ResearchItem> roleNews(String playerId, Instant now) {
        Instant since = now.minus(PreSendResearch.REPORT_WINDOW);
        return cache.read(playerId).items().stream()
                .filter(ResearchItem::original)
                .filter(item -> item.topics().contains(ResearchTopic.ROLE))
                .filter(item -> !UNKNOWN_SOURCE.equals(item.creditedSource()))
                .filter(item -> item.published() != null && !item.published().isBefore(since))
                .findFirst();
    }

    private Map<String, UsageDrop> usageDrops(RosterSnapshot roster, Instant now) {
        RoleSlipLedger.Document held = ledger.read();
        if (held.usageScannedAt() != null
                && Duration.between(held.usageScannedAt(), now).compareTo(USAGE_SCAN_INTERVAL) < 0) {
            return held.usageDrops();
        }
        Map<String, UsageDrop> drops = scan(roster);
        ledger.scanned(now, drops);
        return drops;
    }

    private Map<String, UsageDrop> scan(RosterSnapshot roster) {
        Optional<PlayerUsage> usage = PlayerUsage.from(nflverse)
                .filter(held -> !held.priorSeasonFinal());
        Optional<PlayerIdMap> ids = nflverse.playerIds();
        if (usage.isEmpty() || ids.isEmpty()) {
            return Map.of();
        }
        int newestWeek = nflverse.weeklyStats().stream()
                .flatMap(stats -> stats.rows().stream())
                .mapToInt(WeeklyStats.StatLine::week)
                .max()
                .orElse(0);
        Map<String, UsageDrop> drops = new HashMap<>();
        for (String playerId : roster.players()) {
            ids.get().gsisFor(playerId)
                    .flatMap(gsisId -> UsageDrop.in(usage.get().games(gsisId), newestWeek))
                    .ifPresent(drop -> drops.put(playerId, drop));
        }
        return Map.copyOf(drops);
    }

    private static AlertCandidate candidate(RosterSnapshot roster, String playerId,
            Optional<ResearchItem> news, Optional<UsageDrop> drop) {
        String player = roster.playerNames().getOrDefault(playerId, playerId);
        String team = roster.playerTeams().getOrDefault(playerId, "");
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("playerId", playerId);
        facts.put("player", player);
        facts.put("team", team);
        List<String> evidence = new ArrayList<>();
        news.ifPresent(item -> {
            facts.put("roleNews", Stream.of(item.headline(), item.detail())
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining(": ")));
            facts.put("roleNewsSource", item.creditedSource());
            facts.put("roleNewsPublished", item.published().toString());
            evidence.add("news-" + item.storyKey());
        });
        drop.ifPresent(usage -> {
            usage.facts().forEach((key, value) -> facts.put(USAGE_PREFIX + key, value));
            evidence.add("usage-w" + usage.week());
        });
        Recommendation slip = new Recommendation(playerId, player,
                "%s's role may have slipped".formatted(player), Confidence.MEDIUM, List.of(),
                List.of());
        return new AlertCandidate(AlertCandidate.Source.ROLE_SLIP,
                "role-slip:%s:%s".formatted(playerId, String.join("+", evidence)), playerId,
                team, slip, Map.copyOf(facts));
    }
}
