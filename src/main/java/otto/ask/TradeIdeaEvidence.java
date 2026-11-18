package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
import otto.advice.Prose;
import otto.format.TelegramHtml;
import otto.offers.Offer;
import otto.trade.PricedTrade;

/**
 * Writes trade offers as evidence: every name, number and reason the
 * answer may state about each offer, in the words it may quote them in.
 * The weaknesses answer writes its trade suggestions the same way.
 */
final class TradeIdeaEvidence {

    private static final String SOURCE = "otto:trade-offers";

    private TradeIdeaEvidence() {
    }

    /** How many of the partner's reasons the code-written fallback gives for an offer. */
    private static final int REASONS_IN_THE_FALLBACK = 2;

    /**
     * Everything the trade ideas bundle is written from.
     *
     * @param asked how many offers the user asked for
     * @param shown how many he had already seen, which this answer skips
     * @param shape the shape he asked for, such as "3-for-2"; empty for
     *        the default shapes
     * @param deadline what the trade deadline means this week, when it is
     *        this week
     */
    record Facts(String leagueName, List<Offer> offers, int asked, int shown,
            Optional<String> shape, Optional<String> deadline) {
    }

    static EvidenceBundle bundle(Facts facts, int maxLength, Instant now) {
        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(item("summary", now, summary(facts)));
        facts.deadline().ifPresent(deadline -> evidence.add(item("trade-deadline", now,
                Map.of("tradeDeadline", deadline))));
        for (int index = 0; index < facts.offers().size(); index++) {
            offerItems("offer-" + (index + 1), facts.offers().get(index), now).stream()
                    .filter(item -> evidence.stream()
                            .noneMatch(known -> known.id().equals(item.id())))
                    .forEach(evidence::add);
        }
        return new EvidenceBundle("Suggest these trade offers to the user, each with who to "
                + "send it to, what the user gives and gets, how it helps the user and why "
                + "the other manager would say yes.", evidence, players(facts.offers()), List.of(), true,
                Optional.empty(), maxLength, Optional.empty(), List.of(), List.of());
    }

    /**
     * Renders the computed offers when the model can't supply a checked
     * explanation: each offer, then how it helps the user and the leading
     * reasons the other manager would say yes.
     */
    static String verifiedMessage(Facts facts) {
        List<String> sections = new ArrayList<>();
        facts.deadline().map(TelegramHtml::escape).ifPresent(sections::add);
        if (facts.offers().size() < facts.asked()) {
            int found = facts.offers().size();
            sections.add(found == 1 ? "I found one trade worth offering."
                    : "I found %d trades worth offering.".formatted(found));
        }
        for (int index = 0; index < facts.offers().size(); index++) {
            Offer offer = facts.offers().get(index);
            String message = """
                    %d. Send to %s
                    You give: %s
                    You get: %s""".formatted(index + 1, TelegramHtml.bold(offer.partner()),
                    TelegramHtml.escape(names(offer.gives())),
                    TelegramHtml.escape(names(offer.gets())));
            List<String> yours = offer.helpsYou().isEmpty()
                    ? List.of("This is " + edge(offer.priced()))
                    : offer.helpsYou();
            List<String> paragraphs = Stream.of(yours, partnersReasons(offer))
                    .filter(sentences -> !sentences.isEmpty())
                    .map(sentences -> sentences.stream()
                            .map(sentence -> TelegramHtml.escape(sentence) + ".")
                            .collect(Collectors.joining(" ")))
                    .toList();
            sections.add(Stream.concat(Stream.of(message), paragraphs.stream())
                    .collect(Collectors.joining("\n\n")));
        }
        return String.join("\n\n", sections);
    }

    /**
     * Returns the reasons the fallback gives for the partner saying yes:
     * what the trade does for the partner's roster first, and the trade
     * values only when nothing else fills the space.
     */
    private static List<String> partnersReasons(Offer offer) {
        return offer.reasons().stream()
                .sorted(Comparator.comparing(reason -> reason.startsWith(Offer.TRADE_VALUE)))
                .limit(REASONS_IN_THE_FALLBACK)
                .toList();
    }

    /**
     * The items that state one offer: the offer itself, one per draft pick
     * it moves, the buy-low or sell-high read of each player who carries
     * one, and what each injured player is worth while he is hurt.
     *
     * @param id the offer item's id, which the pick items extend
     */
    static List<EvidenceItem> offerItems(String id, Offer offer, Instant now) {
        List<EvidenceItem> items = new ArrayList<>();
        items.add(item(id, now, offer(offer)));
        List<Offer.Piece> pieces = Stream.concat(offer.gets().stream(), offer.gives().stream())
                .toList();
        for (int index = 0; index < pieces.size(); index++) {
            Offer.Piece piece = pieces.get(index);
            int place = index + 1;
            piece.pick().ifPresent(pick -> items.add(item(id + "-pick" + place, now, Map.of(
                    "pick", piece.name(), Ordinal.DRAFT_FACT, pick.roundText()))));
        }
        List<BuySellEvidence.Judged> judged = pieces.stream()
                .filter(piece -> piece.playerId().isPresent() && piece.call().isPresent())
                .map(piece -> new BuySellEvidence.Judged(piece.playerId().get(), piece.name(),
                        piece.call().get(), offer.gets().contains(piece)
                                ? Optional.of(offer.partner()) : Optional.empty()))
                .toList();
        BuySellEvidence.items(judged, now).stream()
                .filter(item -> items.stream().noneMatch(known -> known.id().equals(item.id())))
                .forEach(items::add);
        pieces.stream()
                .flatMap(piece -> piece.injury().stream())
                .map(injury -> InjuryEvidence.item(injury, now))
                .forEach(items::add);
        return items;
    }

