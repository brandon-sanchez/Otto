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
        List<EvidenceItem> items = items(judged, now);
        List<BundlePlayer> players = judged.stream()
                .map(player -> new BundlePlayer(player.playerId(), player.name(),
                        Optional.empty()))
                .toList();
        return new EvidenceBundle(task, items, players, List.of(), true, Optional.empty(),
                MAX_LENGTH, Optional.empty(), List.of(), List.of());
    }

    /**
     * Returns the items that state each judged player's call and any
     * market move behind it, after the definitions they use; empty when
     * no player is judged.
     */
    static List<EvidenceItem> items(List<Judged> judged, Instant now) {
        List<EvidenceItem> items = new ArrayList<>();
        if (judged.isEmpty()) {
            return items;
        }
        items.add(terms(now));
        for (Judged player : judged) {
            items.add(player(player, now));
            player.assessment().market().ifPresent(move -> items.add(market(player, move, now)));
        }
        return items;
    }

    private static EvidenceItem terms(Instant now) {
        return new EvidenceItem("buysell-terms", SOURCE, Optional.empty(), now, "definitions",
                Map.of(
                        "expectedPoints", "what an average player would score with the "
                                + "same targets, carries and field position",
                        "pointsOverExpected", "points a game above or below what his targets "
                                + "and carries are worth; inside a season this is mostly luck",
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
            facts.put("levelToExpect", "%s points a game, what his work this season is worth"
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
                ? "what his targets and carries are worth a game is the same as last season"
                : "what his targets and carries are worth a game is %s %d%% from last season"
                        .formatted(
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
            case CHEAPER -> "his trade value fell while he kept getting the same work, so "
                    + "he costs less than he should";
            case FELL_WITH_USAGE -> "his trade value fell and so did his work, so the lower "
                    + "price is fair and makes him no bargain";
            case PAYING_FOR_STREAK -> "his trade value rose while his work did not, so "
                    + "managers are paying for a hot streak";
            case NONE -> move.fell() || move.rose()
                    ? "his trade value moved with his work, so his price is fair"
                    : "his trade value has barely moved";
        });
        return new EvidenceItem("buysell-market-" + judged.playerId(), "FantasyCalc",
                Optional.empty(), now, "market value trend", facts);
    }

    private static String call(Assessment.Call call) {
        return switch (call) {
            case BUY_LOW -> "buy-low";
            case MARKET_BUY_LOW -> "buy-low on price";
            case PARTIAL_BOUNCE_BACK -> "partial bounce-back";
            case ROLE_DECLINE -> "not a buy-low, his work shrank";
            case SELL_HIGH -> "sell-high";
            case NO_CALL -> "neither a buy-low nor a sell-high";
        };
    }

    private static String meaning(Assessment assessment) {
        return switch (assessment.call()) {
            case BUY_LOW -> "he is getting the same targets and carries but scoring less, "
                    + "so the dip is luck and his points should come back";
            case MARKET_BUY_LOW -> "he is getting the same targets and carries while his "
                    + "trade value fell, so he costs less than he should";
            case PARTIAL_BOUNCE_BACK -> "some of his slump is luck, but he is also getting "
                    + "less work, so expect only part of his old scoring back";
            case ROLE_DECLINE -> "his targets and carries fell by more than a quarter, so he "
                    + "is scoring less because he is used less, and he is not a buy-low";
            case SELL_HIGH -> "he is scoring well above what his targets and carries are "
                    + "worth, which does not usually last";
            case NO_CALL -> assessment.thisSeason().gamesPlayed() < 3
                    ? "he has played fewer than 3 games, too few to judge"
                    : "his points match the work he is getting and his normal level";
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
            return "right in line with what his targets and carries are worth";
        }
        return "%s points a game %s what his targets and carries are worth".formatted(
                decimal(Math.abs(perGame)),
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
