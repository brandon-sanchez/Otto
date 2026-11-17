package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import otto.advice.BundlePlayer;
import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.advice.Ordinal;
import otto.market.MarketDay;
import otto.market.MarketFormat;
import otto.market.MarketPlayer;
import otto.team.LeagueProfile;
import otto.team.PositionProfile;
import otto.team.RatedPlayer;
import otto.team.Suggestion;
import otto.team.TeamDirection;
import otto.team.TeamProfile;

/**
 * Writes the evidence bundle the weaknesses answer is explained from.
 * Every number and rank the answer may state is written here, by code,
 * in the words the answer may quote.
 */
final class TeamWeaknessEvidence {

    private static final String SOURCE = "otto:team-profile";
    private static final String MARKET_SOURCE = "fantasycalc:values";
    private static final int TIERED_BANDS = 3;

    private TeamWeaknessEvidence() {
    }

    /**
     * Today's FantasyCalc list in the league's format.
     *
     * @param format the format the list prices, whose team count sets
     *        the market bands: a WR1 is a top-12 WR in a 12-team league
     */
    record Market(MarketDay day, MarketFormat format) {
    }

    /**
     * The user's own direction and the market list its values come from.
     *
     * @param market empty when no list could be read
     */
    record Direction(TeamDirection user, Optional<String> market) {
    }

    /**
     * Everything the bundle is written from.
     *
     * @param user the user's team, which is in {@code league}
     * @param sidelined the user's IR and taxi players left out of the
     *        profile because he cannot activate them now
     * @param weeks the remaining weeks the per-game averages cover
     * @param tradeDeadline the week trades closed after, present only
     *        once it has passed
     * @param limits what free data cannot say that matters to this read
     * @param everyLeague true when the user asked about all his leagues
     * @param direction empty when there was nothing to measure it by
     */
    record Facts(String leagueName, LeagueProfile league, TeamProfile user,
            List<Suggestion> suggestions, Optional<Market> market,
            List<BundlePlayer> sidelined, List<Integer> weeks,
            Optional<Integer> tradeDeadline, List<String> limits, boolean everyLeague,
            Optional<Direction> direction) {
    }

    static EvidenceBundle bundle(Facts facts, int maxLength, Instant now) {
        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(item("summary", now, summary(facts)));
        if (facts.everyLeague()) {
            evidence.add(item("leagues", now, Map.of("leagues", ("I follow one league, %s, so "
                    + "this answer covers that league only").formatted(facts.leagueName()))));
        }
        facts.direction().ifPresent(direction -> evidence.add(
                TeamDirectionEvidence.userItem(direction.user(), direction.market(), now)));
        for (String position : LeagueProfile.POSITIONS) {
            facts.user().at(position).ifPresent(profile -> evidence.add(item(
                    "position-" + position, now, position(profile, facts.market()))));
        }
        players(facts.user()).forEach(player -> evidence.add(item(
                "player-" + player.playerId(), now, player(player))));
        facts.sidelined().forEach(player -> evidence.add(item("sidelined-" + player.playerId(),
                now, Map.of("player", player.fullName(), "counted", ("%s is on IR or the taxi "
                        + "squad and cannot be activated now, so he is left out")
                        .formatted(player.fullName())))));
        facts.market().ifPresent(market -> Stream.concat(players(facts.user()),
                        suggestedPlayers(facts.suggestions()))
                .forEach(player -> market.day().entry(player.playerId()).ifPresent(row ->
                        evidence.add(marketItem(player, row, market, now)))));
        evidence.add(item("ceiling", now, ceiling(facts)));
        for (int index = 0; index < facts.suggestions().size(); index++) {
            evidence.add(item("suggestion-" + (index + 1), now,
                    suggestion(facts.suggestions().get(index))));
        }
        facts.tradeDeadline().ifPresent(week -> evidence.add(item("trade-deadline", now,
                Map.of("tradeDeadline", ("the trade deadline passed after week %d, so trades "
                        + "are closed and only waiver moves are left").formatted(week)))));
        if (!facts.limits().isEmpty()) {
            Map<String, String> limits = new LinkedHashMap<>();
            for (int index = 0; index < facts.limits().size(); index++) {
                limits.put("limit" + (index + 1), facts.limits().get(index));
            }
            evidence.add(item("limits", now, limits));
        }
        List<BundlePlayer> players = Stream.concat(Stream.concat(players(facts.user()),
                        suggestedPlayers(facts.suggestions()))
                        .map(player -> new BundlePlayer(player.playerId(), player.name(),
                                Optional.empty())),
                        facts.sidelined().stream())
                .distinct()
                .toList();
        return new EvidenceBundle(
                "Tell the user where his team is strong, thin or in surplus, position by "
                        + "position, how high its big weeks reach, and what to do about it.",
                evidence, players, List.of(), true, Optional.empty(), maxLength,
                Optional.empty(), List.of(), List.of());
    }

