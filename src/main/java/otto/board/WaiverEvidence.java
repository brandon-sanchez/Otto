package otto.board;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.ask.LeagueWeek;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.lineup.Slot;
import otto.needs.RosterNeeds;
import otto.needs.RosterNeedsService;
import otto.records.Candidate;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.storage.OttoJson;

/**
 * Screens every available player in the league and builds the evidence
 * bundle the model ranks the waiver board from.
 *
 * <p>Every free agent at QB, RB, WR and TE with an NFL team is
 * screened by every {@link EvidenceLane}. The candidate pool is the
 * union of what the lanes name, with no weights between them; each
 * candidate then carries every lane's evidence, the roster needs and
 * the user's roster, and the model weighs it all. Players on any
 * roster in the league are never screened, so they can never be
 * recommended.
 *
 * <p>A {@link WaiverPreference} the user set for the round is handed to
 * the model as evidence. One that keeps the board to some positions
 * also narrows the screening to them, so no other player can be
 * recommended; a focus leaves the pool whole.
 */
@Component
public class WaiverEvidence {

    private static final Set<String> POSITIONS = Set.of("QB", "RB", "WR", "TE");

    /** The longest opening or entry the model may write, in characters. */
    static final int PART_MAX_LENGTH = 700;

    /** The longest explanation of a bid's upper limit, in characters. */
    static final int LIMIT_MAX_LENGTH = 300;

    /** Room for an entry's heading and the break before it, in characters. */
    private static final int HEADING_ALLOWANCE = 100;

    /**
     * The label on a fallback candidate whom only popularity lanes name,
     * in his evidence, his Decision Record reason and his board entry.
     */
    static final String TRENDING_ONLY = "trending, but this week's usage numbers aren't in yet";

    private static final String TASK = "Rank this week's waiver candidates against the user's "
            + "roster needs and write the waiver board.";

    private final PlayerDirectoryStore directory;
    private final RosterNeedsService needs;
    private final List<EvidenceLane> lanes;
    private final FaabEvidence faab;

    WaiverEvidence(PlayerDirectoryStore directory, RosterNeedsService needs,
            List<EvidenceLane> lanes, FaabEvidence faab) {
        this.directory = directory;
        this.needs = needs;
        this.lanes = List.copyOf(lanes);
        this.faab = faab;
    }

    /**
     * One screened board, ready for the model.
     *
     * @param candidates the pool the model may recommend from, by
     *        player id, in screening order
     * @param screened every free agent screened, each kept in the pool
     *        or left out with the reason
     * @param comparisons what code worked out for the run: each lane's
     *        picks, the roster needs and the bid bounds, for the Decision
     *        Record
     * @param bidding the bid bounds and each candidate's published
     *        suggestions
     * @param trendingOnly the candidates only popularity lanes name while
     *        a required feed lacks the latest week, by player id; each is
     *        labelled {@link #TRENDING_ONLY}
     */
    public record Screen(EvidenceBundle bundle, Map<String, DirectoryPlayer> candidates,
            List<Candidate> screened, JsonNode comparisons, RosterSnapshot user,
            FaabEvidence.Bidding bidding, Set<String> trendingOnly) {

        public Screen {
            candidates = Collections.unmodifiableMap(new LinkedHashMap<>(candidates));
            screened = List.copyOf(screened);
            trendingOnly = Set.copyOf(trendingOnly);
        }
    }

