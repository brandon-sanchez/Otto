package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.advice.Ordinal;
import otto.directory.DirectoryPlayer;
import otto.matchup.MatchupSources;
import otto.matchup.NextGenMetric;
import otto.matchup.PlayerStat;
import otto.matchup.Position;
import otto.matchup.RankWords;
import otto.matchup.Ranking;
import otto.nflverse.FeedId;
import otto.nflverse.NextGenColumn;
import otto.nflverse.NextGenStats;
import otto.nflverse.PlayerUsage;
import otto.nflverse.RushingAdvancedStats;
import otto.nflverse.UsageTotals;

/**
 * Writes one player's stats over a span as the evidence bundle the
 * model explains them from: his snaps, targets, carries, air yards,
 * red-zone, end-zone and first-read looks, expected points, the Next Gen
 * and PFR figures his position has, and his season rank among his
 * position on each ranked stat. A stat no stored source holds is written
 * as "unknown" with the reason, never as zero.
 */
final class PlayerStatsEvidence {

    static final int MAX_LENGTH = 900;

    private static final String PREFIX = "stats-";

    private static final String UNKNOWN = "unknown: ";

    private static final String WHAT_OTTO_READS = "snaps and snap share, targets and target "
            + "share, carries and carry share, air yards, air-yards share and WOPR from "
            + "nflverse; red-zone and end-zone looks from the play-by-play; first-read targets "
            + "from FTN charting; expected fantasy points from ffopportunity; Next Gen Stats "
            + "tracking figures; and, for running backs, PFR's yards before and after contact "
            + "and broken tackles";

    private static final String NOT_IN_FREE_DATA = "route participation, routes run and "
            + "yards per route run, which no free source Otto reads publishes";

    private PlayerStatsEvidence() {
    }

    /**
     * Everything one answer is written from.
     *
     * @param team his team over the span, in Sleeper's codes
     * @param games the games he played in the span, oldest first
     * @param didNotPlay the span's weeks his team played and the weekly
     *        stats list him nowhere
     * @param notPublished the span's weeks nflverse has not published his
     *        team's stats for yet
     * @param asked what the user asked about, in his words, or null
     */
    record Subject(DirectoryPlayer player, String gsisId, Position position, String team,
            StatsSpan.Window window, List<PlayerUsage.Game> games, List<Integer> didNotPlay,
            List<Integer> notPublished, String asked) {

        Subject {
            games = List.copyOf(games);
            didNotPlay = List.copyOf(didNotPlay);
            notPublished = List.copyOf(notPublished);
        }

        String id() {
            return player.playerId();
        }

        String name() {
            return player.fullName();
        }
    }

    static EvidenceBundle bundle(Subject subject, MatchupSources sources, String season,
            Instant now) {
        UsageTotals totals = UsageTotals.of(subject.games());
        List<EvidenceItem> items = new ArrayList<>();
        items.add(span(subject, now));
        items.add(snaps(subject, totals, now));
        items.add(volume(subject, totals, now));
        items.add(airYards(subject, totals, now));
        items.add(looks(subject, totals, now));
        items.add(expected(subject, totals, now));
        items.addAll(nextGen(subject, sources, season, now));
        if (subject.position() == Position.RB) {
            items.add(contact(subject, sources, now));
        }
        items.addAll(ranks(subject, sources, now));
        items.add(item("sources", subject, "what Otto can and cannot see", now, Map.of(
                "statsOttoReads", WHAT_OTTO_READS,
                "notInFreeData", NOT_IN_FREE_DATA)));
        String asked = subject.asked() == null || subject.asked().isBlank() ? ""
                : " He asked about: %s.".formatted(subject.asked().strip());
        return new EvidenceBundle("Tell the user %s's stats for %s.%s".formatted(subject.name(),
                subject.window().label(), asked), items,
                List.of(new BundlePlayer(subject.id(), subject.name(), Optional.empty())),
                List.of(), true, Optional.empty(), MAX_LENGTH, Optional.empty(), List.of(),
                List.of());
    }

