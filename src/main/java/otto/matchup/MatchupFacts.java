package otto.matchup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.advice.Ordinal;
import otto.nflverse.NextGenColumn;
import otto.nflverse.NextGenStats;
import otto.nflverse.NflverseFeed;
import otto.nflverse.NflverseStore;
import otto.nflverse.PbpParticipation;
import otto.nflverse.PlayerIdMap;
import otto.nflverse.RushingAdvancedStats;
import otto.nflverse.Schedule;

/**
 * Turns the stored matchup sources into evidence items the model may
 * quote: a player's opponent this week from the stored schedule, how
 * that defense ranks against his position, which of its starters
 * cannot play, how it covered last season, how the player himself
 * ranks, his targets when his team is blitzed, a runner's yards after
 * contact and broken tackles, his share of his team's dropbacks last
 * season, his Next Gen Stats, and how hard the rest of his team's
 * schedule is.
 *
 * <p>Code computes every value and rank here; the model only combines
 * and explains them, and {@code AdviceCheck} holds every number and
 * rank it writes to these items. Each ranked item carries its value,
 * its rank written as an ordinal such as "3rd", the rank from the
 * bottom, who the rank runs over, which way rank 1 runs, its
 * {@link Tone} for the player as a "matchup" fact, its sample and the
 * weeks it covers. Ranks follow the analyst's convention: 1st is the
 * toughest defense and the hardest remaining schedule, and the most of
 * a player stat. A source Otto has not stored, or a player
 * a source does not rank, yields no item, so a missing value never
 * reads as zero.
 */
@Component
public class MatchupFacts {

    private static final String PREFIX = "matchup-";

    private static final int MOST_USED_SHELLS = 2;

    private final MatchupSourcesFactory sources;

    public MatchupFacts(MatchupSourcesFactory sources) {
        this.sources = sources;
    }

    /**
     * Starts reading the matchup sources for one week of advice. The
     * returned {@link Week} reads through one {@link MatchupSources}, so
     * each document is read and each derived model built at most once
     * however many players it is asked about, and every fact agrees with
     * the same copy of each source. Build one per piece of advice and drop
     * it after.
     *
     * @param week the NFL week the advice is for; empty when it is not
     *        known, which leaves out every fact that needs an opponent
     * @param now when the sources are read, the items' retrieval time
     */
    public Week forWeek(OptionalInt week, Instant now) {
        return new Week(week, now, sources.open());
    }

    /**
     * The matchup sources as read for one week of advice. A Week keeps
     * what it has read and computed, and is not safe for use by more than
     * one thread at a time.
     */
    public static final class Week {

        private final OptionalInt week;
        private final Instant now;
        private final MatchupSources sources;
        private final NflverseStore store;

        private Week(OptionalInt week, Instant now, MatchupSources sources) {
            this.week = week;
            this.now = now;
            this.sources = sources;
            this.store = sources.store();
        }

        /** Returns true when a schedule is stored, so an opponent can be known. */
        public boolean hasSchedule() {
            return schedule().isPresent();
        }

        /**
         * Returns every matchup fact Otto holds for one player. Each item's
         * id is {@code matchup-<fact>-<playerId>}, unique to the fact and
         * the player.
         *
         * <p>Side effects: reads stored nflverse documents and the player
         * directory, at most once each per {@link Week}.
         *
         * @param playerId the Sleeper player id
         * @param fullName his name, as the advice writes it
         * @param position his position, as Sleeper writes it, or null when
         *        the Player Directory does not know it
         * @param team his NFL team in Sleeper's codes, or null for a free agent
         * @return empty for an unknown position or one no matchup fact covers
         */
        public List<EvidenceItem> about(String playerId, String fullName, String sleeperPosition,
                String team) {
            return subject(playerId, fullName, sleeperPosition, team)
                    .map(subject -> facts(subject, List.of(
                            this::opponentItem,
                            each -> defenseRanks(each, List.of(DefenseStat.values())),
                            MatchupFact.single(this::defensiveStarters),
                            MatchupFact.single(this::lastSeasonCoverage),
                            each -> playerRanks(each, each.position().playerStats()),
                            MatchupFact.single(this::targetsAgainstTheBlitz),
                            MatchupFact.single(this::lastSeasonDropbacks),
                            MatchupFact.single(this::rushingContact),
                            MatchupFact.single(this::nextGenStats),
                            MatchupFact.single(this::restOfSeason))))
                    .orElse(List.of());
        }

