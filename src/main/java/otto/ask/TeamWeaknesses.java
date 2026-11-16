package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.OttoProperties;
import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdviceResult;
import otto.advice.AdviceWriter;
import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;
import otto.advice.RankConvention;
import otto.ceiling.Ceiling;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.habits.HabitLean;
import otto.habits.HabitService;
import otto.market.MarketDay;
import otto.market.MarketFormat;
import otto.market.MarketValues;
import otto.offers.Offer;
import otto.offers.TradeOffers;
import otto.records.DecisionRecord;
import otto.records.Outcome;
import otto.records.RunIds;
import otto.records.RunKind;
import otto.records.RunOutcomes;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
import otto.storage.OttoJson;
import otto.team.LeagueDirections;
import otto.team.LeagueProfile;
import otto.team.PositionProfile;
import otto.team.RatedPlayer;
import otto.team.TeamDirections;
import otto.team.TeamProfile;
import otto.team.TeamProfiler;
import otto.team.WaiverSuggestion;
import otto.team.WeaknessSuggestions;
import otto.trade.RestOfSeason;
import otto.trade.RestOfSeasonProjections;

/**
 * Answers "what are my team's weaknesses?" in chat, only when the user
 * asks. Code profiles every team in the league position by position
 * from per-game rest-of-season projections, ranks the user's starters
 * and depth against the rest, and picks up to three moves. The model
 * explains it in one message, and {@link AdviceCheck} holds every name,
 * number and rank in that message to the evidence first. The user's
 * costly habits weigh which moves are picked, and the message never
 * names them.
 */
@Component
public class TeamWeaknesses {

    private static final int MAX_LENGTH = 1800;

    private static final String RUN_ID_PREFIX = "team-weaknesses-";

    private static final Pattern EVERY_LEAGUE =
            Pattern.compile("(?i)\\b(?:all|every|each|both)\\b");

    private static final String INSTRUCTIONS = """
            You are Otto, a fantasy football assistant texting your one user about his
            team, the way a knowledgeable friend would.

            The user message is an evidence bundle as JSON. State facts only from the
            evidence bundle: never from memory, never invented. Every number you write
            must appear in the evidence, written as the evidence writes it. Name only
            players listed in "players", and introduce each by full name before using his
            surname alone.
            """ + RankConvention.ADVICE_RULES + FriendVoice.RULE + QuietHabits.RULE + """

            Write plain text in four parts, with a blank line between parts:
            1. Two or three sentences that sum up the team: where it is strong and where it
               is thin, and its direction from "direction" with that item's reason. When
               "leagues" is present, open by saying it.
            2. One short paragraph for each position in "position-" items, in the order
               QB, RB, WR, TE. Say whether it is strong, thin, in surplus or in the middle,
               with the numbers behind that: the starter and depth ranks as "4th of 4",
               and for a surplus how many startable players it has for how many spots. Use
               the market shape when it adds something, such as no WR1.
            3. One sentence on the lineup's ceiling from "ceiling".
            4. Each suggestion in the "suggestion-" items, in order, with its reason. For a
               trade, say which manager to send it to ("sendTo"), what he gives and gets,
               and why the other manager would say yes from its "why" facts. Offer none
               that the evidence lacks. When "trade-deadline" is present, say trades are
               closed and point to waivers.
            When "direction" has a "question", end the message by asking it.
            When "limits" is present, say each limit plainly in one sentence where it
            matters. Never write a confidence label such as High or Medium. Stay under
            "maxLength" characters.

            Answer with only this JSON object:
            {
              "message": "the text the user receives",
              "move": null,
              "players": ["<playerId of every player the message names>"],
              "claims": [{"text": "<one factual statement>", "evidence": ["<evidence id>"]}],
              "comparisons": []
            }
            """;

    private static final Set<PlayerHealth> INJURED = Set.of(PlayerHealth.QUESTIONABLE,
            PlayerHealth.DOUBTFUL, PlayerHealth.OUT, PlayerHealth.PUP, PlayerHealth.IR);

    private static final String INJURY_LIMIT = "Free data carries no injury return dates, "
            + "so I can't say when %s will be back";

    private static final String ROUTES_LIMIT = "Free data has no route participation or "
            + "first-read share this season, so receivers and tight ends are judged on "
            + "projections alone";

    private final UserWeekLoader loader;
    private final RestOfSeasonProjections restOfSeason;
    private final TeamProfiler profiler;
    private final PlayerDirectoryStore directory;
    private final MarketValues market;
    private final TeamDirections directions;
    private final TradeOffers offers;
    private final HabitService habits;
    private final AdviceWriter writer;
    private final RunOutcomes runs;
    private final AdviceForm<AdviceAnswer> form;
    private final String userId;

