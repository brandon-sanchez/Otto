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
import otto.advice.Ordinal;
import otto.market.DraftPick;
import otto.market.KeeperCost;
import otto.market.MarketPlayer;
import otto.market.MarketQuote;
import otto.market.MarketValues;

/**
 * Writes a {@link MarketQuote} as the evidence bundle the model explains
 * it from. Every number is written the way the message may quote it,
 * with no thousands separators, so {@code AdviceCheck} can hold the
 * message to it.
 */
final class MarketValueEvidence {

    static final int MAX_LENGTH = 700;

    private static final String TASK = "Tell the user what %s is worth on the trade market "
            + "in his %s league, from the evidence.";

    private MarketValueEvidence() {
    }

    /**
     * Returns the bundle for one quote.
     *
     * @param pick the pick being priced, empty for a player
     */
    static EvidenceBundle bundle(MarketQuote quote, Optional<DraftPick> pick, Instant now) {
        String subject = pick.isPresent() ? "pick" : quote.id();
        String source = quote.source().label();
        List<EvidenceItem> items = new ArrayList<>();
        Map<String, String> value = new LinkedHashMap<>();
        value.put("subject", quote.name());
        value.put("value", String.valueOf(quote.value()));
        value.put("league", quote.league());
        value.put("source", source);
        value.put("meaning", "A market value is what managers pay for him in real trades on "
                + source + "; a bigger number is worth more, and values compare only with "
                + "other values from the same source and league format.");
        value.put("listDay", quote.asOf().toString());
        value.put("listAge", age(quote.ageDays()));
        if (quote.ageDays() > 0) {
            value.put("staleness", ("%s could not be reached today, so this value is from its "
                    + "list of %s.").formatted(source, age(quote.ageDays())));
        }
        if (quote.source() == MarketQuote.Source.DYNASTY_PROCESS) {
            value.put("fallback", "FantasyCalc does not price %s in this format, so the value "
                    .formatted(quote.name()) + "is DynastyProcess's dynasty value, built from "
                    + "expert dynasty rankings rather than trades.");
        }
        pick.ifPresent(drafted -> value.put(Ordinal.DRAFT_FACT, drafted.roundText()));
        items.add(item("market-value-" + subject, source, now, value));
        quote.listing().ifPresent(row -> items.addAll(listing(subject, quote, row, now)));
        quote.history().ifPresent(change -> items.add(history(subject, change, now)));
        quote.keeper().ifPresent(keeper -> items.add(keeper(subject, keeper, now)));
        List<BundlePlayer> players = pick.isPresent() ? List.of()
                : List.of(new BundlePlayer(quote.id(), quote.name(), Optional.empty()));
        Optional<Integer> auctionBudget = quote.keeper().flatMap(MarketQuote.Keeper::cost)
                .flatMap(KeeperCost::budget);
        return new EvidenceBundle(TASK.formatted(quote.name(), quote.league()), items, players,
                List.of(), true, Optional.empty(), MAX_LENGTH, auctionBudget, List.of(),
                List.of());
    }

    private static List<EvidenceItem> listing(String subject, MarketQuote quote,
            MarketPlayer row, Instant now) {
        List<EvidenceItem> items = new ArrayList<>();
        String source = quote.source().label();
        row.overallRank().ifPresent(rank -> items.add(item("market-overall-rank-" + subject,
                source, now, Map.of(Ordinal.RANK_FACT, Ordinal.of(rank),
                        "population", "every player and pick on FantasyCalc's list for this "
                                + "league format"))));
        row.positionRank().filter(rank -> !row.pick()).ifPresent(rank -> items.add(item(
                "market-position-rank-" + subject, source, now, Map.of(
                        Ordinal.RANK_FACT, Ordinal.of(rank),
                        "population", "every " + row.position() + " on the same list"))));
        row.tier().ifPresent(tier -> items.add(item("market-tier-" + subject, source, now,
                Map.of("tier", String.valueOf(tier),
                        "meaning", "FantasyCalc groups players of similar value into tiers; "
                                + "the lower the tier number, the more valuable the group."))));
        row.trend30Day().ifPresent(trend -> items.add(item("market-trend-" + subject, source,
                now, trend(trend, row.value()))));
        Map<String, String> profile = new LinkedHashMap<>();
        row.age().ifPresent(age -> profile.put("age", String.format(Locale.ROOT, "%.1f", age)));
        row.yearsExperience().ifPresent(years -> profile.put("nflSeasonsPlayed",
                String.valueOf(years)));
        row.adp().ifPresent(adp -> profile.put("averageDraftPosition",
                String.format(Locale.ROOT, "%.1f", adp)));
        row.rosterShare().ifPresent(share -> profile.put("rosteredIn",
                percent(share, 0) + " of leagues"));
        row.tradeFrequency().ifPresent(share -> profile.put("tradeShare",
                percent(share, 1) + " of the trades FantasyCalc logged include him"));
        if (!profile.isEmpty()) {
            items.add(item("market-profile-" + subject, source, now, profile));
        }
        return items;
    }