        /**
         * Returns the short form of {@link #about} for a player who is one
         * option among many, such as every playable bench player for a
         * slot: his opponent, how that defense ranks in fantasy points
         * allowed to his position, and how he ranks on his position's lead
         * usage stat. It keeps a bundle with many options small.
         *
         * <p>Side effects: as {@link #about}.
         */
        public List<EvidenceItem> briefAbout(String playerId, String fullName,
                String sleeperPosition, String team) {
            return subject(playerId, fullName, sleeperPosition, team)
                    .map(subject -> facts(subject, List.of(
                            this::opponentItem,
                            each -> defenseRanks(each, List.of(DefenseStat.POINTS_ALLOWED)),
                            each -> playerRanks(each,
                                    List.of(each.position().playerStats().getFirst())))))
                    .orElse(List.of());
        }

        private static List<EvidenceItem> facts(Subject subject, List<MatchupFact> facts) {
            return facts.stream().flatMap(fact -> fact.about(subject)).toList();
        }

        /** Returns the player as every fact reads him; empty for a position no fact covers. */
        private Optional<Subject> subject(String playerId, String fullName,
                String sleeperPosition, String team) {
            return Position.of(sleeperPosition).map(position -> new Subject(playerId, fullName,
                    position, Optional.ofNullable(team),
                    ids().flatMap(map -> map.gsisFor(playerId)),
                    Optional.ofNullable(team).flatMap(this::game)
                            .map(found -> team.equals(found.home()) ? found.away()
                                    : found.home())));
        }

        /** Returns the team's game in the advice week; empty without a week or schedule. */
        private Optional<Schedule.Game> game(String team) {
            if (week.isEmpty() || schedule().isEmpty()) {
                return Optional.empty();
            }
            Schedule held = schedule().get();
            return held.rows().stream()
                    .filter(row -> row.season().equals(held.season())
                            && row.week() == week.getAsInt())
                    .filter(row -> team.equals(row.home()) || team.equals(row.away()))
                    .findFirst();
        }

        private Stream<EvidenceItem> opponentItem(Subject subject) {
            if (subject.team().isEmpty() || week.isEmpty() || schedule().isEmpty()) {
                return Stream.empty();
            }
            String team = subject.team().get();
            int number = week.getAsInt();
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("week", String.valueOf(number));
            facts.put("team", team);
            game(team).ifPresentOrElse(found -> {
                boolean home = team.equals(found.home());
                facts.put("opponent", home ? found.away() : found.home());
                facts.put("site", home ? "home" : "away");
            }, () -> facts.put("opponent", "none: the schedule lists no game for %s in week %d"
                    .formatted(team, number)));
            return Stream.of(new EvidenceItem(PREFIX + "opponent-" + subject.playerId(),
                    "nflverse:schedules", Optional.of(schedule().get().assetUpdatedAt()), now,
                    "who his team plays this week, from the stored schedule", facts));
        }

        private Stream<EvidenceItem> defenseRanks(Subject subject, List<DefenseStat> stats) {
            if (subject.opponent().isEmpty()) {
                return Stream.empty();
            }
            String defense = subject.opponent().get();
            Position position = subject.position();
            return stats.stream()
                    .filter(stat -> stat.positions().contains(position))
                    .flatMap(stat -> sources.rankings().defenses(stat, position)
                            .flatMap(ranking -> ranking.placement(defense)
                                    .map(placement -> rankedItem(
                                            itemId("defense-" + RankWords.slug(stat), subject),
                                            "%s defense".formatted(defense), ranking, placement,
                                            "how his opponent's defense ranks against %s"
                                                    .formatted(position.plural()))))
                            .stream());
        }

        private Stream<EvidenceItem> playerRanks(Subject subject, List<PlayerStat> stats) {
            if (subject.gsisId().isEmpty()) {
                return Stream.empty();
            }
            String gsisId = subject.gsisId().get();
            return stats.stream()
                    .flatMap(stat -> sources.rankings().players(stat, subject.position())
                            .flatMap(ranking -> ranking.placement(gsisId)
                                    .map(placement -> rankedItem(
                                            itemId("player-" + RankWords.slug(stat), subject),
                                            subject.name(), ranking, placement,
                                            "how %s ranks among %s".formatted(subject.name(),
                                                    subject.position().plural()))))
                            .stream());
        }