    private static EvidenceItem span(Subject subject, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("subject", subject.name());
        facts.put("team", subject.team());
        facts.put("weeks", subject.window().label());
        facts.put("gamesPlayed", RankWords.count(subject.games().size(), "games"));
        if (!subject.didNotPlay().isEmpty()) {
            facts.put("didNotPlay", "the weekly stats list him in no game in " + weeks(
                    subject.didNotPlay()));
        }
        if (!subject.notPublished().isEmpty()) {
            facts.put("notPublishedYet", "nflverse has not published %s's stats for %s yet"
                    .formatted(subject.team(), weeks(subject.notPublished())));
        }
        return item("span", subject, "the games these stats cover", now, facts);
    }

    private static EvidenceItem snaps(Subject subject, UsageTotals totals, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        UsageTotals.Part<UsageTotals.Snaps> part = totals.snaps();
        if (part.value() == null) {
            facts.put("snaps", unknown("nflverse's snap-count file", subject, part));
            facts.put("snapShare", unknown("nflverse's snap-count file", subject, part));
        } else {
            facts.put("offensiveSnaps", String.valueOf(part.value().snaps()));
            facts.put(single(subject) ? "snapShare" : "averageSnapShare",
                    RankWords.percent(part.value().meanShare()));
            if (!single(subject)) {
                facts.put("snapShareByWeek", subject.games().stream()
                        .filter(game -> game.snaps() != null)
                        .map(game -> "week %d %s".formatted(game.week(),
                                RankWords.percent(game.snaps().share())))
                        .collect(Collectors.joining(", ")));
            }
            missingWeeks(facts, part);
        }
        return item("snaps", subject, "his share of his team's offensive snaps", now, facts);
    }

    private static EvidenceItem volume(Subject subject, UsageTotals totals, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        UsageTotals.Part<UsageTotals.Volume> part = totals.volume();
        if (part.value() == null) {
            facts.put("targets", unknown("nflverse's weekly stats file", subject, part));
            facts.put("carries", unknown("nflverse's weekly stats file", subject, part));
        } else {
            UsageTotals.Volume volume = part.value();
            facts.put("targets", "%d of %s's %d".formatted(volume.targets(), subject.team(),
                    volume.teamTargets()));
            facts.put("targetShare", share(volume.targets(), volume.teamTargets()));
            if (runs(subject) || volume.carries() > 0) {
                facts.put("carries", "%d of %s's %d".formatted(volume.carries(), subject.team(),
                        volume.teamCarries()));
                facts.put("carryShare", share(volume.carries(), volume.teamCarries()));
            }
            missingWeeks(facts, part);
        }
        return item("volume", subject, "his targets and carries against his team's", now,
                facts);
    }

    private static EvidenceItem airYards(Subject subject, UsageTotals totals, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        UsageTotals.Part<UsageTotals.Looks> looks = totals.looks();
        facts.put("airYards", looks.value() == null
                ? unknown("the play-by-play", subject, looks)
                : String.valueOf(looks.value().airYards()));
        String averaged = single(subject) ? "" : "average of his weekly ";
        facts.put(single(subject) ? "airYardsShare" : "averageAirYardsShare",
                percentOr(totals.airYardsShare().value(), "nflverse's weekly stats give no "
                        + "air-yards share for him over " + subject.window().label()));
        facts.put(single(subject) ? "wopr" : "averageWopr", decimalOr(totals.wopr().value(), 2,
                "nflverse's weekly stats give no WOPR for him over "
                        + subject.window().label()));
        facts.put("meaning", ("air yards are how far his targets travelled past the line; the "
                + "share and WOPR are the %snflverse figures, WOPR being 1.5 times target share "
                + "plus 0.7 times air-yards share").formatted(averaged));
        return item("air-yards", subject, "how far downfield he is targeted", now, facts);
    }

