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

    /** Room for an entry's heading and the break before it, in characters. */
    private static final int HEADING_ALLOWANCE = 100;

    private static final String TASK = "Rank this week's waiver candidates against the user's "
            + "roster needs and write the waiver board.";

    private final PlayerDirectoryStore directory;
    private final RosterNeedsService needs;
    private final List<EvidenceLane> lanes;

    public WaiverEvidence(PlayerDirectoryStore directory, RosterNeedsService needs,
            List<EvidenceLane> lanes) {
        this.directory = directory;
        this.needs = needs;
        this.lanes = List.copyOf(lanes);
    }

    /**
     * One screened board, ready for the model.
     *
     * @param candidates the pool the model may recommend from, by
     *        player id, in screening order
     * @param screened every free agent screened, each kept in the pool
     *        or left out with the reason
     * @param comparisons what code worked out for the run: each lane's
     *        picks and the roster needs, for the Decision Record
     */
    public record Screen(EvidenceBundle bundle, Map<String, DirectoryPlayer> candidates,
            List<Candidate> screened, JsonNode comparisons, RosterSnapshot user) {

        public Screen {
            candidates = Collections.unmodifiableMap(new LinkedHashMap<>(candidates));
            screened = List.copyOf(screened);
        }
    }

    /**
     * Screens the league's free agents for the claim deadline given.
     *
     * <p>Side effects: every lane's reads, and the roster needs' read
     * and store of completed weeks.
     *
     * @param preference the preference the user set for this claim
     *        round, or empty when he set none
     * @return unavailable when there is no player directory or no
     *         roster for the user, since without both no player can be
     *         known to be a free agent
     */
    public SourceResult<Screen> screen(LeagueWeek league, ZonedDateTime deadline,
            Optional<WaiverPreference> preference, Instant now) {
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
        Set<String> rostered = league.rosters().stream()
                .flatMap(roster -> roster.players().stream())
                .collect(Collectors.toSet());
        List<DirectoryPlayer> freeAgents = players.get().players().values().stream()
                .filter(player -> POSITIONS.contains(player.position()))
                .filter(player -> player.team() != null && !player.team().isBlank())
                .filter(player -> !rostered.contains(player.playerId()))
                .sorted(Comparator.comparing(DirectoryPlayer::playerId))
                .toList();

        Optional<WaiverPreference> only = preference
                .filter(stated -> stated.mode() == WaiverPreference.Mode.ONLY);
        List<DirectoryPlayer> eligible = only
                .map(stated -> freeAgents.stream()
                        .filter(player -> stated.positions().contains(player.position()))
                        .toList())
                .orElse(freeAgents);

        EvidenceLane.Inputs inputs = new EvidenceLane.Inputs(league, user.get(), now);
        List<EvidenceLane.Read> reads = lanes.stream().map(lane -> lane.read(inputs)).toList();
        Map<String, List<String>> reasons = new LinkedHashMap<>();
        Map<String, Map<String, String>> picksByLane = new LinkedHashMap<>();
        for (int index = 0; index < reads.size(); index++) {
            Map<String, String> picks = reads.get(index).shortlist(eligible);
            picksByLane.put(lanes.get(index).getClass().getSimpleName(), picks);
            picks.forEach((playerId, reason) ->
                    reasons.computeIfAbsent(playerId, id -> new ArrayList<>()).add(reason));
        }
        Map<String, DirectoryPlayer> byId = freeAgents.stream()
                .collect(Collectors.toMap(DirectoryPlayer::playerId, player -> player));
        Map<String, DirectoryPlayer> candidates = new LinkedHashMap<>();
        reasons.keySet().forEach(playerId -> candidates.put(playerId, byId.get(playerId)));

        List<Candidate> screened = freeAgents.stream()
                .map(player -> reasons.containsKey(player.playerId())
                        ? new Candidate(player.playerId(), player.fullName(), true,
                                String.join("; ", reasons.get(player.playerId())))
                        : new Candidate(player.playerId(), player.fullName(), false,
                                leftOut(player, only)))
                .toList();

        SourceResult<RosterNeeds> rosterNeeds = needs.assess(league);
        List<String> gaps = new ArrayList<>();
        reads.forEach(read -> gaps.addAll(read.gaps()));
        if (rosterNeeds instanceof SourceResult.Unavailable<RosterNeeds> down) {
            gaps.add("the roster needs could not be worked out: " + down.reason());
        }

        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(boardItem(league, deadline, now, gaps));
        preference.ifPresent(stated -> evidence.add(preferenceItem(stated)));
        if (rosterNeeds instanceof SourceResult.Ok<RosterNeeds> ok) {
            evidence.add(needsItem(league, ok.value(), now));
        }
        evidence.add(rosterItem(league, user.get(), now));
        for (DirectoryPlayer player : candidates.values()) {
            evidence.add(candidateItem(player, reasons.get(player.playerId()), now));
            reads.forEach(read -> evidence.addAll(read.evidence(player)));
        }

        EvidenceBundle bundle = new EvidenceBundle(TASK, evidence,
                bundlePlayers(league, user.get(), candidates.values()), List.of(), true,
                Optional.empty(), wholeBoardLength(candidates.size()), Optional.empty(), List.of(),
                List.of());
        Map<String, Object> comparisons = new LinkedHashMap<>();
        comparisons.put("lanes", picksByLane);
        comparisons.put("rosterNeeds", rosterNeeds instanceof SourceResult.Ok<RosterNeeds> ok
                ? ok.value() : null);
        return new SourceResult.Ok<>(new Screen(bundle, candidates, screened,
                OttoJson.MAPPER.valueToTree(comparisons), user.get()));
    }

    /**
     * The longest checked board text: an opening and one entry per
     * candidate, each with its heading. Each part's own limit is checked
     * by the board, so this only bounds the whole.
     */
    private static int wholeBoardLength(int candidates) {
        return (PART_MAX_LENGTH + HEADING_ALLOWANCE) * (candidates + 1);
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

    private static String leftOut(DirectoryPlayer player, Optional<WaiverPreference> only) {
        return only.filter(stated -> !stated.positions().contains(player.position()))
                .map(stated -> "the user asked for " + stated.phrase() + " on this round's board")
                .orElse("no lane named him among the free agents worth a look");
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
            Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("fullName", player.fullName());
        facts.put("position", player.position());
        facts.put("nflTeam", player.team());
        facts.put("designation", designation(player.health()));
        facts.put("whyOnTheBoard", String.join("; ", reasons));
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