    /**
     * Screens the league's free agents for the claim deadline given.
     *
     * <p>The latest week's gaps join the board's missing data. While a
     * required feed lacks the week, a free agent only popularity lanes
     * name stays in the pool labelled {@link #TRENDING_ONLY}, so the
     * model and the user can tell attention from usage that has not
     * arrived.
     *
     * <p>Side effects: every lane's reads, and the roster needs' read
     * and store of completed weeks.
     *
     * @param preference what the user asked of this claim round's board
     * @return unavailable when there is no player directory or no
     *         roster for the user, since without both no player can be
     *         known to be a free agent
     */
    public SourceResult<Screen> screen(LeagueWeek league, ZonedDateTime deadline,
            RoundPreference preference, Instant now,
            LatestWeekData.Report latestWeek) {
        Optional<PlayerDirectory> players = directory.read();
        if (players.isEmpty()) {
            return new SourceResult.Unavailable<>("waivers",
                    "there is no player directory yet, so no free agent is known");
        }
        Optional<RosterSnapshot> user = league.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .findFirst();
        if (user.isEmpty()) {
            return new SourceResult.Unavailable<>("waivers",
                    "the Snapshot holds no roster for the user");
        }
        List<DirectoryPlayer> freeAgents = freeAgents(players.get(), league);

        List<DirectoryPlayer> eligible = freeAgents.stream()
                .filter(preference::screens)
                .toList();

        EvidenceLane.Inputs inputs = new EvidenceLane.Inputs(league, user.get(), now);
        List<LaneRead> reads = lanes.stream()
                .map(lane -> new LaneRead(lane, lane.read(inputs)))
                .toList();
        Pool pool = pool(freeAgents, eligible, reads, latestWeek);
        Map<String, List<String>> reasons = pool.reasons();
        Set<String> trendingOnly = pool.trendingOnly();
        Map<String, DirectoryPlayer> candidates = pool.candidates();
        List<Candidate> screened = freeAgents.stream()
                .map(player -> screened(player, reasons.get(player.playerId()),
                        trendingOnly.contains(player.playerId()), preference))
                .toList();

        SourceResult<RosterNeeds> rosterNeeds = needs.assess(league);
        FaabEvidence.Bidding bidding = faab.read(league, user.get(), players.get().players(),
                candidates.values(), now);
        List<String> gaps = new ArrayList<>(latestWeek.gaps());
        reads.forEach(read -> gaps.addAll(read.read().gaps()));
        gaps.addAll(bidding.gaps());
        if (rosterNeeds instanceof SourceResult.Unavailable<RosterNeeds> down) {
            gaps.add("the roster needs could not be worked out: " + down.reason());
        }

        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(boardItem(league, deadline, now, gaps));
        preference.stated().ifPresent(stated -> evidence.add(preferenceItem(stated)));
        if (rosterNeeds instanceof SourceResult.Ok<RosterNeeds> ok) {
            evidence.add(needsItem(league, ok.value(), now));
        }
        evidence.add(rosterItem(league, user.get(), now));
        evidence.addAll(bidding.league());
        for (DirectoryPlayer player : candidates.values()) {
            evidence.add(candidateItem(player, reasons.get(player.playerId()),
                    trendingOnly.contains(player.playerId()), now));
            reads.forEach(read -> evidence.addAll(read.read().evidence(player)));
            evidence.add(bidding.candidates().get(player.playerId()));
        }

        EvidenceBundle bundle = new EvidenceBundle(TASK, evidence,
                bundlePlayers(league, user.get(), candidates.values()), List.of(), true,
                Optional.empty(), wholeBoardLength(candidates.size()), bidding.policy().budget(),
                List.of(), List.of());
        Map<String, Object> comparisons = new LinkedHashMap<>();
        comparisons.put("lanes", pool.picksByLane());
        comparisons.put("latestWeek", latestWeek);
        comparisons.put("rosterNeeds", rosterNeeds instanceof SourceResult.Ok<RosterNeeds> ok
                ? ok.value() : null);
        comparisons.put("bidPolicy", bidding.policy().toString());
        return new SourceResult.Ok<>(new Screen(bundle, candidates, screened,
                OttoJson.MAPPER.valueToTree(comparisons), user.get(), bidding, trendingOnly));
    }

    /** One lane and what it read for this board. */
    private record LaneRead(EvidenceLane lane, EvidenceLane.Read read) {
    }

    /**
     * The board's pool: the free agents any lane picks, best reasons in
     * lane order.
     *
     * @param candidates the picked free agents, by player id, in the order first picked
     * @param reasons each picked player's reasons, one per lane that picked him
     * @param picksByLane each lane's picks and reasons, by {@link EvidenceLane#name}
     * @param trendingOnly the picked players only popularity lanes name
     *        while the latest week is incomplete
     */
    private record Pool(Map<String, DirectoryPlayer> candidates,
            Map<String, List<String>> reasons, Map<String, Map<String, String>> picksByLane,
            Set<String> trendingOnly) {
    }