    public TeamWeaknesses(UserWeekLoader loader, RestOfSeasonProjections restOfSeason,
            TeamProfiler profiler, PlayerDirectoryStore directory, MarketValues market,
            TeamDirections directions, TradeOffers offers, HabitService habits,
            AdviceWriter writer, AdviceCheck check, RunOutcomes runs,
            OttoProperties properties) {
        this.loader = loader;
        this.restOfSeason = restOfSeason;
        this.profiler = profiler;
        this.directory = directory;
        this.market = market;
        this.directions = directions;
        this.offers = offers;
        this.habits = habits;
        this.writer = writer;
        this.runs = runs;
        this.userId = properties.userId();
        this.form = new AdviceForm<>() {
            @Override
            public String instructions() {
                return INSTRUCTIONS;
            }

            @Override
            public AdviceCheck.Verdict<AdviceAnswer> check(EvidenceBundle bundle,
                    String reply) {
                return QuietHabits.failHabitMentions(check.check(bundle, reply));
            }
        };
    }

    /**
     * The checked read of the user's team.
     *
     * @param league the league the answer covers
     * @param message the checked text, which the user receives word for
     *        word unless the chat model gathered other tool results for
     *        the same question
     */
    public record Answer(String league, String message) {
    }

    /**
     * Profiles the user's team against the league and has the model
     * explain it.
     *
     * <p>Side effects: the rest-of-season projection reads, the market
     * value reads for every roster, the habit capture's reads and writes,
     * one or two model calls, and one Decision Record of kind
     * {@link RunKind#TEAM_WEAKNESSES} whatever the outcome.
     *
     * @param leagueReference the league the user named; null or blank for
     *        the league in the conversation, or words such as "all of
     *        them" for every league he has. Otto follows one league, so
     *        "all" answers that one and says so.
     * @param delivery how a checked message reaches the user, which the
     *        Decision Record keeps as its outcome
     * @return the checked message, or why there is none in plain words
     */
    public ToolAnswer<Answer> answer(String leagueReference, Instant now,
            CheckedAnswers.Delivery delivery) {
        return switch (loader.leagueWeek()) {
            case SourceResult.Unavailable<LeagueWeek> unavailable -> ToolAnswer.unavailable(
                    "%s: %s".formatted(unavailable.source(), unavailable.reason()));
            case SourceResult.Ok<LeagueWeek> ok -> answer(ok.value(), leagueReference, now,
                    delivery);
        };
    }

    private ToolAnswer<Answer> answer(LeagueWeek league, String reference, Instant now,
            CheckedAnswers.Delivery delivery) {
        String name = league.league().name();
        boolean everyLeague = reference != null && EVERY_LEAGUE.matcher(reference).find();
        if (!everyLeague && !names(reference, name)) {
            return ToolAnswer.unavailable(("I follow one league, %s, and no other, so I "
                    + "cannot read \"%s\"").formatted(name, reference));
        }
        Optional<RosterSnapshot> mine = league.rosters().stream()
                .filter(RosterSnapshot::userRoster)
                .filter(roster -> !roster.players().isEmpty())
                .findFirst();
        if (mine.isEmpty()) {
            return ToolAnswer.unavailable("the Snapshot holds no players on a roster of yours, "
                    + "so I have no team to read");
        }
        Map<String, DirectoryPlayer> freeAgents = freeAgents(league);
        return switch (price(league, freeAgents)) {
            case SourceResult.Unavailable<RestOfSeason> unavailable ->
                ToolAnswer.unavailable(unavailable.reason());
            case SourceResult.Ok<RestOfSeason> season -> explain(league, mine.get(),
                    freeAgents, season.value(), everyLeague, now, delivery);
        };
    }

