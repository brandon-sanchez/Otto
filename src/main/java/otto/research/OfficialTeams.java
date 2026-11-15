package otto.research;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Optional;

/**
 * The official website of each NFL team, keyed by Sleeper's team
 * abbreviation. A story counts as the team's own announcement only
 * when its address is on the site of the team the player plays for:
 * the address is what ties the words to the team, since anyone can
 * name a team as the source.
 */
final class OfficialTeams {

    /**
     * A team's site and what the team is called in a message.
     *
     * @param host the site's host in {@link CanonicalUrl} form, without
     *        {@code www.}
     */
    record Team(String host, String name) {
    }

    private static final Map<String, Team> BY_ABBREVIATION = Map.ofEntries(
            Map.entry("ARI", new Team("azcardinals.com", "Cardinals")),
            Map.entry("ATL", new Team("atlantafalcons.com", "Falcons")),
            Map.entry("BAL", new Team("baltimoreravens.com", "Ravens")),
            Map.entry("BUF", new Team("buffalobills.com", "Bills")),
            Map.entry("CAR", new Team("panthers.com", "Panthers")),
            Map.entry("CHI", new Team("chicagobears.com", "Bears")),
            Map.entry("CIN", new Team("bengals.com", "Bengals")),
            Map.entry("CLE", new Team("clevelandbrowns.com", "Browns")),
            Map.entry("DAL", new Team("dallascowboys.com", "Cowboys")),
            Map.entry("DEN", new Team("denverbroncos.com", "Broncos")),
            Map.entry("DET", new Team("detroitlions.com", "Lions")),
            Map.entry("GB", new Team("packers.com", "Packers")),
            Map.entry("HOU", new Team("houstontexans.com", "Texans")),
            Map.entry("IND", new Team("colts.com", "Colts")),
            Map.entry("JAX", new Team("jaguars.com", "Jaguars")),
            Map.entry("KC", new Team("chiefs.com", "Chiefs")),
            Map.entry("LV", new Team("raiders.com", "Raiders")),
            Map.entry("LAC", new Team("chargers.com", "Chargers")),
            Map.entry("LAR", new Team("therams.com", "Rams")),
            Map.entry("MIA", new Team("miamidolphins.com", "Dolphins")),
            Map.entry("MIN", new Team("vikings.com", "Vikings")),
            Map.entry("NE", new Team("patriots.com", "Patriots")),
            Map.entry("NO", new Team("neworleanssaints.com", "Saints")),
            Map.entry("NYG", new Team("giants.com", "Giants")),
            Map.entry("NYJ", new Team("newyorkjets.com", "Jets")),
            Map.entry("PHI", new Team("philadelphiaeagles.com", "Eagles")),
            Map.entry("PIT", new Team("steelers.com", "Steelers")),
            Map.entry("SEA", new Team("seahawks.com", "Seahawks")),
            Map.entry("SF", new Team("49ers.com", "49ers")),
            Map.entry("TB", new Team("buccaneers.com", "Buccaneers")),
            Map.entry("TEN", new Team("tennesseetitans.com", "Titans")),
            Map.entry("WAS", new Team("commanders.com", "Commanders")));

    private OfficialTeams() {
    }

    /** Returns every team, keyed by Sleeper's abbreviation. */
    static Map<String, Team> all() {
        return BY_ABBREVIATION;
    }

    /**
     * Returns the team when the canonical address is on that team's own
     * site, and empty for any other address, a blank team or an
     * abbreviation Sleeper does not use.
     */
    static Optional<Team> publishedBy(String team, String canonicalUrl) {
        if (team == null || canonicalUrl == null) {
            return Optional.empty();
        }
        Team official = BY_ABBREVIATION.get(team);
        if (official == null) {
            return Optional.empty();
        }
        try {
            String host = new URI(canonicalUrl).getHost();
            return official.host().equals(host) ? Optional.of(official) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
