package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.buysell.Assessment;
import otto.buysell.Baseline;
import otto.buysell.MarketMove;
import otto.buysell.PlayerSeason;
import otto.buysell.TeamContext;
import otto.buysell.Usage;

/**
 * Writes buy-low and sell-high assessments as the evidence bundle the
 * model explains them from. Every number is written the way the message
 * may quote it, so {@code AdviceCheck} can hold the message to it.
 */
final class BuySellEvidence {

    static final int MAX_LENGTH = 1200;

    private static final String SOURCE = "ffopportunity via Otto's buy-low and sell-high rules";

    private BuySellEvidence() {
    }

    /**
     * One judged player.
     *
     * @param rosteredBy the manager whose team he is on, empty when he is
     *        on the user's team or a free agent
     */
    record Judged(String playerId, String name, Assessment assessment,
            Optional<String> rosteredBy) {
    }

    static EvidenceBundle bundle(String task, List<Judged> judged, Instant now) {
        List<EvidenceItem> items = new ArrayList<>();
        items.add(terms(now));
        for (Judged player : judged) {
            items.add(player(player, now));
            player.assessment().market().ifPresent(move -> items.add(market(player, move, now)));
        }
        List<BundlePlayer> players = judged.stream()
                .map(player -> new BundlePlayer(player.playerId(), player.name(),
                        Optional.empty()))
                .toList();
        return new EvidenceBundle(task, items, players, List.of(), true, Optional.empty(),
                MAX_LENGTH, Optional.empty(), List.of(), List.of());
    }

    private static EvidenceItem terms(Instant now) {
        return new EvidenceItem("buysell-terms", SOURCE, Optional.empty(), now, "definitions",
                Map.of(
                        "expectedPoints", "xFP: what an average player would score with the "
                                + "same targets, carries and field position",
                        "pointsOverExpected", "points a game above or below what his usage "
                                + "is worth; inside a season this is mostly luck",
                        "scoring", "ffopportunity's full-PPR scoring, which compares players "
                                + "with each other but is not this league's scoring"));
    }

    private static EvidenceItem player(Judged judged, Instant now) {
        Assessment assessment = judged.assessment();
        PlayerSeason season = assessment.thisSeason();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("player", judged.name());
        facts.put("call", call(assessment.call()));
        facts.put("meaning", meaning(assessment));
        judged.rosteredBy().ifPresent(manager -> facts.put("rosteredBy", manager));
        facts.put("gamesThisSeason", String.valueOf(season.gamesPlayed()));
        facts.put("pointsPerGame", decimal(season.pointsPerGame()));
        facts.put("expectedPointsPerGame", decimal(season.expectedPerGame()));
        facts.put("pointsVersusUsage", versus(season.overExpectedPerGame()));
        facts.put("touchdowns", "%s touchdowns on %s expected this season".formatted(
                whole(season.touchdowns()), decimal(season.expectedTouchdowns())));
        PlayerSeason recent = assessment.recent();
        facts.put("lastThreeGames", "%s points a game on %s expected over his last %d games"
                .formatted(decimal(recent.pointsPerGame()), decimal(recent.expectedPerGame()),
                        recent.gamesPlayed()));
        if (assessment.call() == Assessment.Call.SELL_HIGH) {
            PlayerSeason window = assessment.touchdownWindow();
            facts.put("levelToExpect", "%s points a game, his xFP this season"
                    .formatted(decimal(season.expectedPerGame())));
            facts.put("recentTouchdowns", "%s touchdowns on %s expected over his last %d games"
                    .formatted(whole(window.touchdowns()), decimal(window.expectedTouchdowns()),
                            window.gamesPlayed()));
        }
        facts.put("baseline", baseline(assessment.baseline()));
        assessment.lastSeason().ifPresent(last -> facts.put("lastSeason",
                "%s points a game on %s expected over %d games".formatted(
                        decimal(last.pointsPerGame()), decimal(last.expectedPerGame()),
                        last.gamesPlayed())));
        assessment.lastSeasonFinish().ifPresent(finish -> facts.put("lastSeasonFinish",
                season.position() + finish));
        assessment.usage().ifPresent(usage -> usage(usage, facts));
        if (!assessment.proven().isEmpty()) {
            facts.put("proven", String.join("; ", assessment.proven()));
        }
        if (!assessment.exclusions().isEmpty()
                && assessment.call() != Assessment.Call.SELL_HIGH) {
            facts.put("ruledOutAsBuyLow", String.join("; ", assessment.exclusions()));
        }
        return new EvidenceItem("buysell-" + judged.playerId(), SOURCE, Optional.empty(), now,
                "buy-low and sell-high call", facts);
    }