    private static Pool pool(List<DirectoryPlayer> freeAgents, List<DirectoryPlayer> eligible,
            List<LaneRead> reads, LatestWeekData.Report latestWeek) {
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        Map<String, Map<String, String>> picksByLane = new LinkedHashMap<>();
        Set<String> backedByFootball = new LinkedHashSet<>();
        for (LaneRead read : reads) {
            Map<String, String> picks = read.read().shortlist(eligible);
            picksByLane.put(read.lane().name(), picks);
            picks.forEach((playerId, reason) ->
                    reasons.computeIfAbsent(playerId, id -> new ArrayList<>()).add(reason));
            if (!read.lane().popularity()) {
                backedByFootball.addAll(picks.keySet());
            }
        }
        Set<String> trendingOnly = latestWeek.complete() ? Set.of()
                : reasons.keySet().stream()
                        .filter(playerId -> !backedByFootball.contains(playerId))
                        .collect(Collectors.toSet());
        Map<String, DirectoryPlayer> byId = freeAgents.stream()
                .collect(Collectors.toMap(DirectoryPlayer::playerId, player -> player));
        Map<String, DirectoryPlayer> candidates = new LinkedHashMap<>();
        reasons.keySet().forEach(playerId -> candidates.put(playerId, byId.get(playerId)));
        return new Pool(candidates, reasons, picksByLane, trendingOnly);
    }

    private static Candidate screened(DirectoryPlayer player, List<String> reasons,
            boolean trendingOnly, RoundPreference preference) {
        if (reasons == null) {
            return new Candidate(player.playerId(), player.fullName(), false,
                    preference.leftOutBy(player)
                            .orElse("no lane named him among the free agents worth a look"));
        }
        String named = String.join("; ", reasons);
        return new Candidate(player.playerId(), player.fullName(), true,
                trendingOnly ? TRENDING_ONLY + ": " + named : named);
    }

    /**
     * Returns the players the user may claim in this league: every
     * player at QB, RB, WR and TE with an NFL team who is on no roster
     * in the league, by player id.
     */
    static List<DirectoryPlayer> freeAgents(PlayerDirectory players, LeagueWeek league) {
        Set<String> rostered = league.rosters().stream()
                .flatMap(roster -> roster.players().stream())
                .collect(Collectors.toSet());
        return players.players().values().stream()
                .filter(player -> POSITIONS.contains(player.position()))
                .filter(player -> player.team() != null && !player.team().isBlank())
                .filter(player -> !rostered.contains(player.playerId()))
                .sorted(Comparator.comparing(DirectoryPlayer::playerId))
                .toList();
    }

    /**
     * The longest checked board text: an opening and one entry per
     * candidate, each with its heading and the explanation of its bid's
     * limit. Each part's own limit is checked by the board, so this only
     * bounds the whole.
     */
    private static int wholeBoardLength(int candidates) {
        return (PART_MAX_LENGTH + LIMIT_MAX_LENGTH + HEADING_ALLOWANCE) * (candidates + 1);
    }

    private static List<BundlePlayer> bundlePlayers(LeagueWeek league, RosterSnapshot user,
            Collection<DirectoryPlayer> candidates) {
        Map<String, BundlePlayer> players = new LinkedHashMap<>();
        candidates.forEach(player -> players.put(player.playerId(), new BundlePlayer(
                player.playerId(), player.fullName(),
                projection(league, player.playerId(), player.position()))));
        user.players().forEach(playerId -> players.putIfAbsent(playerId, new BundlePlayer(
                playerId, user.playerNames().getOrDefault(playerId, playerId),
                projection(league, playerId, user.playerPositions().get(playerId)))));
        return List.copyOf(players.values());
    }

    private static Optional<String> projection(LeagueWeek league, String playerId,
            String position) {
        return league.week().projections()
                .flatMap(table -> table.points(playerId, position))
                .map(Facts::points);
    }

    private static EvidenceItem boardItem(LeagueWeek league, ZonedDateTime deadline,
            Instant now, List<String> gaps) {
        Map<String, String> facts = new LinkedHashMap<>();
        league.week().weekNumber().ifPresent(week -> facts.put("week", String.valueOf(week)));
        facts.put("claimDeadline", deadline.toString());
        league.league().rules().format()
                .ifPresent(format -> facts.put("leagueFormat",
                        format.name().toLowerCase(Locale.ROOT)));
        facts.put("startingSlots", league.week().startingSlots().stream()
                .map(Slot::name)
                .collect(Collectors.joining(", ")));
        facts.put("missingData", gaps.isEmpty() ? "none" : String.join("; ", gaps));
        return new EvidenceItem("board", "otto:waiver-board", Optional.empty(), now,
                "the claim round this board is for", facts);
    }