    private static EvidenceItem looks(Subject subject, UsageTotals totals, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        UsageTotals.Part<UsageTotals.Looks> part = totals.looks();
        if (part.value() == null) {
            String unknown = unknown("the play-by-play", subject, part);
            facts.put("redZoneTargets", unknown);
            facts.put("endZoneTargets", unknown);
        } else {
            facts.put("redZoneTargets", String.valueOf(part.value().redZoneTargets()));
            facts.put("endZoneTargets", String.valueOf(part.value().endZoneTargets()));
            if (runs(subject) || part.value().redZoneCarries() > 0) {
                facts.put("redZoneCarries", String.valueOf(part.value().redZoneCarries()));
            }
            missingWeeks(facts, part);
        }
        UsageTotals.Part<Integer> reads = totals.firstReads();
        facts.put("firstReadTargets", reads.value() == null
                ? unknown("FTN's charting", subject, reads)
                : String.valueOf(reads.value()));
        if (reads.value() != null && !reads.missing().isEmpty()) {
            facts.put("firstReadsMissing", "FTN has not charted " + weeks(reads.missing()));
        }
        facts.put("meaning", "red-zone looks come at or inside the opponent's 20; an end-zone "
                + "target is thrown at least as far as the goal line; a first-read target is "
                + "one FTN charted as the quarterback's first read");
        return item("looks", subject, "where his work comes", now, facts);
    }

    private static EvidenceItem expected(Subject subject, UsageTotals totals, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        UsageTotals.Part<UsageTotals.Expected> part = totals.expected();
        if (part.value() == null) {
            String unknown = unknown("ffopportunity", subject, part);
            facts.put("fantasyPoints", unknown);
            facts.put("expectedFantasyPoints", unknown);
        } else {
            double points = part.value().points();
            double expected = part.value().expectedPoints();
            facts.put("fantasyPoints", decimal(points, 1));
            facts.put("expectedFantasyPoints", decimal(expected, 1));
            facts.put("difference", "%s %s than expected".formatted(
                    decimal(Math.abs(points - expected), 1), points >= expected ? "more"
                            : "fewer"));
            missingWeeks(facts, part);
        }
        facts.put("scoring", "full PPR as ffopportunity scores it, which may differ from his "
                + "league's scoring");
        return item("expected", subject, "what he scored against what his opportunities were "
                + "worth", now, facts);
    }

    private static List<EvidenceItem> nextGen(Subject subject, MatchupSources sources,
            String season, Instant now) {
        NextGenStats.Kind kind = subject.position().nextGenKind();
        Optional<NextGenStats> board = sources.store().nextGenStats(kind);
        String notListed = "Next Gen Stats lists only players who clear the NFL's qualifying "
                + "minimum, and it did not list %s for ".formatted(subject.name());
        if (board.isEmpty()) {
            return List.of(item("next-gen", subject, "his Next Gen Stats tracking figures", now,
                    Map.of("nextGenStats", UNKNOWN + "Otto has no Next Gen Stats stored")));
        }
        String gsisId = subject.gsisId();
        return switch (subject.window().reach()) {
            case WEEK -> {
                int week = subject.window().weeks().getFirst();
                yield List.of(nextGenItem("next-gen", subject, kind,
                        weekLine(board.get(), gsisId, season, week),
                        notListed + subject.window().label(), board.get(), now));
            }
            case SEASON -> List.of(nextGenItem("next-gen", subject, kind,
                    board.get().seasonTotals().stream()
                            .filter(line -> line.season().equals(season)
                                    && line.gsisId().equals(gsisId))
                            .findFirst(),
                    notListed + subject.window().label(), board.get(), now));
            case RECENT -> subject.games().stream()
                    .map(game -> nextGenItem("next-gen-week" + game.week(), subject, kind,
                            weekLine(board.get(), gsisId, season, game.week()),
                            notListed + "week " + game.week(), board.get(), now))
                    .toList();
        };
    }

