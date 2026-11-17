package otto.ask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import otto.advice.EvidenceItem;
import otto.research.ReturnTimeline;
import otto.trade.InjuredPlayer;
import otto.trade.InjuredValue;

/**
 * Writes what an injured player is worth to the user as evidence: his
 * return timeline exactly as cited news states it, the games he likely
 * plays after it, his worth in the league's format, and why the timing
 * suits both teams. Without a timeline in the news the evidence says
 * so, and the answer can't give one.
 */
final class InjuryEvidence {

    private static final String SOURCE = "Otto's injured-player read of cited news";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMMM d",
            Locale.US).withZone(ZoneId.of("America/New_York"));

    private InjuryEvidence() {
    }

    static String id(String playerId) {
        return "injury-" + playerId;
    }

    static EvidenceItem item(InjuredValue value, Instant now) {
        InjuredPlayer player = value.player();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("player", player.name());
        facts.put("status", status(player));
        player.injury().ifPresent(part -> facts.put("injury", part));
        value.holder().ifPresent(manager -> facts.put("rosteredBy", manager));
        facts.put("timeline", timeline(player));
        source(player.timeline()).ifPresent(cited -> {
            facts.put("reportedBy", cited.outlet());
            facts.put("reported", DAY.format(cited.published()));
            cited.url().ifPresent(url -> facts.put("story", url));
        });
        player.returnWeek().ifPresent(week -> facts.put("likelyBack", ("week %d, planning on "
                + "the long end of what the news says").formatted(week)));
        player.games().filter(games -> !player.seasonOver())
                .ifPresent(games -> facts.put("gamesBack", gamesBack(player, games)));
        player.perGame().filter(points -> !player.seasonOver()).ifPresent(points -> facts.put(
                "pointsWhenHePlays", "%s points a game when he plays, in your league's scoring"
                        .formatted(decimal(points))));
        facts.put("worth", worth(value));
        facts.put("worthBuying", value.worthBuying() ? "yes" : "no");
        value.timing().ifPresent(timing -> facts.put("timing", timing));
        return new EvidenceItem(id(player.playerId()), SOURCE,
                source(player.timeline()).map(ReturnTimeline.Cited::published), now,
                "injured player valued by league format", facts);
    }

    private static String status(InjuredPlayer player) {
        return switch (player.health()) {
            case IR -> "he is on injured reserve";
            case PUP -> "he is on the physically unable to perform list";
            default -> "he is listed as Out";
        };
    }

    private static String timeline(InjuredPlayer player) {
        return switch (player.timeline()) {
            case ReturnTimeline.SeasonEnding ending ->
                "the news says his injury ends his season: \"%s\"".formatted(
                        ending.source().statement());
            case ReturnTimeline.WeeksOut out -> "the news gives a timeline: \"%s\"".formatted(
                    out.source().statement());
            case ReturnTimeline.ByWeek byWeek -> "the news gives a timeline: \"%s\"".formatted(
                    byWeek.source().statement());
            case ReturnTimeline.NoTimetable _ -> "no timetable for return";
            case ReturnTimeline.Unknown _ -> "I don't know his timeline for "
                    + player.injuryWords();
        };
    }

    private static Optional<ReturnTimeline.Cited> source(ReturnTimeline timeline) {
        return switch (timeline) {
            case ReturnTimeline.SeasonEnding ending -> Optional.of(ending.source());
            case ReturnTimeline.WeeksOut out -> Optional.of(out.source());
            case ReturnTimeline.ByWeek byWeek -> Optional.of(byWeek.source());
            case ReturnTimeline.NoTimetable none -> Optional.of(none.source());
            case ReturnTimeline.Unknown _ -> Optional.empty();
        };
    }

    private static String gamesBack(InjuredPlayer player, int games) {
        String end = player.playoffsKnown()
                ? "week %d, when your league's playoffs end".formatted(player.throughWeek())
                : "week %d, the end of the regular season".formatted(player.throughWeek());
        return "%d %s from his return through %s".formatted(games,
                games == 1 ? "game" : "games", end);
    }

    private static String worth(InjuredValue value) {
        InjuredPlayer player = value.player();
        return switch (value.format()) {
            case REDRAFT -> player.seasonOver()
                    ? "in a redraft league he is worth 0 to you, because his season is over"
                    : player.pointsLeft()
                            .map(points -> ("in a redraft league he is worth only the games "
                                    + "he plays after he's back: about %s points").formatted(
                                            decimal(points)))
                            .orElse("in a redraft league he is worth only the games he plays "
                                    + "after he's back, and I can't count those without a "
                                    + "timeline");
            case KEEPER -> value.redraftMarket().flatMap(redraft -> value.keeperValue()
                            .map(keeper -> ("in a keeper league he is worth his redraft value"
                                    + "%s plus his keeper value of %d, so %d in all")
                                    .formatted(player.seasonOver()
                                            ? ", which is 0 because his season is over,"
                                            : " of " + redraft, keeper, redraft + keeper)))
                    .orElse("in a keeper league he is worth his redraft value plus his keeper "
                            + "value, and the market prices neither for him right now");
            case DYNASTY -> value.dynastyValue()
                    .map(dynasty -> ("in a dynasty league he is worth his long-term value, %d "
                            + "on the dynasty market").formatted(dynasty))
                    .orElse("in a dynasty league he is worth his long-term value, and the "
                            + "market doesn't price him right now");
        };
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