    private static Map<String, String> trend(int trend, int value) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("window", "the last 30 days");
        facts.put("direction", trend < 0 ? "down" : trend > 0 ? "up" : "unchanged");
        facts.put("change", String.valueOf(Math.abs(trend)));
        int before = value - trend;
        if (before > 0 && trend != 0) {
            facts.put("percent", Math.round(100.0 * Math.abs(trend) / before) + "%");
            facts.put("valueThirtyDaysAgo", String.valueOf(before));
        }
        return facts;
    }

    private static EvidenceItem history(String subject, MarketValues.Change change, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("window", change.days() == 1 ? "since yesterday"
                : "over the last %d days of Otto's own daily record".formatted(change.days()));
        facts.put("from", String.valueOf(change.from()));
        facts.put("to", String.valueOf(change.to()));
        facts.put("direction", change.to() < change.from() ? "down"
                : change.to() > change.from() ? "up" : "unchanged");
        facts.put("change", String.valueOf(Math.abs(change.to() - change.from())));
        facts.put("percent", Math.abs(change.percent()) + "%");
        return item("market-history-" + subject, "Otto's daily FantasyCalc record", now, facts);
    }

    private static EvidenceItem keeper(String subject, MarketQuote.Keeper keeper, Instant now) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("redraftValue", String.valueOf(keeper.redraftValue()));
        keeper.dynastyValue().ifPresent(value -> facts.put("dynastyValue", String.valueOf(value)));
        keeper.dynastySource().ifPresent(source -> facts.put("dynastySource", source.label()));
        keeper.keeperLimit().ifPresentOrElse(
                limit -> facts.put("keeperLimit", String.valueOf(limit)),
                () -> facts.put("keeperLimit", "the league does not say how many players a "
                        + "team may keep, so nobody is counted as a likely keeper"));
        facts.put("likelyKeeper", keeper.likely() ? "yes" : "no");
        keeper.cost().ifPresent(cost -> {
            facts.put("keeperCost", String.valueOf(cost.value()));
            facts.put("whatKeepingHimCosts", cost.cost());
            facts.put("keeperRule", cost.rule());
            cost.question().ifPresent(question -> facts.put("askForKeeperRule", question));
        });
        if (keeper.likely()) {
            facts.put("keeperLine", String.valueOf(keeper.keeperLine()));
            facts.put("keeperBonus", String.valueOf(keeper.bonus()));
            facts.put("meaning", "He is one of his team's likely keepers, so his value is his "
                    + "redraft value plus his keeper bonus: his dynasty value minus keeperCost, "
                    + "the dynasty value of the pick, dollars or keeper spot keeping him uses "
                    + "up.");
        } else {
            facts.put("meaning", "He is not one of his team's likely keepers, the players "
                    + "worth most above what keeping them costs, so his value is his redraft "
                    + "value alone.");
        }
        return item("market-keeper-" + subject, "Otto's keeper blend", now, facts);
    }

    private static String age(long days) {
        if (days == 0) {
            return "today";
        }
        return days == 1 ? "yesterday" : days + " days ago";
    }

    private static String percent(double share, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f%%", share * 100);
    }

    private static EvidenceItem item(String id, String source, Instant now,
            Map<String, String> facts) {
        return new EvidenceItem(id, source, Optional.empty(), now, "market value", facts);
    }
}
