package otto.keeper;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.advice.ToolAnswer;
import otto.sleeper.SleeperAdapter;

/**
 * The owner's keeper rule, shown, set, confirmed or cleared in chat. The
 * rule is stored per league and replaces whatever Otto assumed or read
 * off the league's drafts.
 */
@Component
public class KeeperRuleChat {

    private final KeeperRules rules;

    public KeeperRuleChat(KeeperRules rules) {
        this.rules = rules;
    }

    /**
     * What the chat sees after an action.
     *
     * @param outcome what the action did, in a sentence
     * @param rule the rule in force and where it comes from, in a sentence
     */
    public record View(String outcome, String rule) {
    }

    /**
     * The owner's rule as he states it.
     *
     * @param kind same, rounds_earlier, fixed_round, free, plus_dollars or
     *        plus_percent
     * @param amount the rounds earlier per year, the fixed round, the
     *        dollars or the percent; null for same and free
     * @param minimum the cheapest a keeper may cost, dollars or the
     *        latest round; null for none
     * @param undrafted what an undrafted player costs, a round or
     *        dollars; null for the last round or $1
     * @param yearLimit the most seasons in a row a player may be kept;
     *        null for no limit
     */
    public record Stated(String kind, Integer amount, Integer minimum, Integer undrafted,
            Integer yearLimit) {
    }

    /**
     * Applies one chat action to the league's keeper rule.
     *
     * <p>Side effects: "set", "confirm" and "clear" write the stored
     * rules; every action reads the league's drafts from Sleeper.
     *
     * @param action show, set, confirm or clear; blank shows
     * @param stated the rule to set; read only by "set"
     * @return unavailable outside a keeper league, for an unknown action
     *         or rule, for an auction rule in a league that drafts in
     *         rounds, and for a confirm with no rule read off the drafts
     */
    public ToolAnswer<View> apply(SleeperAdapter.League league, String action, Stated stated) {
        if (!league.rules().keeper()) {
            return ToolAnswer.unavailable("this isn't a keeper league, so it has no keeper rule");
        }
        String leagueId = league.leagueId();
        String verb = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "show", "" -> ToolAnswer.of(new View("Here's how I price keepers in your "
                    + "league.", rules.inForce(leagueId).sentence()));
            case "set" -> set(leagueId, stated);
            case "confirm" -> confirm(leagueId);
            case "clear" -> {
                rules.clear(leagueId);
                yield ToolAnswer.of(new View("I've forgotten the keeper rule you gave me.",
                        rules.inForce(leagueId).sentence()));
            }
            default -> ToolAnswer.unavailable(
                    "unknown action \"%s\"; use show, set, confirm or clear".formatted(action));
        };
    }

    private ToolAnswer<View> set(String leagueId, Stated stated) {
        Optional<KeeperRule.Kind> kind = KeeperRule.Kind.named(stated.kind());
        if (kind.isEmpty()) {
            return ToolAnswer.unavailable(("\"%s\" isn't a keeper rule I know; use same, "
                    + "rounds_earlier, fixed_round, free, plus_dollars or plus_percent")
                    .formatted(stated.kind()));
        }
        if (kind.get().takesAmount() && (stated.amount() == null || stated.amount() < 1)) {
            return ToolAnswer.unavailable("that rule needs a whole number of at least 1: the "
                    + "rounds earlier, the fixed round, the dollars or the percent");
        }
        KeeperRules.InForce before = rules.inForce(leagueId);
        if (kind.get().auctionOnly() && !before.drafts().isEmpty() && !before.auction()) {
            return ToolAnswer.unavailable("your league drafts in rounds, not an auction, so a "
                    + "keeper can't cost dollars; give me a rule in rounds");
        }
        KeeperRule rule = new KeeperRule(kind.get(),
                kind.get().takesAmount() ? stated.amount() : 0,
                positive(stated.minimum()), positive(stated.undrafted()),
                positive(stated.yearLimit()));
        rules.set(leagueId, rule);
        return ToolAnswer.of(new View("Got it, I'll price your keepers by that rule from now "
                + "on.", rules.inForce(leagueId).sentence()));
    }

    private ToolAnswer<View> confirm(String leagueId) {
        Optional<KeeperRule> proposal = rules.inForce(leagueId).proposal();
        if (proposal.isEmpty()) {
            return ToolAnswer.unavailable("I haven't read a keeper rule off your league's "
                    + "drafts, so there's nothing to confirm; tell me the rule instead");
        }
        rules.set(leagueId, proposal.get());
        return ToolAnswer.of(new View("Thanks, that's your league's keeper rule now.",
                rules.inForce(leagueId).sentence()));
    }

    private static Optional<Integer> positive(Integer value) {
        return Optional.ofNullable(value).filter(number -> number > 0);
    }
}