    private static EvidenceItem preferenceItem(WaiverPreference preference) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("asked", preference.phrase());
        facts.put("positions", String.join(", ", preference.positions()));
        facts.put("kind", switch (preference.mode()) {
            case FOCUS -> "a focus: rank these positions first, and still show a clearly "
                    + "better pickup elsewhere with why he beats them";
            case ONLY -> "a filter: only these positions were screened";
        });
        return new EvidenceItem("preference", "otto:chat", Optional.empty(),
                preference.setAt(), "what the user asked for this claim round's board, by chat",
                facts);
    }

    private static EvidenceItem needsItem(LeagueWeek league, RosterNeeds needs,
            Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        RosterNeeds.Coverage coverage = needs.coverage();
        facts.put("openSlots", coverage.openSlots().isEmpty() ? "none"
                : String.join(", ", coverage.openSlots()));
        if (!coverage.positionsThatFill().isEmpty()) {
            facts.put("positionsThatFillAnOpenSlot",
                    String.join(", ", new TreeSet<>(coverage.positionsThatFill())));
        }
        facts.put("sidelined", coverage.sidelined().isEmpty() ? "nobody"
                : coverage.sidelined().stream()
                        .map(out -> name(league, out.playerId()) + " ("
                                + out.reason().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                                + ")")
                        .collect(Collectors.joining(", ")));
        facts.put("weaknessThisWeek", standings(needs.weakness(), "projected"));
        facts.put("pastStarterScoring", needs.weeksScored() == 0 ? "no completed week yet"
                : standings(needs.pastScoring(), "per week from his starters over "
                        + needs.weeksScored() + " completed weeks"));
        if (!needs.notes().isEmpty()) {
            facts.put("notes", String.join("; ", needs.notes()));
        }
        return new EvidenceItem("needs", "otto:roster-needs", Optional.empty(), now,
                "where the user's roster needs help, slot by slot against the league",
                facts);
    }

    private static String standings(List<RosterNeeds.SlotStanding> standings, String basis) {
        if (standings.isEmpty()) {
            return Facts.UNKNOWN;
        }
        return standings.stream()
                .map(standing -> "%s: %s, %s, league median %s".formatted(standing.slot(),
                        standing.points().map(points -> Facts.points(points) + " points "
                                + basis).orElse("points unknown"),
                        standing.rank().map(rank -> "ranked %d of %d".formatted(rank,
                                standing.rankedTeams())).orElse("unranked"),
                        standing.leagueMedian().map(Facts::points).orElse(Facts.UNKNOWN)))
                .collect(Collectors.joining("; "));
    }

    private static EvidenceItem rosterItem(LeagueWeek league, RosterSnapshot user,
            Instant now) {
        Set<String> starters = new LinkedHashSet<>(user.starters());
        Map<String, String> facts = new LinkedHashMap<>();
        for (String playerId : user.players()) {
            String role = user.reserve().contains(playerId) ? "on IR"
                    : user.taxi().contains(playerId) ? "on the taxi squad"
                    : starters.contains(playerId) ? "starting" : "on the bench";
            facts.put(name(league, playerId), "%s, %s, %s, %s".formatted(
                    user.playerPositions().getOrDefault(playerId, Facts.UNKNOWN),
                    user.playerTeams().getOrDefault(playerId, Facts.UNKNOWN), role,
                    designation(user.playerHealth().getOrDefault(playerId,
                            PlayerHealth.ACTIVE))));
        }
        return new EvidenceItem("roster", "sleeper:rosters",
                Optional.of(league.snapshot().at()), league.snapshot().at(),
                "the user's roster, for roster fit", facts);
    }

    private static EvidenceItem candidateItem(DirectoryPlayer player, List<String> reasons,
            boolean trendingOnly, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("fullName", player.fullName());
        facts.put("position", player.position());
        facts.put("nflTeam", player.team());
        facts.put("designation", designation(player.health()));
        facts.put("whyOnTheBoard", String.join("; ", reasons));
        if (trendingOnly) {
            facts.put("usageThisWeek", TRENDING_ONLY + ": only trending adds name him, and "
                    + "they measure attention, never usage");
        }
        return new EvidenceItem("candidate-" + player.playerId(), "sleeper:players",
                Optional.empty(), now, "a free agent the user may claim", facts);
    }

    private static String name(LeagueWeek league, String playerId) {
        return league.rosters().stream()
                .map(roster -> roster.playerNames().get(playerId))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(playerId);
    }

    private static String designation(PlayerHealth health) {
        PlayerHealth known = health == null ? PlayerHealth.ACTIVE : health;
        return known.name().replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
