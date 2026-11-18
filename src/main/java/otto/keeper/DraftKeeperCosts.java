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
 * dynasty market says they buy. "Last season's draft" always means the
 * latest finished draft: during the season that is this season's draft,
 * and before the draft it is last season's.
 *
 * <p>A free keeper costs the keeper spot he uses on his own team: the
 * dynasty value of the best teammate who would otherwise be kept in it
 * (see {@link KeeperScale.KeeperSpot}). Only a team with too few other
 * keepable players to fill its spots keeps him for nothing.
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
            return (playerId, scale) -> free(inForce, playerId, scale);
        }
        return (playerId, scale) -> priced(inForce, playerId, scale);
    }

    @Override
    public void asked(String leagueId) {
        rules.markAsked(leagueId);
    }

    private static KeeperCost free(KeeperRules.InForce inForce, String playerId,
            KeeperScale scale) {
        Optional<KeeperCost> pastLimit = pastYearLimit(inForce, playerId, Optional.empty());
        if (pastLimit.isPresent()) {
            return pastLimit.get();
        }
        Optional<KeeperScale.KeeperSpot> spot = scale.spotOf(playerId);
        String cost = spot.map(DraftKeeperCosts::spotCost)
                .orElse("Keeping him costs no draft pick, only a keeper spot.");
        return new KeeperCost(spot.map(KeeperScale.KeeperSpot::value).orElse(0), true, cost,
                inForce.sentence(), inForce.question(), Optional.empty());
    }

    private static String spotCost(KeeperScale.KeeperSpot spot) {
        return spot.instead()
                .map(instead -> "Keeping him costs no draft pick, but he uses a keeper spot "
                        + "%s otherwise give to %s, worth %d.".formatted(
                                spot.usersTeam() ? "you'd" : "their team would", instead,
                                spot.value()))
                .orElseGet(() -> "Keeping him costs no draft pick, and %s no other player "
                        .formatted(spot.usersTeam() ? "you have" : "their team has")
                        + "worth a keeper spot, so the spot costs nothing.");
    }

    /** He can't be kept once he has been kept the league's limit of seasons in a row. */
    private static Optional<KeeperCost> pastYearLimit(KeeperRules.InForce inForce,
            String playerId, Optional<Integer> budget) {
        int kept = yearsKept(inForce.drafts(), playerId);
        if (inForce.rule().yearLimit().filter(limit -> kept >= limit).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new KeeperCost(0, false, ("He can't be kept again: he has been kept "
                + "%d %s in a row, your league's limit.").formatted(kept,
                kept == 1 ? "season" : "seasons"), inForce.sentence(), inForce.question(),
                budget));
    }

    private static KeeperCost priced(KeeperRules.InForce inForce, String playerId,
            KeeperScale scale) {
        KeeperRule rule = inForce.rule();
        SleeperDrafts.Draft latest = inForce.drafts().getFirst();
        boolean auction = inForce.auction();
        Optional<Integer> budget = latest.budget();
        Optional<KeeperCost> pastLimit = pastYearLimit(inForce, playerId, budget);
        if (pastLimit.isPresent()) {
            return pastLimit.get();
        }
        int kept = yearsKept(inForce.drafts(), playerId);
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