    private static Map<String, String> summary(Facts facts) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("league", facts.leagueName());
        summary.put("teams", "%d teams with players compared"
                .formatted(facts.league().teams().size()));
        summary.put("basis", ("every average is rest-of-season projected points per game "
                + "in this league's scoring, weeks %d to %d, byes left out")
                .formatted(facts.weeks().getFirst(), facts.weeks().getLast()));
        for (PositionProfile.Strength strength : PositionProfile.Strength.values()) {
            List<String> positions = facts.user().positions().values().stream()
                    .filter(profile -> profile.strength() == strength)
                    .map(PositionProfile::position)
                    .sorted((a, b) -> LeagueProfile.POSITIONS.indexOf(a)
                            - LeagueProfile.POSITIONS.indexOf(b))
                    .toList();
            if (!positions.isEmpty()) {
                summary.put(strength.name().toLowerCase(Locale.ROOT), String.join(" and ", positions));
            }
        }
        return summary;
    }

    private static Map<String, String> position(PositionProfile profile,
            Optional<Market> market) {
        String position = profile.position();
        int width = Math.max(1, profile.dedicatedSlots());
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("position", position);
        facts.put("strength", words(profile.strength()));
        facts.put("starters", "%s points a game from your top %d %s".formatted(
                decimal(profile.starterAverage()), width, plural(position, width)));
        facts.put("starterRank", standing(profile.starterRank(), profile.teams()));
        facts.put("depth", "%s points a game from your next %d %s".formatted(
                decimal(profile.depthAverage()), width, plural(position, width)));
        facts.put("depthRank", standing(profile.depthRank(), profile.teams()));
        facts.put("players", "%d %s you can use".formatted(profile.players().size(),
                plural(position, profile.players().size())));
        if (Double.isFinite(profile.startableLine())) {
            facts.put("startable", "%d startable %s for %d %s that can start one%s".formatted(
                    profile.startable(), plural(position, profile.startable()), profile.spots(),
                    profile.spots() == 1 ? "spot" : "spots",
                    "QB".equals(position) ? "" : ", leaving Superflex to quarterbacks"));
            facts.put("startableLine", ("startable means %s points a game or more, the level "
                    + "of the last rostered %s who makes a best lineup in this league")
                    .formatted(decimal(profile.startableLine()), position));
        }
        market.ifPresent(value -> facts.put("marketShape", marketShape(profile, value)));
        return facts;
    }

    private static Map<String, String> player(RatedPlayer player) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("player", player.name());
        facts.put("position", player.position());
        facts.put("average", "%s points a game over the rest of the season"
                .formatted(decimal(player.perGame())));
        facts.put("role", player.starter() ? "in your best lineup" : "on your bench");
        player.boomChance().ifPresent(chance -> facts.put("boomChance",
                "%s chance of a boom week".formatted(percent(chance))));
        return facts;
    }

    private static EvidenceItem marketItem(RatedPlayer player, MarketPlayer row, Market market,
            Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("player", player.name());
        facts.put("list", "FantasyCalc, " + market.format().describe());
        row.positionRank().ifPresent(rank -> {
            facts.put(Ordinal.RANK_FACT, "%s among %ss on FantasyCalc".formatted(
                    Ordinal.of(rank), player.position()));
            facts.put("band", band(player.position(), rank, market.format().teams()));
        });
        row.tier().ifPresent(tier -> facts.put("tier", "FantasyCalc tier " + tier));
        row.trend30Day().ifPresent(trend -> facts.put("trend", trend(row.value(), trend)));
        return new EvidenceItem("market-" + player.playerId(), MARKET_SOURCE, Optional.empty(),
                market.day().fetchedAt(), "market shape: what other managers pay for him",
                facts);
    }

    private static Map<String, String> ceiling(Facts facts) {
        Map<String, String> ceiling = new LinkedHashMap<>();
        ceiling.put("boomStarters", ("%s of your %d starters reach a boom week in an average "
                + "week").formatted(decimal(facts.user().boomStarters()),
                        facts.user().lineup().size()));
        ceiling.put("boomRank", standing(facts.user().boomRank(), facts.league().teams().size()));
        facts.user().lineup().stream()
                .filter(player -> player.boomChance().isPresent())
                .max((a, b) -> Double.compare(a.boomChance().get(), b.boomChance().get()))
                .ifPresent(best -> ceiling.put("bestBoom", ("%s has the best chance of a boom "
                        + "week among your starters, %s").formatted(best.name(),
                                percent(best.boomChance().get()))));
        ceiling.put("boomWeek", "a boom week is a top weekly finish: QB6, RB12, WR12 or TE6");
        return ceiling;
    }

    private static Map<String, String> suggestion(Suggestion suggestion) {
        Map<String, String> facts = new LinkedHashMap<>();
        switch (suggestion) {
            case Suggestion.Waiver waiver -> {
                String position = waiver.need().position();
                facts.put("move", "claim %s off waivers".formatted(waiver.add().name()));
                facts.put("player", waiver.add().name());
                facts.put("average", "%s points a game over the rest of the season"
                        .formatted(decimal(waiver.add().perGame())));
                facts.put(Ordinal.RANK_FACT, "%s among your %ss if you add him"
                        .formatted(Ordinal.of(waiver.place()), position));
                facts.put("reason", "your %s position is thin".formatted(position));
            }
            case Suggestion.Trade trade -> {
                String surplus = trade.surplus().position();
                String need = trade.need().position();
                facts.put("move", "trade from your %s surplus for help at %s"
                        .formatted(surplus, need));
                facts.put("give", "%s, beyond the %d %s spots".formatted(
                        trade.give().stream().map(RatedPlayer::name)
                                .collect(Collectors.joining(" or ")),
                        trade.surplus().spots(), surplus));
                facts.put("partners", String.join(", ", trade.partners()));
                facts.put("reason", "%s %s deeper than you at %s and thin at %s".formatted(
                        String.join(" and ", trade.partners()),
                        trade.partners().size() == 1 ? "is" : "are", need, surplus));
            }
        }
        return facts;
    }

    private static Stream<RatedPlayer> players(TeamProfile user) {
        return LeagueProfile.POSITIONS.stream()
                .flatMap(position -> user.at(position).stream())
                .flatMap(profile -> profile.players().stream());
    }

    private static Stream<RatedPlayer> suggestedPlayers(List<Suggestion> suggestions) {
        return suggestions.stream().flatMap(suggestion -> switch (suggestion) {
            case Suggestion.Waiver waiver -> Stream.of(waiver.add());
            case Suggestion.Trade trade -> trade.give().stream();
        });
    }

    /**
     * Counts the position's players by market band: a WR1 ranks inside
     * the top {@code teams} WRs on the list, a WR2 inside twice that.
     */
    private static String marketShape(PositionProfile profile, Market market) {
        String position = profile.position();
        int teams = market.format().teams();
        Map<String, Integer> bands = new LinkedHashMap<>();
        for (int band = 1; band <= TIERED_BANDS; band++) {
            bands.put(position + band, 0);
        }
        bands.put("below " + position + TIERED_BANDS, 0);
        bands.put("not on the list", 0);
        for (RatedPlayer player : profile.players()) {
            String band = market.day().entry(player.playerId())
                    .flatMap(MarketPlayer::positionRank)
                    .map(rank -> band(position, rank, teams))
                    .orElse("not on the list");
            bands.merge(band, 1, Integer::sum);
        }
        return "by FantasyCalc position rank: " + bands.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(Collectors.joining(", "));
    }

    private static String band(String position, int rank, int teams) {
        int band = (rank - 1) / teams + 1;
        return band <= TIERED_BANDS ? position + band : "below " + position + TIERED_BANDS;
    }

    private static String trend(int value, int trend) {
        int before = value - trend;
        String direction = trend >= 0 ? "up" : "down";
        if (before <= 0) {
            return "value %s %d over the last 30 days".formatted(direction, Math.abs(trend));
        }
        return "value %s %d, or %d%%, over the last 30 days".formatted(direction,
                Math.abs(trend), Math.round(100.0 * Math.abs(trend) / before));
    }

    private static EvidenceItem item(String id, Instant now, Map<String, String> facts) {
        return new EvidenceItem(id, SOURCE, Optional.empty(), now,
                "team profile from rest-of-season projections", facts);
    }

    private static String words(PositionProfile.Strength strength) {
        return switch (strength) {
            case SURPLUS -> "in surplus";
            case THIN -> "thin";
            case STRONG -> "strong";
            case MIDDLE -> "in the middle";
        };
    }

    private static String standing(int rank, int teams) {
        return "%s of %d".formatted(Ordinal.of(rank), teams);
    }

    private static String plural(String position, int count) {
        return count == 1 ? position : position + "s";
    }

    private static String percent(double chance) {
        return "%d%%".formatted(Math.round(100 * chance));
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