        private static String itemId(String fact, Subject subject) {
            return PREFIX + fact + "-" + subject.playerId();
        }

        private EvidenceItem rankedItem(String id, String subject, Ranking ranking,
                Ranking.Placement placement, String scoringBasis) {
            RankedStat stat = ranking.stat();
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("subject", subject);
            facts.put("stat", RankWords.stat(stat, ranking.position()));
            facts.put("value", RankWords.value(stat, placement.value()));
            facts.put(Ordinal.RANK_FACT, Ordinal.of(placement.rank()));
            facts.put(Ordinal.FROM_BOTTOM_FACT, Ordinal.fromBottom(placement.rankFromBottom()));
            facts.put("population", ranking.population().describe());
            facts.put("rankMeaning", RankWords.rankMeaning(stat, ranking.position()));
            facts.put("matchup", RankWords.tone(stat, ranking.position(),
                    ranking.tone(placement)));
            facts.put("sample", RankWords.count(placement.sample(), stat.sampleUnit()));
            facts.put("weeks", RankWords.weeks(ranking.weeks()));
            return new EvidenceItem(id, stat.source(), store.newestPublished(stat.feeds()), now,
                    scoringBasis, facts);
        }

        private Optional<EvidenceItem> defensiveStarters(Subject subject) {
            if (subject.opponent().isEmpty()) {
                return Optional.empty();
            }
            String defense = subject.opponent().get();
            if (!(sources.starters().forTeam(defense)
                    instanceof DefensiveStarters.Answer.Known known)) {
                return Optional.empty();
            }
            String playerId = subject.playerId();
            DefensiveStarters.TeamDefense chart = known.defense();
            List<DefensiveStarters.Starter> out = chart.starters().stream()
                    .filter(DefensiveStarters.Starter::outOrDoubtful)
                    .toList();
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("defense", defense);
            facts.put("startersOutOrDoubtful", out.isEmpty()
                    ? "none of the starters whose designation is known"
                    : out.stream()
                            .map(starter -> "%s (%s, %s)".formatted(starter.player(),
                                    starter.place(), starter.health().name()
                                            .toLowerCase(Locale.ROOT).replace('_', ' ')))
                            .collect(Collectors.joining("; ")));
            facts.put("startersWithUnknownDesignation", "%d of %d".formatted(
                    chart.unseen().size(), chart.starters().size()));
            return Optional.of(new EvidenceItem(PREFIX + "defensive-starters-" + playerId,
                    "nflverse:depth_charts+sleeper:players", Optional.of(chart.chartedAt()), now,
                    "which of his opponent's defensive starters cannot play", facts));
        }

        private Optional<EvidenceItem> lastSeasonCoverage(Subject subject) {
            if (subject.position() == Position.RB || subject.opponent().isEmpty()) {
                return Optional.empty();
            }
            String defense = subject.opponent().get();
            String playerId = subject.playerId();
            return sources.lastSeason().defense(defense).map(fact -> {
                PbpParticipation.DefenseTendencies tendencies = fact.value();
                Map<String, String> facts = new LinkedHashMap<>();
                facts.put("defense", defense);
                facts.put("manCoverageRate", RankWords.percent(tendencies.manRate()));
                facts.put("zoneCoverageRate", RankWords.percent(tendencies.zoneRate()));
                facts.put("pressureRate", RankWords.percent(tendencies.pressureRate()));
                facts.put("mostUsedShells", mostUsedShells(tendencies));
                facts.put("sample", RankWords.count(tendencies.dropbacks(), "dropbacks"));
                lastSeasonWeeks(fact, facts);
                return new EvidenceItem(PREFIX + "last-season-coverage-" + playerId,
                        "nflverse:pbp_participation",
                        newestPublished(store.pbpParticipation().stream()), now,
                        "how his opponent's defense covered last season; last season's "
                                + "figures, not this season's", facts);
            });
        }

