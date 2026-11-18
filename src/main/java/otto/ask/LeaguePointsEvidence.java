package otto.ask;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import otto.advice.EvidenceItem;
import otto.advice.Ordinal;
import otto.format.Dot;
import otto.format.TelegramHtml;
import otto.lineup.LeagueScoring;
import otto.matchup.Position;

/**
 * Writes a player's points in one league's own scoring as the evidence
 * item his stats answer leads with, with the lines code wrote for the
 * model to copy: the points he asked for in bold, and his rank at his
 * position as a coloured tier.
 */
final class LeaguePointsEvidence {

    /** The fact holding the sentence that leads with his points. */
    static final String LINE_FACT = "line";

    /** The fact holding the sentence that leads with his points per game. */
    static final String PER_GAME_LINE_FACT = "perGameLine";

    /** The fact holding his rank at his position as a coloured tier. */
    static final String RANK_LINE_FACT = "rankLine";

    private static final String UNKNOWN = "unknown: ";

    private static final String SAT_OUT = "Sleeper's stats show him not playing in week ";

    private static final double FULL_PPR = 1.0;
    private static final double HALF_PPR = 0.5;

    /** The reception premiums the scoring words name, by Sleeper's setting key. */
    private static final Map<String, String> PREMIUMS = Map.of(
            "bonus_rec_te", "tight ends",
            "bonus_rec_rb", "running backs",
            "bonus_rec_wr", "receivers");

    private LeaguePointsEvidence() {
    }

    /**
     * Returns the item for a report: the league, its catch scoring in
     * words, the points over the span with the line the answer leads with,
     * which source scored them, and his rank when the report has one.
     */
    static EvidenceItem item(PlayerStatsEvidence.Subject subject, LeaguePoints.Report report,
            String leagueName, LeagueScoring scoring, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("league", leagueName);
        facts.put("scoring", scoringWords(scoring));
        if (subject.window().reach() == StatsSpan.Reach.WEEK) {
            week(subject, report.games().getFirst(), facts);
        } else {
            span(subject, report, facts);
        }
        if (!report.scored().isEmpty()) {
            facts.put("scoredBy", scoredBy(report.scored()));
        }
        report.rank().ifPresent(rank -> {
            facts.put(Ordinal.RANK_FACT, Ordinal.of(rank.rank()));
            facts.put(RANK_LINE_FACT, rankLine(subject, rank, leagueName, report.total()));
        });
        return new EvidenceItem("stats-league-points-" + subject.id(), "sleeper:stats",
                Optional.empty(), now, "what he scored in %s's own scoring".formatted(leagueName),
                facts);
    }

    /** Returns the item for a league whose scoring settings Sleeper left empty. */
    static EvidenceItem unscored(PlayerStatsEvidence.Subject subject, String leagueName,
            Instant now) {
        return new EvidenceItem("stats-league-points-" + subject.id(), "sleeper:league",
                Optional.empty(), now, "what he scored in %s's own scoring".formatted(leagueName),
                Map.of("league", leagueName, "fantasyPoints", UNKNOWN
                        + "Sleeper sent no scoring settings for this league"));
    }

    private static void week(PlayerStatsEvidence.Subject subject, LeaguePoints.Game game,
            Map<String, String> facts) {
        switch (game) {
            case LeaguePoints.Scored scored -> {
                facts.put("fantasyPoints", points(scored.points()));
                facts.put(LINE_FACT, "%s scored <b>%s</b> points in week %d.".formatted(
                        TelegramHtml.escape(subject.name()), points(scored.points()),
                        scored.week()));
            }
            case LeaguePoints.DidNotPlay didNotPlay -> facts.put("fantasyPoints",
                    "none: " + SAT_OUT + didNotPlay.week());
            case LeaguePoints.Unknown unknown -> facts.put("fantasyPoints",
                    UNKNOWN + unknown.reason());
        }
    }

    private static void span(PlayerStatsEvidence.Subject subject, LeaguePoints.Report report,
            Map<String, String> facts) {
        List<LeaguePoints.Scored> scored = report.scored();
        if (scored.isEmpty()) {
            facts.put("fantasyPoints", UNKNOWN + "no source holds a week he played in "
                    + subject.window().label());
        } else {
            double perGame = report.total() / scored.size();
            String games = scored.size() == 1 ? "1 game" : scored.size() + " games";
            String name = TelegramHtml.escape(subject.name());
            facts.put("totalPoints", points(report.total()));
            facts.put("gamesScored", games);
            facts.put("pointsPerGame", points(perGame));
            facts.put("pointsByWeek", scored.stream()
                    .map(each -> "week %d %s".formatted(each.week(), points(each.points())))
                    .collect(Collectors.joining(", ")));
            facts.put(LINE_FACT, "%s has <b>%s</b> points over %s (%s per game).".formatted(
                    name, points(report.total()), games, points(perGame)));
            facts.put(PER_GAME_LINE_FACT, "%s is averaging <b>%s</b> points per game over %s."
                    .formatted(name, points(perGame), games));
        }
        if (!report.satOut().isEmpty()) {
            facts.put("didNotPlay", SAT_OUT + report.satOut().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(", ")));
        }
        if (!report.unknown().isEmpty()) {
            facts.put("weeksUnknown", UNKNOWN + report.unknown().stream()
                    .map(each -> "week %d (%s)".formatted(each.week(), each.reason()))
                    .collect(Collectors.joining("; ")));
        }
    }

    private static String rankLine(PlayerStatsEvidence.Subject subject, LeaguePoints.Rank rank,
            String leagueName, double total) {
        Position position = subject.position();
        String dot = rank.teams().isPresent()
                ? Dot.tier(rank.rank(), rank.teams().getAsInt()).emoji() + " "
                : "";
        return "%s<b>%s%d</b> of %d %s in %s scoring (%s points, %s)".formatted(dot,
                position.name(), rank.rank(), rank.of(), position.plural(),
                TelegramHtml.escape(leagueName), points(total), subject.window().label());
    }

    private static String scoredBy(List<LeaguePoints.Scored> scored) {
        boolean anyOfficial = scored.stream().anyMatch(LeaguePoints.Scored::official);
        boolean anyPriced = scored.stream().anyMatch(each -> !each.official());
        if (anyOfficial && !anyPriced) {
            return "Sleeper's own score in this league";
        }
        if (!anyOfficial) {
            return "his Sleeper stat line priced in this league's scoring";
        }
        return "Sleeper's own score in this league where it has one, otherwise his Sleeper "
                + "stat line priced in this league's scoring";
    }

    /**
     * Returns the league's catch scoring in words, such as "half PPR,
     * tight ends +0.5 per catch".
     */
    static String scoringWords(LeagueScoring scoring) {
        double perCatch = scoring.settings().getOrDefault("rec", 0.0);
        StringBuilder words = new StringBuilder(perCatch == FULL_PPR ? "full PPR"
                : perCatch == HALF_PPR ? "half PPR"
                : perCatch == 0.0 ? "standard, no points per catch"
                : "%s per catch".formatted(decimal(perCatch)));
        PREMIUMS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(premium -> {
                    double bonus = scoring.settings().getOrDefault(premium.getKey(), 0.0);
                    if (bonus != 0.0) {
                        words.append(", %s +%s per catch".formatted(premium.getValue(),
                                decimal(bonus)));
                    }
                });
        return words.toString();
    }

    private static String points(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String decimal(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.valueOf(value);
    }
}