    private ToolAnswer<Answer> explain(LeagueWeek league, RosterSnapshot mine,
            Map<String, DirectoryPlayer> freeAgents, RestOfSeason season, boolean everyLeague,
            Instant now, CheckedAnswers.Delivery delivery) {
        String name = league.league().name();
        Map<String, Double> perGame = TeamProfiler.perGame(season);
        LeagueProfile profile = profiler.profile(league, perGame);
        TeamProfile user = profile.user().orElseThrow();
        Optional<Integer> deadline = passedDeadline(league);
        HabitLean lean = habits.lean(league.league(), league.snapshot(), league.week(), now);
        List<WaiverSuggestion> waivers = WeaknessSuggestions.of(profile,
                rated(freeAgents, perGame, league), lean.depthAt());
        TeamWeaknessEvidence.Facts facts = new TeamWeaknessEvidence.Facts(name, profile, user,
                waivers, deadline.isEmpty() ? trades(league, waivers.size(), lean, now)
                        : List.of(),
                market(league, now), sidelined(mine, user), season.weeks(),
                deadline, limits(mine, user), everyLeague, direction(league, now));
        EvidenceBundle bundle = QuietHabits.add(TeamWeaknessEvidence.bundle(facts, MAX_LENGTH,
                now), lean, now);
        AdviceResult<AdviceAnswer> result = writer.write(bundle, form);
        Outcome outcome = runs.record(record(league, bundle, result, now, delivery));
        if (outcome instanceof Outcome.Sent || outcome instanceof Outcome.HandedToChat) {
            facts.direction().ifPresent(direction -> directions.asked(league, direction.user()));
        }
        return switch (outcome) {
            case Outcome.Sent sent -> ToolAnswer.of(new Answer(name, sent.message()));
            case Outcome.HandedToChat handed -> ToolAnswer.of(new Answer(name, handed.message()));
            default -> ToolAnswer.unavailable(withheld(result));
        };
    }

    /**
     * The best trade offers that fit beside the waiver moves, up to
     * {@link WeaknessSuggestions#MOST} suggestions in all. A league the
     * offer search can't price gets waiver moves alone.
     */
    private List<Offer> trades(LeagueWeek league, int waivers, HabitLean lean, Instant now) {
        int room = WeaknessSuggestions.MOST - waivers;
        if (room < 1) {
            return List.of();
        }
        return switch (offers.find(league,
                new TradeOffers.Request(room, 0, Optional.empty(), lean), now)) {
            case SourceResult.Ok<TradeOffers.Ideas> ideas -> ideas.value().offers();
            case SourceResult.Unavailable<TradeOffers.Ideas> _ -> List.of();
        };
    }

    private static boolean names(String reference, String league) {
        if (reference == null || reference.isBlank()) {
            return true;
        }
        String asked = reference.strip().toLowerCase(Locale.ROOT);
        String known = league.toLowerCase(Locale.ROOT);
        return known.contains(asked) || asked.contains(known);
    }

    private Map<String, DirectoryPlayer> freeAgents(LeagueWeek league) {
        Set<String> rostered = new HashSet<>();
        league.rosters().forEach(roster -> Stream.of(roster.players(), roster.reserve(),
                roster.taxi()).forEach(rostered::addAll));
        Map<String, DirectoryPlayer> free = new HashMap<>();
        directory.read().map(PlayerDirectory::players).orElse(Map.of()).values().stream()
                .filter(player -> LeagueProfile.POSITIONS.contains(player.position()))
                .filter(player -> player.team() != null)
                .filter(player -> !rostered.contains(player.playerId()))
                .forEach(player -> free.put(player.playerId(), player));
        return free;
    }

    private SourceResult<RestOfSeason> price(LeagueWeek league,
            Map<String, DirectoryPlayer> freeAgents) {
        Map<String, String> positions = new HashMap<>();
        Map<String, String> teams = new HashMap<>();
        league.rosters().forEach(roster -> {
            positions.putAll(roster.playerPositions());
            teams.putAll(roster.playerTeams());
        });
        freeAgents.values().forEach(player -> {
            positions.put(player.playerId(), player.position());
            teams.put(player.playerId(), player.team());
        });
        return restOfSeason.restOfSeason(league, positions, teams);
    }

    private static List<RatedPlayer> rated(Map<String, DirectoryPlayer> freeAgents,
            Map<String, Double> perGame, LeagueWeek league) {
        return freeAgents.values().stream()
                .filter(player -> perGame.containsKey(player.playerId()))
                .map(player -> new RatedPlayer(player.playerId(), player.fullName(),
                        player.position(), perGame.get(player.playerId()), false,
                        Ceiling.estimate(player.position(), perGame.get(player.playerId()),
                                league.week().scoring()).map(Ceiling::boomChance)))
                .toList();
    }

    private Optional<TeamWeaknessEvidence.Market> market(LeagueWeek league, Instant now) {
        return MarketFormat.neededBy(league.league()).stream().findFirst()
                .flatMap(format -> switch (market.fantasyCalc(format, now)) {
                    case SourceResult.Ok<MarketValues.Listing<MarketDay>> ok -> Optional.of(
                            new TeamWeaknessEvidence.Market(ok.value().values(), format));
                    case SourceResult.Unavailable<MarketValues.Listing<MarketDay>> none ->
                        Optional.empty();
                });
    }