        private Optional<EvidenceItem> targetsAgainstTheBlitz(Subject subject) {
            Optional<String> gsisId = subject.gsisId();
            if (subject.position() == Position.QB || subject.team().isEmpty() || gsisId.isEmpty()
                    || sources.blitzRates().isEmpty()) {
                return Optional.empty();
            }
            String playerId = subject.playerId();
            String fullName = subject.name();
            String team = subject.team().get();
            BlitzRates rates = sources.blitzRates().get();
            return rates.offense(team).flatMap(offense -> offense.players().stream()
                    .filter(player -> player.gsisId().equals(gsisId.get()))
                    .findFirst()
                    .map(player -> {
                        Map<String, String> facts = new LinkedHashMap<>();
                        facts.put("subject", fullName);
                        facts.put("targetsWhenBlitzed", "%d of his team's %d".formatted(
                                player.targets(), offense.targets()));
                        facts.put("shareOfTargetsWhenBlitzed",
                                RankWords.percent((double) player.targets() / offense.targets()));
                        facts.put("sample", RankWords.count(offense.blitzedDropbacks(),
                                "blitzed dropbacks"));
                        facts.put("weeks", RankWords.weeks(new Ranking.Weeks(rates.season(),
                                rates.priorSeasonFinal(), rates.weeks().getFirst(),
                                rates.weeks().getLast())));
                        return new EvidenceItem(PREFIX + "targets-against-blitz-" + playerId,
                                DefenseStat.BLITZ_RATE.source(),
                                store.newestPublished(DefenseStat.BLITZ_RATE.feeds()),
                                now, "how often he is targeted when his team is blitzed",
                                facts);
                    }));
        }

        private Optional<EvidenceItem> rushingContact(Subject subject) {
            if (subject.position() != Position.RB) {
                return Optional.empty();
            }
            String playerId = subject.playerId();
            String fullName = subject.name();
            List<RushingAdvancedStats.RushLine> lines = sources.rushingContact().runner(playerId);
            Optional<RushingAdvancedStats> stats = store.rushingAdvancedStats();
            int carries = lines.stream().mapToInt(RushingAdvancedStats.RushLine::carries).sum();
            if (carries == 0 || stats.isEmpty()) {
                return Optional.empty();
            }
            int afterContact = lines.stream()
                    .mapToInt(RushingAdvancedStats.RushLine::yardsAfterContact).sum();
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("subject", fullName);
            facts.put("yardsAfterContactPerCarry",
                    String.format(Locale.ROOT, "%.1f", (double) afterContact / carries));
            facts.put("brokenTackles", String.valueOf(lines.stream()
                    .mapToInt(RushingAdvancedStats.RushLine::brokenTackles).sum()));
            facts.put("sample", RankWords.count(carries, "carries"));
            facts.put("weeks", RankWords.weeks(new Ranking.Weeks(stats.get().season(),
                    stats.get().priorSeasonFinal(), lines.getFirst().week(),
                    lines.getLast().week())));
            return Optional.of(new EvidenceItem(PREFIX + "rushing-contact-" + playerId,
                    PlayerStat.YARDS_BEFORE_CONTACT_PER_CARRY.source(),
                    store.newestPublished(PlayerStat.YARDS_BEFORE_CONTACT_PER_CARRY.feeds()),
                    now, "what he makes after first contact this season", facts));
        }

        private Optional<EvidenceItem> lastSeasonDropbacks(Subject subject) {
            if (subject.position() == Position.QB) {
                return Optional.empty();
            }
            String playerId = subject.playerId();
            String fullName = subject.name();
            List<LastSeasonFact<PbpParticipation.DropbackShare>> shares =
                    sources.lastSeason().player(playerId);
            if (shares.isEmpty()) {
                return Optional.empty();
            }
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("subject", fullName);
            facts.put("dropbackShare", shares.stream()
                    .map(fact -> "%s %s".formatted(fact.value().team(),
                            RankWords.percent(fact.value().share())))
                    .collect(Collectors.joining("; ")));
            facts.put("sample", shares.stream()
                    .map(fact -> "on the field for %d of %s in %s".formatted(
                            fact.value().onFieldDropbacks(),
                            RankWords.count(fact.value().teamDropbacks(), "team dropbacks"),
                            RankWords.count(fact.value().games(), "games")))
                    .collect(Collectors.joining("; ")));
            lastSeasonWeeks(shares.getFirst(), facts);
            return Optional.of(new EvidenceItem(PREFIX + "last-season-dropback-share-" + playerId,
                    "nflverse:pbp_participation", newestPublished(store.pbpParticipation().stream()),
                    now, "his share of his team's dropbacks last season; last season's "
                            + "figures, not this season's", facts));
        }

