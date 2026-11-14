package otto.alerts;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import otto.advice.EvidenceItem;
import otto.advice.RosterPlan;
import otto.decisions.PlayerAvailability;
import otto.decisions.RosterPlans;
import otto.directory.PlayerHealth;
import otto.research.OfficialUpdate;

/**
 * Writes the three separate facts of a player decision into its
 * evidence: whether he plays, the designation Sleeper shows, and the
 * moves Sleeper allows. When the team and Sleeper disagree, the
 * disagreement is an unknown the advice must be conditional on, so the
 * message says it rather than picking a side in silence.
 */
final class AvailabilityEvidence {

    static final String SOURCES_DISAGREE = "sources-disagree";

    private AvailabilityEvidence() {
    }

    /**
     * Adds the three facts and any disagreement.
     *
     * @return the ids of the unknowns added: the disagreement, or none
     */
    static List<String> add(LineupEvidence builder, String name, PlayerAvailability availability,
            RosterPlans.Plans plans, Instant sleeperReadAt, Instant now) {
        String designation = RosterPlans.designation(availability.designation());
        builder.addItem(footballFact(name, availability, sleeperReadAt));

        Map<String, String> sleeper = new LinkedHashMap<>();
        sleeper.put("player", name);
        sleeper.put("designation", designation);
        builder.addItem(new EvidenceItem("sleeper-designation", "sleeper:players",
                Optional.empty(), sleeperReadAt,
                "the designation Sleeper shows, which decides the roster moves Sleeper allows",
                sleeper));

        Map<String, String> moves = new LinkedHashMap<>();
        moves.put("legalPlans", plans.legal().stream().map(RosterPlan::id)
                .collect(Collectors.joining(", ")));
        moves.put("decidedBy", "Sleeper's %s designation and this league's roster rules; a team "
                .formatted(designation) + "announcement opens no move Sleeper does not allow");
        builder.addItem(new EvidenceItem("move-eligibility", "otto:roster-rules",
                Optional.empty(), now, "the roster moves Sleeper allows him now", moves));

        if (!availability.sourcesDisagree()) {
            return List.of();
        }
        builder.addItem(new EvidenceItem(SOURCES_DISAGREE, "otto:source-check", Optional.empty(),
                now, "his team and Sleeper disagree about whether he plays",
                Map.of("missing", disagreement(name, availability))));
        return List.of(SOURCES_DISAGREE);
    }

    /**
     * The verified facts for a team announcement Sleeper has not caught
     * up with, as code writes them when the advice cannot be had.
     */
    static String announced(String name, PlayerAvailability availability) {
        OfficialUpdate update = availability.announcement().orElseThrow();
        return "The %s announced that %s %s. Sleeper still lists him as %s, so Sleeper allows "
                .formatted(update.teamName(), name, plays(update),
                        RosterPlans.designation(availability.designation()))
                + "only the moves that designation allows until it changes.";
    }

    /**
     * The disagreement as a sentence to follow a status change, or empty
     * when the sources agree.
     */
    static String disagreementSuffix(PlayerAvailability availability) {
        if (!availability.sourcesDisagree()) {
            return "";
        }
        OfficialUpdate update = availability.announcement().orElseThrow();
        return " The %s said earlier that he %s.".formatted(update.teamName(), plays(update));
    }

    private static EvidenceItem footballFact(String name, PlayerAvailability availability,
            Instant sleeperReadAt) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("player", name);
        if (availability.announcementIsNewer()) {
            OfficialUpdate update = availability.announcement().orElseThrow();
            facts.put("plays", plays(update));
            facts.put("announcedBy", update.teamName());
            facts.put("announcement", update.statement());
            return new EvidenceItem("football-fact", "official:" + update.host(),
                    Optional.of(update.published()), sleeperReadAt,
                    "whether he plays, from his team's own announcement, newer than Sleeper's "
                            + "designation", facts);
        }
        PlayerHealth designation = availability.designation();
        facts.put("plays", designation.rulesOutPlaying() ? "will not play"
                : designation.isWorseThan(PlayerHealth.PROBABLE) ? "in doubt" : "will play");
        facts.put("designation", RosterPlans.designation(designation));
        return new EvidenceItem("football-fact", "sleeper:players", Optional.empty(),
                sleeperReadAt, "whether he plays, from Sleeper's designation, the newest word on it",
                facts);
    }

    private static String disagreement(String name, PlayerAvailability availability) {
        OfficialUpdate update = availability.announcement().orElseThrow();
        String designation = RosterPlans.designation(availability.designation());
        if (availability.announcementIsNewer()) {
            return "Sleeper has not yet matched the %s' announcement that %s %s: it still "
                    .formatted(update.teamName(), name, plays(update))
                    + "lists him as %s, and allows only the moves that designation allows until "
                            .formatted(designation)
                    + "it changes";
        }
        return "the %s said earlier that %s %s (%s), and Sleeper's later designation lists him "
                .formatted(update.teamName(), name, plays(update), update.statement())
                + "as " + designation;
    }

    private static String plays(OfficialUpdate update) {
        return update.availability() == OfficialUpdate.Availability.WILL_NOT_PLAY
                ? "will not play" : "will play";
    }
}
