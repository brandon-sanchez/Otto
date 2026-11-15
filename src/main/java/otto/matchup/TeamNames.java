package otto.matchup;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import otto.nflverse.NflTeams;

/**
 * Reads a team as people write it - "Chargers", "LAC", "LA Chargers",
 * "Los Angeles Chargers" - into Sleeper's team code.
 *
 * A city two franchises share ("LA", "New York") names neither, so it
 * resolves to nothing rather than to a guess.
 */
public final class TeamNames {

    private record Team(String code, String city, String nickname, List<String> aliases) {
    }

    private static final List<Team> TEAMS = List.of(
            new Team("ARI", "Arizona", "Cardinals", List.of("cards")),
            new Team("ATL", "Atlanta", "Falcons", List.of()),
            new Team("BAL", "Baltimore", "Ravens", List.of()),
            new Team("BUF", "Buffalo", "Bills", List.of()),
            new Team("CAR", "Carolina", "Panthers", List.of()),
            new Team("CHI", "Chicago", "Bears", List.of()),
            new Team("CIN", "Cincinnati", "Bengals", List.of()),
            new Team("CLE", "Cleveland", "Browns", List.of()),
            new Team("DAL", "Dallas", "Cowboys", List.of()),
            new Team("DEN", "Denver", "Broncos", List.of()),
            new Team("DET", "Detroit", "Lions", List.of()),
            new Team("GB", "Green Bay", "Packers", List.of()),
            new Team("HOU", "Houston", "Texans", List.of()),
            new Team("IND", "Indianapolis", "Colts", List.of()),
            new Team("JAX", "Jacksonville", "Jaguars", List.of("jags")),
            new Team("KC", "Kansas City", "Chiefs", List.of()),
            new Team("LV", "Las Vegas", "Raiders", List.of()),
            new Team("LAC", "Los Angeles", "Chargers", List.of("la chargers", "bolts")),
            new Team("LAR", "Los Angeles", "Rams", List.of("la rams")),
            new Team("MIA", "Miami", "Dolphins", List.of("phins")),
            new Team("MIN", "Minnesota", "Vikings", List.of()),
            new Team("NE", "New England", "Patriots", List.of("pats")),
            new Team("NO", "New Orleans", "Saints", List.of()),
            new Team("NYG", "New York", "Giants", List.of("ny giants")),
            new Team("NYJ", "New York", "Jets", List.of("ny jets")),
            new Team("PHI", "Philadelphia", "Eagles", List.of()),
            new Team("PIT", "Pittsburgh", "Steelers", List.of()),
            new Team("SF", "San Francisco", "49ers", List.of("niners")),
            new Team("SEA", "Seattle", "Seahawks", List.of()),
            new Team("TB", "Tampa Bay", "Buccaneers", List.of("bucs")),
            new Team("TEN", "Tennessee", "Titans", List.of()),
            new Team("WAS", "Washington", "Commanders", List.of()));

    private static final Map<String, String> CODES_BY_NAME = codesByName();

    private TeamNames() {
    }

    /**
     * Returns the Sleeper code for a team as a person wrote it, or empty
     * when the text names no team or more than one.
     */
    public static Optional<String> resolve(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String key = key(text);
        if (key.startsWith("the ")) {
            key = key.substring("the ".length());
        }
        String code = CODES_BY_NAME.get(key);
        if (code != null) {
            return Optional.of(code);
        }
        // "LA" is the Rams only in nflverse's files; a person saying it
        // could mean either Los Angeles team.
        if (key.equals("la")) {
            return Optional.empty();
        }
        return Optional.ofNullable(CODES_BY_NAME.get(key(NflTeams.normalize(key))));
    }

    private static Map<String, String> codesByName() {
        Map<String, Integer> cityUses = new HashMap<>();
        TEAMS.forEach(team -> cityUses.merge(key(team.city()), 1, Integer::sum));
        Map<String, String> codes = new HashMap<>();
        for (Team team : TEAMS) {
            codes.put(key(team.code()), team.code());
            codes.put(key(team.nickname()), team.code());
            codes.put(key(team.city() + " " + team.nickname()), team.code());
            team.aliases().forEach(alias -> codes.put(key(alias), team.code()));
            if (cityUses.get(key(team.city())) == 1) {
                codes.put(key(team.city()), team.code());
            }
        }
        return Map.copyOf(codes);
    }

    private static String key(String text) {
        return text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll(" +", " ")
                .trim();
    }
}
