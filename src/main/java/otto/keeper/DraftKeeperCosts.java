package otto.keeper;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import otto.market.KeeperCost;
import otto.market.KeeperCosts;
import otto.market.KeeperScale;
import otto.sleeper.SleeperAdapter;
import otto.sleeper.SleeperDrafts;

/**
 * Prices keepers from the league's latest finished draft and its keeper
 * rule. A kept player costs next season what the rule makes of his round
 * or price in that draft, and that pick or those dollars cost what the
 * dynasty market says they buy. In season the latest finished draft is
 * this season's; before the draft it is last season's.
 */
@Component
public class DraftKeeperCosts implements KeeperCosts {

    private final KeeperRules rules;

    public DraftKeeperCosts(KeeperRules rules) {
        this.rules = rules;
    }

    @Override
    public Pricing pricing(SleeperAdapter.League league) {
        KeeperRules.InForce inForce = rules.inForce(league.leagueId());
        if (inForce.rule().kind() == KeeperRule.Kind.FREE) {
            return (playerId, scale) -> free(inForce, league, scale);
        }
        return (playerId, scale) -> priced(inForce, playerId, scale);
    }

    @Override
    public void asked(String leagueId) {
        rules.markAsked(leagueId);
    }

    private static KeeperCost free(KeeperRules.InForce inForce, SleeperAdapter.League league,
            KeeperScale scale) {
        String spots = league.rules().keeperLimit()
                .map(limit -> "one of your %d keeper spots".formatted(limit))
                .orElse("a keeper spot");
        return new KeeperCost(scale.keeperLine(), true, ("Keeping him costs no draft pick, "
                + "only %s, which is worth about what the best player nobody keeps is worth "
                + "(%d).").formatted(spots, scale.keeperLine()), inForce.sentence(),
                inForce.question(), Optional.empty());
    }

    private static KeeperCost priced(KeeperRules.InForce inForce, String playerId,
            KeeperScale scale) {
        KeeperRule rule = inForce.rule();
        SleeperDrafts.Draft latest = inForce.drafts().getFirst();
        boolean auction = inForce.auction();
        Optional<Integer> budget = latest.budget();
        int kept = yearsKept(inForce.drafts(), playerId);
        if (rule.yearLimit().filter(limit -> kept >= limit).isPresent()) {
            return new KeeperCost(0, false, ("He can't be kept again: he has been kept %d %s "
                    + "in a row, your league's limit.").formatted(kept,
                    kept == 1 ? "season" : "seasons"), inForce.sentence(), inForce.question(),
                    budget);
        }
        Optional<SleeperDrafts.Pick> pick = latest.pickOf(playerId);
        Optional<Integer> last = pick.flatMap(drafted -> auction ? drafted.price()
                : Optional.of(drafted.round()));
        int next = rule.next(last, latest);
        int value = auction
                ? scale.dollars(next, latest.budget().orElseThrow(), latest.teams(),
                        latest.teams() * latest.rounds())
                : scale.pick(next, latest.teams());
        String cost = (auction ? "Keeping him would cost $%d next season" : "Keeping him "
                + "would cost a round %d pick next season").formatted(next)
                + why(pick, last, latest, auction) + yearsKeptNote(kept);
        return new KeeperCost(value, true, cost, inForce.sentence(), inForce.question(), budget);
    }

    private static String why(Optional<SleeperDrafts.Pick> pick, Optional<Integer> last,
            SleeperDrafts.Draft latest, boolean auction) {
        if (last.isEmpty()) {
            return ", since nobody drafted him in the %s draft.".formatted(latest.season());
        }
        boolean keeper = pick.orElseThrow().keeper();
        if (auction) {
            return "; he %s $%d in the %s auction.".formatted(keeper ? "was kept for" : "went for",
                    last.get(), latest.season());
        }
        return "; he %s round %d of the %s draft.".formatted(keeper ? "was kept with a pick in"
                : "went in", last.get(), latest.season());
    }

    private static String yearsKeptNote(int kept) {
        return kept == 0 ? "" : " He has been kept %d %s in a row.".formatted(kept,
                kept == 1 ? "season" : "seasons");
    }

    private static int yearsKept(List<SleeperDrafts.Draft> drafts, String playerId) {
        int kept = 0;
        for (SleeperDrafts.Draft draft : drafts) {
            if (draft.pickOf(playerId).filter(SleeperDrafts.Pick::keeper).isEmpty()) {
                break;
            }
            kept++;
        }
        return kept;
    }
}
