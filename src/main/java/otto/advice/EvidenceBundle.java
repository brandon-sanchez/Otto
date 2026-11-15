package otto.advice;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;

import otto.records.Evidence;
import otto.records.Versions;
import otto.storage.OttoJson;

/**
 * Everything the model may know when it writes one piece of advice.
 * Facts may come only from here, and {@link AdviceCheck} holds every
 * answer to it.
 *
 * @param task what the model is asked to write, in plain words
 * @param legalMoves every lineup change code verified as legal
 * @param noChangeAllowed whether recommending no change is legal
 * @param kickoffPhrase the words the message must use for the time
 *        left before the game, or empty when the message need not say
 * @param maxLength the longest message allowed, in characters
 * @param budget the verified waiver budget left, in dollars, or empty
 *        when none was verified, in which case no dollar amount may be
 *        stated
 * @param rosterPlans every roster plan code verified as legal for a
 *        player decision, of which the answer must choose one; empty
 *        for advice that is not a player decision
 * @param unknowns the ids of evidence items that name a fact the
 *        advice depends on and no source supplied; the answer must be
 *        conditional on each one
 */
public record EvidenceBundle(
        String task,
        List<EvidenceItem> evidence,
        List<BundlePlayer> players,
        List<LineupMove> legalMoves,
        boolean noChangeAllowed,
        Optional<String> kickoffPhrase,
        int maxLength,
        Optional<Integer> budget,
        List<RosterPlan> rosterPlans,
        List<String> unknowns) {

    public EvidenceBundle {
        evidence = List.copyOf(evidence);
        players = List.copyOf(players);
        legalMoves = List.copyOf(legalMoves);
        rosterPlans = List.copyOf(rosterPlans);
        unknowns = List.copyOf(unknowns);
        if (evidence.stream().map(EvidenceItem::id).distinct().count() != evidence.size()) {
            throw new IllegalArgumentException("Evidence ids repeat within a bundle");
        }
    }

    public Optional<EvidenceItem> item(String id) {
        return evidence.stream().filter(item -> item.id().equals(id)).findFirst();
    }

    public Optional<RosterPlan> rosterPlan(String id) {
        return rosterPlans.stream().filter(plan -> plan.id().equals(id)).findFirst();
    }

    public Optional<BundlePlayer> player(String playerId) {
        return players.stream().filter(player -> player.playerId().equals(playerId)).findFirst();
    }

    /**
     * The evidence fact that says how far the current season has come
     * beside a last-season fact, such as "beside 3 completed weeks of
     * 2026". It changes as games finish while the fact it labels does not,
     * so {@link #identity()} leaves it out.
     */
    public static final String SEASON_PROGRESS_FACT = "seasonProgress";

    /**
     * A hash of what the evidence says, leaving out when it was read, how
     * long is left before kickoff and how far the season has come beside
     * a last-season fact. Two runs over the same facts share it, so advice
     * is never retried against unchanged evidence.
     */
    public String identity() {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("task", task);
        facts.put("evidence", evidence.stream()
                .map(item -> {
                    Map<String, String> said = new TreeMap<>(item.facts());
                    said.remove(SEASON_PROGRESS_FACT);
                    return List.of(item.id(), item.source(), item.scoringBasis(), said);
                })
                .toList());
        facts.put("players", players);
        facts.put("legalMoves", legalMoves);
        facts.put("noChangeAllowed", noChangeAllowed);
        facts.put("budget", budget);
        if (!rosterPlans.isEmpty()) {
            facts.put("rosterPlans", rosterPlans);
        }
        if (!unknowns.isEmpty()) {
            facts.put("unknowns", unknowns);
        }
        try {
            return Sha256.hex(OttoJson.MAPPER.writeValueAsString(facts));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot fingerprint an evidence bundle", e);
        }
    }

    /**
     * Returns the bundle's evidence as a Decision Record keeps it: every
     * item read, with no copied text.
     */
    public List<Evidence> recorded() {
        return evidence.stream()
                .map(item -> Evidence.read(item.id(), item.source(), item.publishedAt(),
                        item.retrievedAt(), item.scoringBasis()))
                .toList();
    }

    /**
     * Returns the versions of a run that asked the model about this
     * bundle: its {@link #identity} as the data version, the form's
     * prompt version and the model that answered.
     */
    public Versions versions(AdviceForm<?> form, AdviceResult<?> result) {
        return Versions.ofThisBuild(identity(), form.promptVersion(), result.model());
    }

    /** Returns every number and rank the evidence and the kickoff phrase state. */
    Set<Quantity> quantities() {
        Set<Quantity> quantities = new HashSet<>(Quantity.of(evidence.stream()));
        quantities.addAll(Quantity.amountsOf(kickoffPhrase.stream()));
        return quantities;
    }
}
