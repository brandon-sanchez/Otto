package otto.ask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.team.LeagueDirections;
import otto.team.TeamDirection;

/**
 * Writes the evidence the direction answers are explained from: every
 * team's label and reason, in the words an answer may quote.
 */
final class TeamDirectionEvidence {

    private static final String SOURCE = "otto:team-direction";

    private static final String QUESTION = "Your numbers look like a rebuilding team. Are you "
            + "rebuilding? I won't treat you that way until you say so.";

    private TeamDirectionEvidence() {
    }

    static EvidenceBundle bundle(String leagueName, LeagueDirections league, int maxLength,
            Instant now) {
        List<EvidenceItem> evidence = new ArrayList<>();
        evidence.add(item("summary", now, summary(leagueName, league)));
        league.teams().forEach(team -> evidence.add(item("team-" + team.rosterId(), now,
                facts(team, league.market()))));
        return new EvidenceBundle(
                "Tell the user which teams in his league are contending, in the middle or "
                        + "rebuilding, and why.",
                evidence, List.of(), List.of(), true, Optional.empty(), maxLength,
                Optional.empty(), List.of(), List.of());
    }

    /** Returns the evidence item that carries the user's own direction in another answer. */
    static EvidenceItem userItem(TeamDirection user, Optional<String> market, Instant now) {
        return item("direction", now, facts(user, market));
    }

    private static Map<String, String> summary(String leagueName, LeagueDirections league) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("league", leagueName);
        summary.put("teams", "%d teams with players".formatted(league.teams().size()));
        summary.put("games", "%d games played".formatted(league.gamesPlayed()));
        summary.put("basis", league.market().isPresent()
                ? "each label weighs the team's summed market value against its record and "
                        + "points scored, and the record and points count for more as the "
                        + "season goes on"
                : "I couldn't read market values, so each label rests on the team's record "
                        + "and points scored alone");
        return summary;
    }

    private static Map<String, String> facts(TeamDirection team, Optional<String> market) {
        Map<String, String> facts = new LinkedHashMap<>();
        facts.put("manager", team.userTeam() ? "you" : team.manager());
        facts.put("direction", switch (team.basis()) {
            case MEASURED -> team.direction().words();
            case CHOSEN -> team.direction().words() + ", as you told me";
            case AWAITING_CONFIRMATION -> team.direction().words()
                    + " until you tell me otherwise";
        });
        if (team.basis() != TeamDirection.Basis.MEASURED) {
            facts.put("measured", team.measured().words());
        }
        facts.put("reason", team.reason());
        facts.put("pointsFor", "%s points scored".formatted(
                String.format(Locale.ROOT, "%.1f", team.record().pointsFor())));
        team.marketValue().ifPresent(value -> market.ifPresent(list -> facts.put("marketValue",
                "%d in market value for a %s league".formatted(value, list))));
        if (team.basis() == TeamDirection.Basis.AWAITING_CONFIRMATION) {
            facts.put("question", QUESTION);
        }
        return facts;
    }

    private static EvidenceItem item(String id, Instant now, Map<String, String> facts) {
        return new EvidenceItem(id, SOURCE, Optional.empty(), now,
                "team direction from market value, record and points", facts);
    }
}