    private static Optional<NextGenStats.Line> weekLine(NextGenStats board, String gsisId,
            String season, int week) {
        return board.week(season, week).stream()
                .filter(line -> line.gsisId().equals(gsisId))
                .findFirst();
    }

    private static EvidenceItem nextGenItem(String fact, Subject subject, NextGenStats.Kind kind,
            Optional<NextGenStats.Line> line, String notListed, NextGenStats board,
            Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        if (line.isEmpty()) {
            facts.put("nextGenStats", UNKNOWN + notListed);
        } else {
            Map<NextGenColumn, Double> metrics = line.get().metrics();
            NextGenMetric.of(kind).forEach(metric -> facts.put(metric.label(),
                    metrics.containsKey(metric.column())
                            ? decimal(metrics.get(metric.column()), 1)
                            : UNKNOWN + "Next Gen Stats left it blank"));
            NextGenMetric.Sample sample = NextGenMetric.Sample.of(kind);
            facts.put("sample", metrics.containsKey(sample.column())
                    ? RankWords.count((int) Math.round(metrics.get(sample.column())),
                            sample.unit())
                    : UNKNOWN + "Next Gen Stats left it blank");
            facts.put("weeks", line.get().week() == 0 ? "%s season to date".formatted(
                    line.get().season()) : "week %d of %s".formatted(line.get().week(),
                    line.get().season()));
        }
        facts.put("population", "Next Gen Stats ranks nobody here; it lists only players who "
                + "clear its qualifying minimum");
        return new EvidenceItem(PREFIX + fact + "-" + subject.id(), "nflverse:nextgen_stats",
                Optional.of(board.assetUpdatedAt()), now, "his Next Gen Stats tracking figures",
                facts);
    }

    private static EvidenceItem contact(Subject subject, MatchupSources sources, Instant now) {
        List<Integer> weeks = subject.window().weeks();
        List<RushingAdvancedStats.RushLine> lines = sources.rushingContact().runner(subject.id())
                .stream()
                .filter(line -> weeks.contains(line.week()))
                .toList();
        int carries = lines.stream().mapToInt(RushingAdvancedStats.RushLine::carries).sum();
        Map<String, String> facts = new LinkedHashMap<>();
        if (carries == 0) {
            facts.put("yardsBeforeContactPerCarry", UNKNOWN + "PFR lists no carry for him in "
                    + subject.window().label());
        } else {
            facts.put("yardsBeforeContactPerCarry", decimal((double) lines.stream()
                    .mapToInt(RushingAdvancedStats.RushLine::yardsBeforeContact).sum()
                    / carries, 1));
            facts.put("yardsAfterContactPerCarry", decimal((double) lines.stream()
                    .mapToInt(RushingAdvancedStats.RushLine::yardsAfterContact).sum()
                    / carries, 1));
            facts.put("brokenTackles", String.valueOf(lines.stream()
                    .mapToInt(RushingAdvancedStats.RushLine::brokenTackles).sum()));
            facts.put("sample", RankWords.count(carries, "carries"));
        }
        return new EvidenceItem(PREFIX + "contact-" + subject.id(),
                PlayerStat.YARDS_BEFORE_CONTACT_PER_CARRY.source(),
                sources.store().newestPublished(List.of(FeedId.RUSHING_ADVANCED_STATS)), now,
                "what he makes before and after first contact, from PFR", facts);
    }

    private static List<EvidenceItem> ranks(Subject subject, MatchupSources sources,
            Instant now) {
        List<PlayerStat> stats = new ArrayList<>(List.of(PlayerStat.SNAP_SHARE));
        stats.addAll(subject.position().playerStats());
        if (subject.position() == Position.RB) {
            stats.add(PlayerStat.CARRIES_PER_GAME);
        }
        return stats.stream()
                .flatMap(stat -> sources.rankings().players(stat, subject.position())
                        .map(ranking -> rankItem(subject, ranking, sources, now)).stream())
                .toList();
    }