    /** Returns every player the offers name. */
    static List<BundlePlayer> players(List<Offer> offers) {
        return offers.stream()
                .flatMap(offer -> Stream.concat(offer.gets().stream(), offer.gives().stream()))
                .flatMap(piece -> piece.playerId().map(playerId -> new BundlePlayer(playerId,
                        piece.name(), Optional.empty())).stream())
                .distinct()
                .toList();
    }

    private static Map<String, String> summary(Facts facts) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("league", facts.leagueName());
        summary.put("asked", facts.shown() > 0
                ? "%d more trade ideas after the %d already shown".formatted(facts.asked(),
                        facts.shown())
                : "%d trade ideas".formatted(facts.asked()));
        summary.put("found", facts.offers().size() < facts.asked()
                ? "only %s a win for the user that the other manager would plausibly accept"
                        .formatted(facts.offers().size() == 1 ? "1 offer is"
                                : facts.offers().size() + " offers are")
                : "%d offers".formatted(facts.offers().size()));
        facts.shape().ifPresent(shape -> summary.put("shape", "each offer is a %s, as the "
                .formatted(shape) + "user asked"));
        summary.put("sending", "Otto never sends or accepts a trade; the user sends any offer "
                + "in the Sleeper app");
        return summary;
    }

    private static Map<String, String> offer(Offer offer) {
        PricedTrade priced = offer.priced();
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("sendTo", offer.partner());
        facts.put("youGive", names(offer.gives()));
        facts.put("youGet", names(offer.gets()));
        facts.put("shape", shape(offer));
        facts.put("yourEdge", edge(priced));
        facts.put("yourLineup", lineupChange(priced));
        for (int index = 0; index < offer.helpsYou().size(); index++) {
            facts.put("helpsYou" + (index + 1), offer.helpsYou().get(index));
        }
        priced.market().ifPresent(market -> facts.put("market",
                "on trade value you get %d and give %d".formatted(
                        market.youGet().total(), market.youGive().total())));
        if (priced.otherMeasure() != null) {
            facts.put("otherMeasure", priced.otherMeasure());
        }
        for (int index = 0; index < offer.reasons().size(); index++) {
            facts.put("why" + (index + 1), offer.reasons().get(index));
        }
        offer.partnerDirection().ifPresent(direction -> facts.put("partnerDirection",
                "%s is %s: %s".formatted(offer.partner(), direction.direction().words(),
                        direction.reason())));
        offer.playoffWeeks().ifPresent(line -> facts.put("playoffWeeks", line));
        return facts;
    }

    /**
     * Says in plain words how far the trade leans the user's way. The
     * share behind the verdict stays out: it is a share of the larger
     * side, so it reads 100% whenever the players leaving were not in the
     * user's lineup, which says nothing a reader can use.
     */
    private static String edge(PricedTrade priced) {
        String on = priced.marketDecides() ? "on trade value"
                : "for your lineup the rest of this season";
        return "even".equals(priced.verdict())
                ? "about even %s, leaning your way".formatted(on)
                : "a %s for you %s".formatted(priced.verdict(), on);
    }

    private static String lineupChange(PricedTrade priced) {
        double change = Double.parseDouble(priced.yours().startersDelta());
        String weeks = priced.restOfSeasonWeeks();
        if (change == 0) {
            return "your starting lineup scores the same over %s".formatted(weeks);
        }
        return "your starting lineup %s %s points over %s".formatted(
                change > 0 ? "gains" : "gives up",
                String.format(Locale.ROOT, "%.1f", Math.abs(change)), weeks);
    }

    private static String shape(Offer offer) {
        String need = offer.need().orElse("");
        return switch (offer.kind()) {
            case UPGRADE -> "a %s: two of yours for one better player, to upgrade your %s "
                    .formatted(offer.shape().words(), need) + "starters";
            case DEPTH -> "a %s: one of yours for two of his, for depth at %s".formatted(
                    offer.shape().words(), need);
            case ASKED -> "a %s, the shape you asked for".formatted(offer.shape().words());
        };
    }

    private static String names(List<Offer.Piece> pieces) {
        return Prose.listed(pieces.stream().map(Offer.Piece::name).toList());
    }

    private static EvidenceItem item(String id, Instant now, Map<String, String> facts) {
        return new EvidenceItem(id, SOURCE, Optional.empty(), now,
                "trade offer priced as evaluate_trade prices it", facts);
    }
}
