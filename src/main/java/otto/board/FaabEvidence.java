package otto.board;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.EvidenceItem;
import otto.ask.LeagueWeek;
import otto.directory.DirectoryPlayer;
import otto.research.ResearchCache;
import otto.settings.SettingsStore;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;

/**
 * The evidence the board's FAAB bids are chosen from: the bounds code
 * verified, the competition in the league, and the published
 * suggestions for each candidate.
 *
 * <p>The competition comes from the league itself first: every rival's
 * budget left and every FAAB claim this season, the lost ones
 * included, because a lost bid is the only record of what a rival was
 * willing to pay. Published suggestions are a cross-check behind it.
 */
@Component
class FaabEvidence {

    /** The most claimed players the history lists, newest first. */
    static final int HISTORY_ENTRIES = 30;

    private final SleeperAdapter sleeper;
    private final ResearchCache research;
    private final SettingsStore settings;

    FaabEvidence(SleeperAdapter sleeper, ResearchCache research, SettingsStore settings,
            OttoProperties properties) {
        this.sleeper = sleeper.cachedWithin(properties.sleeperCadence());
        this.research = research;
        this.settings = settings;
    }

    /**
     * The bidding side of one board.
     *
     * @param consensus each candidate's published suggestions, by player id
     * @param league the bounds and the competition, for the bundle
     * @param candidates each candidate's published suggestions as evidence, by player id
     * @param gaps one sentence per source that could not be read
     */
    record Bidding(BidPolicy policy, Map<String, FaabConsensus> consensus,
            List<EvidenceItem> league, Map<String, EvidenceItem> candidates, List<String> gaps) {

        Bidding {
            consensus = Map.copyOf(consensus);
            league = List.copyOf(league);
            candidates = Map.copyOf(candidates);
            gaps = List.copyOf(gaps);
        }
    }

    /**
     * Reads the bidding evidence for one board.
     *
     * <p>Side effects: one Sleeper transactions read per week of the
     * season so far, and one research cache read per candidate.
     *
     * @param directory every player Otto knows, by id, to name claimed players
     */
    Bidding read(LeagueWeek league, RosterSnapshot user, Map<String, DirectoryPlayer> directory,
            Collection<DirectoryPlayer> candidates, Instant now) {
        BidPolicy policy = BidPolicy.of(league.league(), user, settings.current());
        List<String> gaps = new ArrayList<>();
        List<EvidenceItem> leagueEvidence = List.of(boundsItem(league, policy, now),
                competitionItem(league, directory, now, gaps));
        Map<String, FaabConsensus> consensus = new LinkedHashMap<>();
        Map<String, EvidenceItem> perCandidate = new LinkedHashMap<>();
        for (DirectoryPlayer player : candidates) {
            FaabConsensus published = FaabConsensus.from(
                    research.read(player.playerId()).items());
            consensus.put(player.playerId(), published);
            perCandidate.put(player.playerId(), new EvidenceItem(
                    "consensus-" + player.playerId(), "research-cache", Optional.empty(), now,
                    "published FAAB suggestions, one per independent source, converted to "
                            + "this league's budget; a cross-check behind the league's own "
                            + "bidding",
                    published.facts(league.league().waiverBudget())));
        }
        return new Bidding(policy, consensus, leagueEvidence, perCandidate, gaps);
    }

    private static EvidenceItem boundsItem(LeagueWeek league, BidPolicy policy, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        switch (policy) {
            case BidPolicy.NoBids none -> facts.put("bids",
                    "none: this league claims by waiver priority, so state no dollar amount");
            case BidPolicy.Withheld withheld -> facts.put("bids",
                    "withheld: " + withheld.reason() + ", so state no dollar amount");
            case BidPolicy.Verified verified -> {
                facts.put("bids", verified.anyLegalBid()
                        ? "allowed, in whole dollars"
                        : "no legal bid: the minimum bid is above the most the user can bid, "
                                + "so hold and list no moves");
                league.league().waiverBudget()
                        .ifPresent(budget -> facts.put("leagueBudget", "$" + budget));
                facts.put("budgetLeft", "$" + verified.remaining());
                facts.put("minimumBid", "$" + verified.minimum());
                facts.put("bidLimit", verified.limit()
                        .map(limit -> "$%d per player, set by the user".formatted(limit))
                        .orElse("none set by the user"));
                facts.put("highestLegalBid", "$" + verified.ceiling());
            }
        }
        return new EvidenceItem("faab", "otto:faab-bounds", Optional.empty(), now,
                "the bounds every bid must stay inside", facts);
    }