        private Optional<EvidenceItem> nextGenStats(Subject subject) {
            String playerId = subject.playerId();
            String fullName = subject.name();
            NextGenStats.Kind kind = subject.position().nextGenKind();
            Optional<NextGenStats> board = store.nextGenStats(kind);
            Optional<NextGenStats.Line> line = board.flatMap(stats -> ids()
                    .flatMap(map -> stats.seasonTotal(map, playerId, stats.season())));
            if (line.isEmpty()) {
                return Optional.empty();
            }
            Map<NextGenColumn, Double> metrics = line.get().metrics();
            Map<String, String> facts = new LinkedHashMap<>();
            facts.put("subject", fullName);
            NextGenMetric.of(kind).forEach(metric -> facts.put(metric.label(),
                    metrics.containsKey(metric.column())
                            ? String.format(Locale.ROOT, "%.2f", metrics.get(metric.column()))
                            : "unknown"));
            NextGenMetric.Sample sample = NextGenMetric.Sample.of(kind);
            facts.put("sample", metrics.containsKey(sample.column())
                    ? RankWords.count((int) Math.round(metrics.get(sample.column())),
                            sample.unit())
                    : "unknown");
            facts.put("weeks", "%s season to date".formatted(line.get().season()));
            facts.put("population", "Next Gen Stats lists only players who clear its "
                    + "qualifying minimum; no rank is given");
            return Optional.of(new EvidenceItem(PREFIX + "next-gen-" + playerId,
                    "nflverse:nextgen_stats", board.map(NextGenStats::assetUpdatedAt), now,
                    "his Next Gen Stats tracking figures this season", facts));
        }

        private Optional<EvidenceItem> restOfSeason(Subject subject) {
            if (subject.team().isEmpty()) {
                return Optional.empty();
            }
            String team = subject.team().get();
            Position position = subject.position();
            return sources.rankings().schedules(ScheduleStat.REMAINING_SCHEDULE, position)
                    .flatMap(ranking -> ranking.placement(team)
                            .map(placement -> rankedItem(itemId("rest-of-season", subject),
                                    team, ranking, placement,
                                    "how hard the rest of his team's schedule is for %s, "
                                            .formatted(position.plural())
                                            + "its opponents rated on "
                                            + sources.scheduleStrength().orElseThrow().basis())));
        }

        private Optional<Schedule> schedule() {
            return store.schedule();
        }

        private Optional<PlayerIdMap> ids() {
            return store.playerIds();
        }
    }

    /**
     * One player as every matchup fact reads him.
     *
     * @param playerId his Sleeper id
     * @param name his name, as the advice writes it
     * @param team his NFL team in Sleeper's codes; empty for a free agent
     * @param gsisId his nflverse id; empty when the id map cannot reach him
     * @param opponent the defense he faces in the advice week; empty when
     *        the week, the schedule or a game that week is not known
     */
    private record Subject(String playerId, String name, Position position,
            Optional<String> team, Optional<String> gsisId, Optional<String> opponent) {
    }

    /** One kind of matchup fact: the items it holds about a player, often none. */
    @FunctionalInterface
    private interface MatchupFact {

        Stream<EvidenceItem> about(Subject subject);

        /** Returns a fact that holds at most one item about a player. */
        static MatchupFact single(Function<Subject, Optional<EvidenceItem>> fact) {
            return subject -> fact.apply(subject).stream();
        }
    }

    private static String mostUsedShells(PbpParticipation.DefenseTendencies tendencies) {
        if (tendencies.shells().isEmpty()) {
            return "none charted";
        }
        return tendencies.shells().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MOST_USED_SHELLS)
                .map(shell -> "%s %s".formatted(shell.getKey(),
                        RankWords.percent(tendencies.shellRate(shell.getKey()))))
                .collect(Collectors.joining(", "));
    }

    /**
     * Writes the season a last-season fact is from, and how far this
     * season has come beside it under its own fact, so the evidence's
     * identity holds still as games finish.
     */
    private static void lastSeasonWeeks(LastSeasonFact<?> fact, Map<String, String> facts) {
        facts.put("weeks", fact.label());
        fact.progress().ifPresent(progress ->
                facts.put(EvidenceBundle.SEASON_PROGRESS_FACT, progress));
    }

    private static Optional<Instant> newestPublished(Stream<? extends NflverseFeed> feeds) {
        return feeds.map(NflverseFeed::assetUpdatedAt).max(Instant::compareTo);
    }
}