    private static EvidenceItem rankItem(Subject subject, Ranking ranking,
            MatchupSources sources, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("subject", subject.name());
        facts.put("stat", RankWords.stat(ranking.stat(), subject.position()) + ", season to date");
        facts.put("weeks", RankWords.weeks(ranking.weeks()));
        Optional<Ranking.Placement> placement = ranking.placement(subject.gsisId());
        if (placement.isPresent()) {
            facts.put("value", RankWords.value(ranking.stat(), placement.get().value()));
            facts.put(Ordinal.RANK_FACT, Ordinal.of(placement.get().rank()));
            facts.put(Ordinal.FROM_BOTTOM_FACT,
                    Ordinal.fromBottom(placement.get().rankFromBottom()));
            facts.put("population", ranking.population().describe());
            facts.put("rankMeaning", RankWords.rankMeaning(ranking.stat(), subject.position()));
            facts.put("sample", RankWords.count(placement.get().sample(),
                    ranking.stat().sampleUnit()));
            PlayerStat stat = (PlayerStat) ranking.stat();
            facts.put("quoted", RankWords.placed(stat, placement.get()));
            sources.rankings().notableOverall(stat, subject.gsisId(), subject.position())
                    .ifPresent(overall -> facts.put("amongAllPlayers", overall));
        } else {
            facts.put("noRank", ranking.unranked().stream()
                    .filter(entry -> entry.id().equals(subject.gsisId()))
                    .findFirst()
                    .map(_ -> "no stored source holds this stat for him")
                    .orElse("no stored source lists him for this stat"));
        }
        return new EvidenceItem(PREFIX + "rank-" + slug(ranking) + "-" + subject.id(),
                ranking.stat().source(), Optional.empty(), now,
                "where he ranks among %s this season".formatted(subject.position().plural()),
                facts);
    }

    private static String slug(Ranking ranking) {
        return ranking.stat().name().toLowerCase(Locale.ROOT).replace('_', '.');
    }

    private static boolean single(Subject subject) {
        return subject.window().reach() == StatsSpan.Reach.WEEK;
    }

    private static boolean runs(Subject subject) {
        return subject.position() == Position.RB || subject.position() == Position.QB;
    }

    private static String unknown(String source, Subject subject, UsageTotals.Part<?> part) {
        return UNKNOWN + "%s does not hold %s's %s".formatted(source, subject.team(),
                part.missing().isEmpty() ? subject.window().label() : weeks(part.missing()));
    }

    private static void missingWeeks(Map<String, String> facts, UsageTotals.Part<?> part) {
        if (!part.missing().isEmpty()) {
            facts.put("countedWeeks", weeks(part.counted()));
            facts.put("weeksNotHeld", weeks(part.missing()));
        }
    }

    private static String weeks(List<Integer> weeks) {
        List<String> numbers = weeks.stream().map(String::valueOf).toList();
        return (numbers.size() == 1 ? "week " : "weeks ") + String.join(", ", numbers);
    }

    private static String share(int part, int whole) {
        return whole == 0 ? UNKNOWN + "his team had none" : RankWords.percent((double) part
                / whole);
    }

    private static String percentOr(OptionalDouble share, String unknown) {
        return share.isPresent() ? RankWords.percent(share.getAsDouble()) : UNKNOWN + unknown;
    }

    private static String decimalOr(OptionalDouble value, int places, String unknown) {
        return value.isPresent() ? decimal(value.getAsDouble(), places) : UNKNOWN + unknown;
    }

    private static String decimal(double value, int places) {
        return String.format(Locale.ROOT, "%." + places + "f", value);
    }

    private static EvidenceItem item(String fact, Subject subject, String basis, Instant now,
            Map<String, String> facts) {
        return new EvidenceItem(PREFIX + fact + "-" + subject.id(), "nflverse",
                Optional.empty(), now, basis, facts);
    }
}
