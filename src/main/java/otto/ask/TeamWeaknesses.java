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

import otto.advice.AdviceAnswer;
import otto.advice.AdviceCheck;
import otto.advice.AdviceForm;
import otto.advice.AdvicePrompt;
import otto.advice.AnswerDelivery;
import otto.advice.BundlePlayer;
import otto.advice.CheckedAdvice;
import otto.advice.EvidenceBundle;
import otto.advice.FriendVoice;
import otto.advice.RankConvention;
import otto.advice.ToolAnswer;
import otto.ceiling.Ceiling;
import otto.directory.DirectoryPlayer;
import otto.directory.PlayerDirectory;
import otto.directory.PlayerDirectoryStore;
import otto.directory.PlayerHealth;
import otto.habits.HabitLean;
import otto.habits.HabitService;
import otto.league.LeagueShapes;
import otto.league.LeagueWeek;
import otto.market.MarketDay;
import otto.market.MarketFormat;
import otto.market.MarketValues;
import otto.offers.Offer;
import otto.offers.TradeOffers;
import otto.records.RunKind;
import otto.sleeper.SourceResult;
import otto.snapshot.RosterSnapshot;
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
import otto.trade.TradeDeadline;
import otto.trade.TradeDeadlines;

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

    /**
     * The owner's words for every league, read in the one league Otto
     * follows. The chat's front door reads "every league" from the
     * message when several are followed; this reads the model's
     * paraphrase, which is looser.
     */
    private static final Pattern EVERY_LEAGUE =
            Pattern.compile("(?i)\\b(?:all|every|each|both)\\b");

    private static final String INSTRUCTIONS = AdvicePrompt.preamble("about his team",
            AdvicePrompt.Naming.LISTED_BY_FULL_NAME) + RankConvention.ADVICE_RULES + FriendVoice.RULE + QuietHabits.RULE + """

            Write it in four short parts, with a blank line between parts:
            1. One or two short sentences that sum up the team: where it is strong and
               where it is thin, and its direction from "direction" with that item's
               reason. When "leagues" is present, open by saying it.
            2. One line for each position in "position-" items, in the order QB, RB, WR,
               TE: its "line" fact word for word. Add a short clause after it only when
               the market shape adds something, such as no WR1.
            3. One short sentence on the lineup's ceiling from "ceiling".
            4. Up to three moves from the "suggestion-" items, in order, each as a line
               starting with "• " and its short reason. For a trade, say which manager to
               send it to ("sendTo") in bold, what he gives and gets, and in one clause
               why the other manager would say yes, from its "why" facts and its
               "playoffWeeks" fact when it has one. Offer none that the evidence lacks.
               When "trade-deadline" is present, say trades are closed and point to waivers.
            When "direction" has a "question", end the message by asking it.
            When "limits" is present, say each limit plainly in one sentence where it
            matters. Never write a confidence label such as High or Medium. Stay under
            "maxLength" characters.
            """ + AdvicePrompt.answerShape(AdvicePrompt.Naming.LISTED_BY_FULL_NAME);

    private static final CheckedAdvice.Fallbacks FALLBACKS = new CheckedAdvice.Fallbacks(
            "I cannot reach my language model to explain your team right now",
            "I worked out your team's strengths and weaknesses, but I could not check my "
                    + "explanation against the numbers, so I am holding it back rather than "
                    + "risk a wrong figure");

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
    private final LeagueShapes shapes;
    private final PlayerDirectoryStore directory;
    private final MarketValues market;
    private final TeamDirections directions;
    private final TradeOffers offers;
    private final HabitService habits;
    private final TradeDeadlines deadlines;
    private final CheckedAdvice advice;
    private final AdviceForm<AdviceAnswer> form;

    public TeamWeaknesses(UserWeekLoader loader, RestOfSeasonProjections restOfSeason,
            TeamProfiler profiler, LeagueShapes shapes, PlayerDirectoryStore directory,
            MarketValues market, TeamDirections directions, TradeOffers offers,
            HabitService habits, TradeDeadlines deadlines, CheckedAdvice advice,
            AdviceCheck check) {
        this.loader = loader;
        this.restOfSeason = restOfSeason;
        this.profiler = profiler;
        this.shapes = shapes;
        this.directory = directory;
        this.market = market;
        this.directions = directions;
        this.offers = offers;
        this.habits = habits;
        this.deadlines = deadlines;
        this.advice = advice;
        this.form = AdviceForm.of(INSTRUCTIONS, (bundle, reply) ->
                QuietHabits.failHabitMentions(check.check(bundle, reply)));
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
     * value reads for every roster, the habit capture's reads,
     * one or two model calls, and one Decision Record of kind
     * {@link RunKind#TEAM_WEAKNESSES} whatever the outcome.
     *
     * @param leagueReference the league the user named; null or blank for
     *        the league in the conversation, or words such as "all of
     *        them" for every league he has. With one league followed,
     *        "all" answers that one and says so, and a league Otto does
     *        not follow is named back rather than guessed.
     * @param pick how the chat settled the league in scope; the
     *        reference is read only when it is {@link LeaguePick#ONLY_LEAGUE}
     * @param delivery how a checked message reaches the user, which the
     *        Decision Record keeps as its outcome
     * @return the checked message, or why there is none in plain words
     */
    public ToolAnswer<Answer> answer(String leagueReference, LeaguePick pick, Instant now,
            AnswerDelivery delivery) {
        return ToolAnswer.from(loader.leagueWeek(),
                league -> answer(league, leagueReference, pick, now, delivery));
    }

    private ToolAnswer<Answer> answer(LeagueWeek league, String reference, LeaguePick pick,
            Instant now, AnswerDelivery delivery) {
        String name = league.league().name();
        boolean everyLeague = pick == LeaguePick.ONLY_LEAGUE && reference != null
                && EVERY_LEAGUE.matcher(reference).find();
        if (pick == LeaguePick.ONLY_LEAGUE && !everyLeague && !names(reference, name)) {
            return ToolAnswer.unavailable(("I follow one league, %s, and no other, so I "
                    + "cannot read \"%s\"").formatted(name, reference));
        }
        Optional<RosterSnapshot> mine = league.userRoster()
                .filter(roster -> !roster.players().isEmpty());
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
            Instant now, AnswerDelivery delivery) {
        String name = league.league().name();
        Map<String, Double> perGame = TeamProfiler.perGame(season);
        LeagueProfile profile = profiler.profile(league, shapes.shape(league), perGame);
        TeamProfile user = profile.user().orElseThrow();
        Optional<String> tradesClosed = deadlines.assess(league)
                .flatMap(TradeDeadline::closedBecause);
        HabitLean lean = habits.lean(league.league(), league.snapshot(), league.week(), now);
        List<WaiverSuggestion> waivers = WeaknessSuggestions.of(profile,
                rated(freeAgents, perGame, league), lean.depthAt());
        TeamWeaknessEvidence.Facts facts = new TeamWeaknessEvidence.Facts(name, profile, user,
                waivers, tradesClosed.isEmpty() ? trades(league, waivers.size(), lean, now)
                        : List.of(),
                market(league, now), sidelined(mine, user), season.weeks(),
                tradesClosed, limits(mine, user), everyLeague, direction(league, now));
        EvidenceBundle bundle = QuietHabits.add(TeamWeaknessEvidence.bundle(facts, MAX_LENGTH,
                now), lean, now);
        CheckedAdvice.Checked checked = advice.write(bundle, form,
                new CheckedAdvice.Run(RUN_ID_PREFIX, List.of(league.league().leagueId()),
                        RunKind.TEAM_WEAKNESSES, league.league(), List.of(), "explanation"),
                now, delivery);
        if (checked.reachesUser()) {
            facts.direction().ifPresent(direction -> directions.asked(league, direction.user()));
        }
        return checked.answer(message -> new Answer(name, message), FALLBACKS);
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
}