    private EvidenceItem competitionItem(LeagueWeek league, Map<String, DirectoryPlayer> directory,
            Instant now, List<String> gaps) {
        Map<String, String> facts = new LinkedHashMap<>();
        Optional<Integer> leagueBudget = league.league().waiverBudget();
        facts.put("rivalBudgetsLeft", league.rosters().stream()
                .filter(roster -> !roster.userRoster())
                .map(roster -> "%s %s".formatted(roster.manager(), leagueBudget
                        .map(budget -> "$" + Math.max(0, budget - roster.waiverBudgetUsed()))
                        .orElse(Facts.UNKNOWN)))
                .collect(Collectors.joining(", ")));

        OptionalInt currentWeek = league.week().weekNumber();
        if (currentWeek.isEmpty()) {
            gaps.add("the league's bid history could not be read: the week is unknown");
            facts.put("bidHistory", Facts.UNKNOWN);
            return competition(facts, now);
        }
        List<Claimed> history = new ArrayList<>();
        for (int week = currentWeek.getAsInt(); week >= 1; week--) {
            switch (sleeper.waiverClaims(week)) {
                case SourceResult.Unavailable<List<SleeperAdapter.WaiverClaim>> down -> gaps.add(
                        "week %d's FAAB claims could not be read: %s".formatted(week,
                                down.reason()));
                case SourceResult.Ok<List<SleeperAdapter.WaiverClaim>> ok ->
                        history.addAll(claimed(week, ok.value()));
            }
        }
        long lost = history.stream().mapToLong(claimed -> claimed.lost().size()).sum();
        long won = history.stream().filter(claimed -> claimed.winner().isPresent()).count();
        facts.put("claimsRead", "%d FAAB claims this season, %d won and %d lost"
                .formatted(won + lost, won, lost));
        facts.put("bidHistory", history.isEmpty() ? "no FAAB claim yet this season"
                : history.stream()
                        .limit(HISTORY_ENTRIES)
                        .map(claimed -> claimed.describe(league, directory))
                        .collect(Collectors.joining("; ")));
        return competition(facts, now);
    }

    private static EvidenceItem competition(Map<String, String> facts, Instant now) {
        return new EvidenceItem("competition", "sleeper:transactions", Optional.empty(), now,
                "the league's own bidding, won and lost claims, and every rival's budget "
                        + "left: read this before any published consensus",
                facts);
    }

    /** Every claim on one player in one week, the winning one apart. */
    private record Claimed(int week, String playerId, Instant processed,
            Optional<SleeperAdapter.WaiverClaim> winner, List<SleeperAdapter.WaiverClaim> lost) {

        String describe(LeagueWeek league, Map<String, DirectoryPlayer> directory) {
            DirectoryPlayer player = directory.get(playerId);
            String who = player == null ? playerId
                    : "%s (%s)".formatted(player.fullName(), player.position());
            String losers = lost.stream()
                    .map(claim -> "$%d (%s)".formatted(claim.bid(), team(league, claim)))
                    .collect(Collectors.joining(", "));
            return winner
                    .map(claim -> "week %d: %s went to %s for $%d%s".formatted(week, who,
                            team(league, claim), claim.bid(),
                            losers.isEmpty() ? "" : ", lost bids " + losers))
                    .orElse("week %d: %s drew only lost bids, %s".formatted(week, who, losers));
        }

        private static String team(LeagueWeek league, SleeperAdapter.WaiverClaim claim) {
            return league.rosters().stream()
                    .filter(roster -> roster.rosterId() == claim.rosterId())
                    .findFirst()
                    .map(roster -> roster.userRoster() ? "the user" : roster.manager())
                    .orElse(RosterSnapshot.unclaimedName(claim.rosterId()));
        }
    }

    private static List<Claimed> claimed(int week, List<SleeperAdapter.WaiverClaim> claims) {
        Map<String, List<SleeperAdapter.WaiverClaim>> byPlayer = claims.stream()
                .collect(Collectors.groupingBy(SleeperAdapter.WaiverClaim::playerId,
                        LinkedHashMap::new, Collectors.toList()));
        return byPlayer.entrySet().stream()
                .map(entry -> new Claimed(week, entry.getKey(),
                        entry.getValue().stream().map(SleeperAdapter.WaiverClaim::processed)
                                .max(Comparator.naturalOrder()).orElseThrow(),
                        entry.getValue().stream().filter(SleeperAdapter.WaiverClaim::won)
                                .findFirst(),
                        entry.getValue().stream().filter(claim -> !claim.won())
                                .sorted(Comparator.comparingInt(
                                        SleeperAdapter.WaiverClaim::bid).reversed())
                                .toList()))
                .sorted(Comparator.comparing(Claimed::processed).reversed())
                .toList();
    }
}
