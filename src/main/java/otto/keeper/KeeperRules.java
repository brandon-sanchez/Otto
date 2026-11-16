package otto.keeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JavaType;
import org.springframework.stereotype.Component;

import otto.sleeper.SleeperDrafts;
import otto.sleeper.SourceResult;
import otto.storage.JsonStore;
import otto.storage.OttoJson;

/**
 * Each keeper league's rule for pricing keepers: the one the owner set
 * in chat, stored per league, and the one in force when he hasn't set
 * one. The stored document also remembers whether an answer has asked
 * him for the rule, so Otto asks once.
 */
@Component
public class KeeperRules {

    /** How many seasons of drafts are read: enough to count years kept and propose a rule. */
    static final int SEASONS_READ = 5;

    private static final String DOCUMENT = "keeper-rules";

    private static final String ASK_FOR_RULE = "How does your league price keepers? For "
            + "example, the same round as last season, a round earlier for each year kept, "
            + "one fixed round, free, or last season's auction price plus some dollars or a "
            + "percent.";

    private static final JavaType LIST_TYPE = OttoJson.MAPPER.getTypeFactory()
            .constructCollectionType(List.class, Stored.class);

    private final JsonStore store;
    private final SleeperDrafts drafts;

    public KeeperRules(JsonStore store, SleeperDrafts drafts) {
        this.store = store;
        this.drafts = drafts;
    }

    /**
     * One league's stored keeper rule.
     *
     * @param rule the rule the owner set, empty when he hasn't
     * @param asked true once an answer has asked him for the rule
     */
    record Stored(String leagueId, Optional<KeeperRule> rule, boolean asked) {
    }

    /** Where the rule in force comes from. */
    public enum Basis {
        /** The owner set it in chat. */
        SET,
        /** Read off the league's drafts, waiting for the owner to confirm it. */
        PROPOSED,
        /** The most common rule, assumed because the owner hasn't set one. */
        ASSUMED,
        /** The league has no finished draft, so keepers are treated as free. */
        NO_DRAFT,
        /** Sleeper couldn't be read, so keepers are treated as free. */
        UNREADABLE
    }

    /**
     * The rule in force for one league.
     *
     * @param rule the rule costs are priced by; free when there is no
     *        draft to price from
     * @param drafts the league's completed drafts, newest first; empty
     *        when there are none or Sleeper couldn't be read
     * @param proposal the rule the drafts follow, empty when they show
     *        none
     * @param set true when the owner has set a rule
     * @param asked true once an answer has asked the owner for his rule
     */
    public record InForce(KeeperRule rule, Basis basis, List<SleeperDrafts.Draft> drafts,
            Optional<KeeperRule> proposal, boolean set, boolean asked) {

        /** Returns true when the league's latest draft was an auction. */
        public boolean auction() {
            return !drafts.isEmpty()
                    && drafts.getFirst().type() == SleeperDrafts.DraftType.AUCTION;
        }

        /** Returns where the rule comes from and what it is, in a sentence. */
        public String sentence() {
            return switch (basis) {
                case SET -> "Your league's keeper rule, as you told me: %s."
                        .formatted(rule.describe(auction()));
                case PROPOSED -> ("You haven't told me your league's keeper rule, but your "
                        + "%s and %s drafts look like %s, so I'm using that.").formatted(
                                drafts.get(1).season(), drafts.getFirst().season(),
                                rule.describe(auction()));
                case ASSUMED -> ("You haven't told me how your league prices keepers, so I'm "
                        + "assuming the most common rule: %s.").formatted(rule.describe(auction()));
                case NO_DRAFT -> "I couldn't find a finished draft for this league on Sleeper, "
                        + "so I'm treating keepers as free: keeping a player costs only the "
                        + "keeper spot.";
                case UNREADABLE -> "I couldn't read this league's drafts from Sleeper just now, "
                        + "so I'm treating keepers as free: keeping a player costs only the "
                        + "keeper spot.";
            };
        }

        /**
         * Returns the question that asks the owner for his league's rule,
         * or empty once he has set it or an answer has asked.
         */
        public Optional<String> question() {
            if (set || asked) {
                return Optional.empty();
            }
            return Optional.of(basis == Basis.PROPOSED
                    ? "Is that your league's rule: %s?".formatted(rule.describe(auction()))
                    : ASK_FOR_RULE);
        }
    }

