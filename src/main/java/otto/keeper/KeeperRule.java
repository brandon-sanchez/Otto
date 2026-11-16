package otto.keeper;

import java.util.Locale;
import java.util.Optional;

import otto.sleeper.SleeperDrafts;

/**
 * How a keeper league prices keeping a player for next season. Sleeper
 * never records it, since commissioners enforce it by hand, so the owner
 * states it in chat or Otto reads it off the league's drafts.
 *
 * @param amount the rounds earlier per year kept, the fixed round, the
 *        dollars added or the percent added; 0 for the kinds that take
 *        none
 * @param minimum the cheapest a keeper may cost: a floor in dollars, or
 *        the latest round he may cost; empty when the league has none
 * @param undrafted what a player nobody drafted costs, a round or
 *        dollars; empty for the last round, or $1 in an auction
 * @param yearLimit the most seasons in a row a player may be kept, empty
 *        when the league sets no limit
 */
public record KeeperRule(Kind kind, int amount, Optional<Integer> minimum,
        Optional<Integer> undrafted, Optional<Integer> yearLimit) {

    private static final int AUCTION_MINIMUM = 1;

    /** The rule Otto assumes until the owner says otherwise: the round or price he cost last. */
    public static final KeeperRule ASSUMED = new KeeperRule(Kind.SAME, 0, Optional.empty(),
            Optional.empty(), Optional.empty());

    /** Keepers cost no draft pick, only the keeper spot, worth the teammate kept instead. */
    public static final KeeperRule FREE = new KeeperRule(Kind.FREE, 0, Optional.empty(),
            Optional.empty(), Optional.empty());

    /** The kinds of keeper rule, named as the chat names them. */
    public enum Kind {
        /** The round he was drafted in, or the price he went for, the season before. */
        SAME(false, false),
        /** That many rounds earlier than the round he cost the season before. */
        ROUNDS_EARLIER(true, false),
        /** One round for every keeper. */
        FIXED_ROUND(true, false),
        /** No pick or dollars; only the keeper spot. */
        FREE(false, false),
        /** The price he went for the season before plus that many dollars. */
        PLUS_DOLLARS(true, true),
        /** The price he went for the season before plus that percent. */
        PLUS_PERCENT(true, true);

        private final boolean takesAmount;
        private final boolean auctionOnly;

        Kind(boolean takesAmount, boolean auctionOnly) {
            this.takesAmount = takesAmount;
            this.auctionOnly = auctionOnly;
        }

        /** Returns true when the rule needs an amount, such as how many rounds earlier. */
        public boolean takesAmount() {
            return takesAmount;
        }

        /** Returns true when the rule only makes sense in an auction draft. */
        public boolean auctionOnly() {
            return auctionOnly;
        }

        /** Returns the kind the chat names, such as "plus_dollars", or empty for an unknown one. */
        public static Optional<Kind> named(String name) {
            String wanted = name == null ? "" : name.strip().toUpperCase(Locale.ROOT)
                    .replace(' ', '_').replace('-', '_');
            for (Kind kind : values()) {
                if (kind.name().equals(wanted)) {
                    return Optional.of(kind);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Returns what keeping a player costs next season, in rounds or
     * dollars as the draft counts them.
     *
     * @param last the round or price he cost in the draft, empty when
     *        nobody drafted him
     */
    public int next(Optional<Integer> last, SleeperDrafts.Draft draft) {
        boolean auction = draft.type() == SleeperDrafts.DraftType.AUCTION;
        int cost = switch (kind) {
            case FIXED_ROUND -> amount;
            case SAME, FREE -> last.orElseGet(() -> undraftedCost(draft));
            case ROUNDS_EARLIER -> last.map(round -> round - amount)
                    .orElseGet(() -> undraftedCost(draft));
            case PLUS_DOLLARS -> last.map(price -> price + amount)
                    .orElseGet(() -> undraftedCost(draft));
            case PLUS_PERCENT -> last.map(price -> (int) Math.ceil(price * (100 + amount) / 100.0))
                    .orElseGet(() -> undraftedCost(draft));
        };
        if (auction) {
            int floored = Math.max(cost, minimum.orElse(AUCTION_MINIMUM));
            return Math.min(Math.max(floored, AUCTION_MINIMUM),
                    draft.budget().orElse(Integer.MAX_VALUE));
        }
        int capped = Math.min(cost, minimum.orElse(draft.rounds()));
        return Math.min(Math.max(capped, 1), draft.rounds());
    }

    private int undraftedCost(SleeperDrafts.Draft draft) {
        return undrafted.orElse(draft.type() == SleeperDrafts.DraftType.AUCTION
                ? AUCTION_MINIMUM : draft.rounds());
    }

    /**
     * Returns the rule in the owner's words, such as "a keeper costs what
     * he went for the season before plus $5, and never less than $20".
     *
     * @param auction true to word it for an auction draft
     */
    public String describe(boolean auction) {
        StringBuilder words = new StringBuilder(switch (kind) {
            case SAME -> auction ? "a keeper costs what he went for the season before"
                    : "a keeper costs the round he was drafted in the season before";
            case ROUNDS_EARLIER -> "a keeper costs %d %s earlier than the round he cost the "
                    .formatted(amount, amount == 1 ? "round" : "rounds") + "season before";
            case FIXED_ROUND -> "every keeper costs a round %d pick".formatted(amount);
            case FREE -> "keepers cost no draft pick, only the keeper spot they use, which is "
                    + "worth the best player the team would keep instead";
            case PLUS_DOLLARS -> "a keeper costs what he went for the season before plus $%d"
                    .formatted(amount);
            case PLUS_PERCENT -> "a keeper costs what he went for the season before plus %d%%"
                    .formatted(amount);
        });
        if (kind == Kind.FREE) {
            return words.toString();
        }
        minimum.ifPresent(floor -> words.append(auction
                ? ", never less than $%d".formatted(floor)
                : ", never later than round %d".formatted(floor)));
        if (kind != Kind.FIXED_ROUND) {
            words.append(undrafted
                    .map(cost -> auction ? ", and an undrafted player costs $%d".formatted(cost)
                            : ", and an undrafted player costs a round %d pick".formatted(cost))
                    .orElse(auction ? ", and an undrafted player costs $1"
                            : ", and an undrafted player costs the last round"));
        }
        yearLimit.ifPresent(limit -> words.append(", and a player can be kept at most %d %s in a row"
                .formatted(limit, limit == 1 ? "season" : "seasons")));
        return words.toString();
    }
}