    private static void usage(Usage usage, Map<String, String> facts) {
        long change = Math.round(100 * (usage.expectedRatio() - 1));
        facts.put("expectedPointsChange", change == 0
                ? "his xFP a game is the same as last season"
                : "his xFP a game is %s %d%% from last season".formatted(
                        change < 0 ? "down" : "up", Math.abs(change)));
        usage.shareNow().ifPresent(now -> usage.shareBefore().ifPresent(before -> facts.put(
                "share", "his %s is %s this season against %s last season, %s".formatted(
                        usage.share().words(), percent(now), percent(before),
                        shareMove(now - before)))));
        facts.put("usageHeld", usage.held() ? "yes" : "no");
    }

    private static EvidenceItem market(Judged judged, MarketMove move, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("player", judged.name());
        facts.put("direction", move.percent() < 0 ? "down" : move.percent() > 0 ? "up"
                : "unchanged");
        facts.put("change", Math.abs(move.percent()) + "%");
        facts.put("window", "the last %d days".formatted(move.days()));
        facts.put("measuredBy", move.source());
        facts.put("meaning", switch (judged.assessment().marketRead()) {
            case CHEAPER -> "his value fell while his usage held, so he costs less than his "
                    + "role is worth";
            case FELL_WITH_USAGE -> "his value fell and so did his usage, so the lower price "
                    + "is fair and makes him no bargain";
            case PAYING_FOR_STREAK -> "his value rose while his usage did not, so the market "
                    + "is paying for a hot streak";
            case NONE -> move.fell() || move.rose()
                    ? "his value moved with his usage, so the market is pricing his role"
                    : "his value has barely moved";
        });
        return new EvidenceItem("buysell-market-" + judged.playerId(), "FantasyCalc",
                Optional.empty(), now, "market value trend", facts);
    }

    private static String call(Assessment.Call call) {
        return switch (call) {
            case BUY_LOW -> "buy-low";
            case MARKET_BUY_LOW -> "buy-low on price";
            case PARTIAL_BOUNCE_BACK -> "partial bounce-back";
            case ROLE_DECLINE -> "role decline";
            case SELL_HIGH -> "sell-high";
            case NO_CALL -> "neither a buy-low nor a sell-high";
        };
    }

    private static String meaning(Assessment assessment) {
        return switch (assessment.call()) {
            case BUY_LOW -> "his usage held but his points slumped, so the gap is luck and "
                    + "his points should come back toward his baseline";
            case MARKET_BUY_LOW -> "his usage held while his market value fell, so he costs "
                    + "less now than his role is worth";
            case PARTIAL_BOUNCE_BACK -> "his points slumped partly from luck, but his usage "
                    + "also slipped, so expect only part of his old level back";
            case ROLE_DECLINE -> "his usage fell by more than a quarter, so his slump is a "
                    + "smaller role rather than bad luck, and he is not a buy-low";
            case SELL_HIGH -> "he is scoring well above what his usage is worth, which does "
                    + "not usually last, so his xFP is the level to expect";
            case NO_CALL -> assessment.thisSeason().gamesPlayed() < 3
                    ? "he has played fewer than 3 games, too few to judge"
                    : "his points are in line with his usage and his normal level";
        };
    }

    private static String baseline(Baseline baseline) {
        String blend;
        if (!baseline.resets().isEmpty()) {
            blend = "this season alone, because " + String.join(" and ", baseline.resets()
                    .stream().map(TeamContext.Change::words).toList());
        } else if (baseline.lastSeasonWeight() == 0) {
            blend = "this season alone, because he has no full last season";
        } else {
            blend = "last season, which counts for %d%%, and this season".formatted(
                    Math.round(100 * baseline.lastSeasonWeight()));
        }
        return "%s points a game expected, blended from %s".formatted(
                decimal(baseline.perGame()), blend);
    }

    private static String versus(double perGame) {
        if (Math.abs(perGame) < 0.05) {
            return "right in line with what his usage is worth";
        }
        return "%s points a game %s what his usage is worth".formatted(decimal(Math.abs(perGame)),
                perGame > 0 ? "above" : "below");
    }

    private static String shareMove(double change) {
        double points = Math.abs(change) * 100;
        if (points < 0.05) {
            return "unchanged";
        }
        return "%s by %s points".formatted(change < 0 ? "down" : "up",
                String.format(Locale.ROOT, "%.1f", points));
    }

    private static String percent(double share) {
        return String.format(Locale.ROOT, "%.1f%%", share * 100);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String whole(double value) {
        return String.valueOf(Math.round(value));
    }
}