    /**
     * Returns the rule in force for the league: the owner's when he set
     * one, else the one its drafts follow, else the most common rule;
     * free when there is no draft to price from.
     *
     * <p>Side effects: reads up to {@value #SEASONS_READ} seasons of the
     * league's drafts from Sleeper, cached for hours.
     */
    public InForce inForce(String leagueId) {
        Optional<Stored> stored = stored(leagueId);
        Optional<KeeperRule> set = stored.flatMap(Stored::rule);
        boolean asked = stored.map(Stored::asked).orElse(false);
        Optional<KeeperRule> setFree = set.filter(rule -> rule.kind() == KeeperRule.Kind.FREE);
        return switch (drafts.completedDrafts(leagueId, SEASONS_READ)) {
            case SourceResult.Unavailable<List<SleeperDrafts.Draft>> none -> new InForce(
                    setFree.orElse(KeeperRule.FREE),
                    setFree.isPresent() ? Basis.SET : Basis.UNREADABLE,
                    List.of(), Optional.empty(), set.isPresent(), asked);
            case SourceResult.Ok<List<SleeperDrafts.Draft>> ok -> {
                List<SleeperDrafts.Draft> found = ok.value();
                Optional<KeeperRule> proposal = RuleFinder.propose(found);
                if (setFree.isPresent() || (set.isPresent() && !found.isEmpty())) {
                    yield new InForce(set.get(), Basis.SET, found, proposal, true, asked);
                }
                if (found.isEmpty()) {
                    yield new InForce(KeeperRule.FREE, Basis.NO_DRAFT, found, proposal,
                            set.isPresent(), asked);
                }
                yield proposal
                        .map(rule -> new InForce(rule, Basis.PROPOSED, found, proposal, false,
                                asked))
                        .orElseGet(() -> new InForce(KeeperRule.ASSUMED, Basis.ASSUMED, found,
                                proposal, false, asked));
            }
        };
    }

    /**
     * Stores the owner's rule for the league, replacing any earlier one.
     *
     * <p>Side effects: writes the stored document.
     */
    public synchronized void set(String leagueId, KeeperRule rule) {
        boolean asked = stored(leagueId).map(Stored::asked).orElse(false);
        write(leagueId, new Stored(leagueId, Optional.of(rule), asked));
    }

    /**
     * Forgets the owner's rule for the league, so the rule in force goes
     * back to the one its drafts follow or the most common one.
     *
     * <p>Side effects: writes the stored document.
     */
    public synchronized void clear(String leagueId) {
        boolean asked = stored(leagueId).map(Stored::asked).orElse(false);
        write(leagueId, new Stored(leagueId, Optional.empty(), asked));
    }

    /**
     * Records that an answer has asked the owner for the league's rule.
     *
     * <p>Side effects: writes the stored document.
     */
    public synchronized void markAsked(String leagueId) {
        Optional<KeeperRule> rule = stored(leagueId).flatMap(Stored::rule);
        write(leagueId, new Stored(leagueId, rule, true));
    }

    private Optional<Stored> stored(String leagueId) {
        return all().stream().filter(one -> one.leagueId().equals(leagueId)).findFirst();
    }

    private void write(String leagueId, Stored changed) {
        List<Stored> leagues = new ArrayList<>(all().stream()
                .filter(one -> !one.leagueId().equals(leagueId))
                .toList());
        leagues.add(changed);
        store.write(DOCUMENT, leagues);
    }

    private List<Stored> all() {
        return store.<List<Stored>>read(DOCUMENT, LIST_TYPE).orElseGet(List::of);
    }
}
