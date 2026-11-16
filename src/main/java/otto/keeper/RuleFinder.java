package otto.keeper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import otto.sleeper.SleeperDrafts;

/**
 * Reads a league's keeper rule off its drafts. Each keeper in one draft
 * is set against what he cost in the draft before, and a rule is
 * proposed only when one rule explains every keeper and at least two
 * keepers back it. The owner confirms a proposal before it counts as
 * his league's rule.
 */
final class RuleFinder {

    private static final int MINIMUM_KEEPERS = 2;

    private RuleFinder() {
    }

    /** One keeper: what he cost the draft before, empty when undrafted, and what keeping him cost. */
    private record Kept(Optional<Integer> before, int cost) {
    }

    /**
     * Returns the rule the drafts follow, or empty when there are fewer
     * than two drafts of one kind with keepers, or no one rule fits.
     *
     * @param drafts the league's completed drafts, newest first
     */
    static Optional<KeeperRule> propose(List<SleeperDrafts.Draft> drafts) {
        if (drafts.size() < 2) {
            return Optional.empty();
        }
        SleeperDrafts.DraftType type = drafts.getFirst().type();
        boolean auction = type == SleeperDrafts.DraftType.AUCTION;
        List<Kept> kept = new ArrayList<>();
        for (int at = 0; at + 1 < drafts.size(); at++) {
            SleeperDrafts.Draft draft = drafts.get(at);
            SleeperDrafts.Draft before = drafts.get(at + 1);
            if (draft.type() != type || before.type() != type) {
                break;
            }
            for (SleeperDrafts.Pick pick : draft.picks()) {
                Optional<Integer> cost = auction ? pick.price() : Optional.of(pick.round());
                if (pick.keeper() && cost.isPresent()) {
                    kept.add(new Kept(before.pickOf(pick.playerId())
                            .flatMap(earlier -> auction ? earlier.price()
                                    : Optional.of(earlier.round())), cost.get()));
                }
            }
        }
        List<Kept> drafted = kept.stream().filter(one -> one.before().isPresent()).toList();
        if (drafted.size() < MINIMUM_KEEPERS) {
            return Optional.empty();
        }
        Optional<KeeperRule> rule = auction ? auctionRule(drafted) : roundsRule(drafted);
        Optional<Integer> undrafted = sameCost(kept.stream()
                .filter(one -> one.before().isEmpty()).toList());
        return rule.map(found -> new KeeperRule(found.kind(), found.amount(), found.minimum(),
                undrafted.filter(cost -> !found.minimum().equals(Optional.of(cost))),
                Optional.empty()));
    }

    private static Optional<KeeperRule> roundsRule(List<Kept> drafted) {
        List<Integer> earlier = drafted.stream()
                .map(one -> one.before().orElseThrow() - one.cost())
                .distinct()
                .toList();
        if (earlier.size() == 1 && earlier.getFirst() >= 0) {
            int rounds = earlier.getFirst();
            return Optional.of(rule(rounds == 0 ? KeeperRule.Kind.SAME
                    : KeeperRule.Kind.ROUNDS_EARLIER, rounds, Optional.empty()));
        }
        return sameCost(drafted).map(round -> rule(KeeperRule.Kind.FIXED_ROUND, round,
                Optional.empty()));
    }

    private static Optional<KeeperRule> auctionRule(List<Kept> drafted) {
        Optional<KeeperRule> dollars = mostCommon(drafted, one ->
                one.cost() - one.before().orElseThrow())
                .filter(raise -> raise >= 0)
                .flatMap(raise -> fitted(drafted, price -> price + raise,
                        raise == 0 ? KeeperRule.Kind.SAME : KeeperRule.Kind.PLUS_DOLLARS,
                        raise));
        if (dollars.isPresent()) {
            return dollars;
        }
        return mostCommon(drafted.stream().filter(one -> one.before().orElseThrow() > 0)
                        .toList(),
                one -> (int) Math.round(100.0 * (one.cost() - one.before().orElseThrow())
                        / one.before().orElseThrow()))
                .filter(percent -> percent > 0)
                .flatMap(percent -> fitted(drafted,
                        price -> (int) Math.ceil(price * (100 + percent) / 100.0),
                        KeeperRule.Kind.PLUS_PERCENT, percent));
    }

    /**
     * Returns the rule when the raise explains every keeper, those under
     * one shared floor included, or empty when it doesn't.
     */
    private static Optional<KeeperRule> fitted(List<Kept> drafted, IntUnaryOperator raise,
            KeeperRule.Kind kind, int amount) {
        List<Kept> raised = drafted.stream()
                .filter(one -> raise.applyAsInt(one.before().orElseThrow()) == one.cost())
                .toList();
        List<Kept> floored = drafted.stream().filter(one -> !raised.contains(one)).toList();
        if (raised.size() < MINIMUM_KEEPERS) {
            return Optional.empty();
        }
        if (floored.isEmpty()) {
            return Optional.of(rule(kind, amount, Optional.empty()));
        }
        Optional<Integer> floor = sameCost(floored);
        boolean fits = floor.isPresent()
                && floored.stream().allMatch(one ->
                        raise.applyAsInt(one.before().orElseThrow()) < floor.get())
                && raised.stream().allMatch(one -> one.cost() >= floor.get());
        return fits ? Optional.of(rule(kind, amount, floor)) : Optional.empty();
    }

    private static Optional<Integer> mostCommon(List<Kept> drafted,
            ToIntFunction<Kept> measure) {
        Map<Integer, Long> counts = drafted.stream()
                .collect(Collectors.groupingBy(measure::applyAsInt, Collectors.counting()));
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= MINIMUM_KEEPERS)
                .max(Comparator.<Map.Entry<Integer, Long>>comparingLong(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey, Comparator.reverseOrder()))
                .map(Map.Entry::getKey);
    }

    private static Optional<Integer> sameCost(List<Kept> kept) {
        List<Integer> costs = kept.stream().map(Kept::cost).distinct().toList();
        return costs.size() == 1 ? Optional.of(costs.getFirst()) : Optional.empty();
    }

    private static KeeperRule rule(KeeperRule.Kind kind, int amount, Optional<Integer> minimum) {
        return new KeeperRule(kind, amount, minimum, Optional.empty(), Optional.empty());
    }
}