    private Optional<TeamWeaknessEvidence.Direction> direction(LeagueWeek league, Instant now) {
        return switch (directions.read(league, now)) {
            case SourceResult.Ok<LeagueDirections> read -> read.value().user()
                    .map(user -> new TeamWeaknessEvidence.Direction(user, read.value().market()));
            case SourceResult.Unavailable<LeagueDirections> _ -> Optional.empty();
        };
    }

    /**
     * The trade deadline week once it has passed: trades close after the
     * deadline week, so its own week is still open.
     */
    private static Optional<Integer> passedDeadline(LeagueWeek league) {
        return league.league().tradeDeadline()
                .filter(deadline -> league.week().weekNumber().isPresent()
                        && league.week().weekNumber().getAsInt() > deadline);
    }

    private static List<BundlePlayer> sidelined(RosterSnapshot roster, TeamProfile user) {
        Set<String> counted = new HashSet<>();
        user.positions().values().forEach(profile -> profile.players()
                .forEach(player -> counted.add(player.playerId())));
        return Stream.concat(roster.reserve().stream(), roster.taxi().stream())
                .distinct()
                .filter(playerId -> !counted.contains(playerId))
                .filter(playerId -> LeagueProfile.POSITIONS.contains(
                        roster.playerPositions().get(playerId)))
                .map(playerId -> new BundlePlayer(playerId,
                        roster.playerNames().getOrDefault(playerId, playerId), Optional.empty()))
                .toList();
    }

    /**
     * What free data cannot say, kept to the limits that touch this
     * read: return dates when one of his players carries an injury
     * designation, and route data when his receivers or tight ends are
     * thin or in surplus, where a backup's role is the open question.
     */
    private static List<String> limits(RosterSnapshot roster, TeamProfile user) {
        List<String> limits = new ArrayList<>();
        List<String> injured = roster.players().stream()
                .filter(playerId -> LeagueProfile.POSITIONS.contains(
                        roster.playerPositions().get(playerId)))
                .filter(playerId -> INJURED.contains(roster.playerHealth()
                        .getOrDefault(playerId, PlayerHealth.ACTIVE)))
                .map(playerId -> roster.playerNames().getOrDefault(playerId, playerId))
                .toList();
        if (!injured.isEmpty()) {
            limits.add(INJURY_LIMIT.formatted(String.join(" or ", injured)));
        }
        boolean receiversInQuestion = Stream.of("WR", "TE")
                .flatMap(position -> user.at(position).stream())
                .map(PositionProfile::strength)
                .anyMatch(strength -> strength == PositionProfile.Strength.THIN
                        || strength == PositionProfile.Strength.SURPLUS);
        if (receiversInQuestion) {
            limits.add(ROUTES_LIMIT);
        }
        return limits;
    }

    private static String withheld(AdviceResult<AdviceAnswer> result) {
        return switch (result) {
            case AdviceResult.Unavailable<AdviceAnswer> _ ->
                "I cannot reach my language model to explain your team right now";
            default -> "I worked out your team's strengths and weaknesses, but I could not "
                    + "check my explanation against the numbers, so I am holding it back "
                    + "rather than risk a wrong figure";
        };
    }

    private DecisionRecord record(LeagueWeek league, EvidenceBundle bundle,
            AdviceResult<AdviceAnswer> result, Instant now, CheckedAnswers.Delivery delivery) {
        String leagueId = league.league().leagueId();
        String runId = RunIds.stable(RUN_ID_PREFIX, leagueId, bundle.identity(), now.toString());
        return DecisionRecord.forRun(runId, RunKind.TEAM_WEAKNESSES, now, userId, leagueId,
                        OttoJson.MAPPER.valueToTree(league.league()))
                .evidence(bundle.recorded())
                .versions(bundle.versions(form, result))
                .exchanges(result.exchanges())
                .outcome(switch (result) {
                    case AdviceResult.Approved<AdviceAnswer> approved -> switch (delivery) {
                        case UNCHANGED -> new Outcome.Sent(approved.answer().message());
                        case THROUGH_CHAT_MODEL ->
                            new Outcome.HandedToChat(approved.answer().message());
                    };
                    case AdviceResult.Rejected<AdviceAnswer> rejected -> new Outcome.Withheld(
                            "The explanation failed its checks twice: "
                                    + String.join(" ", rejected.failures()));
                    case AdviceResult.Unavailable<AdviceAnswer> unavailable ->
                        new Outcome.Withheld(unavailable.reason());
                });
    }
}
