package otto.research;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * A team's inactives post: a heading per club, then one line per
 * inactive player, as in {@code 47 FB Ben VanSumeren}. Only the team's
 * own list is read, for the same reason {@link InjuryReportPage} reads
 * only the team's own table.
 */
final class InactivesPost {

    private static final Pattern LINE = Pattern.compile("^(?<jersey>\\d{1,2})\\s+"
            + "(?<emergencyQb>\\(3rd QB\\)\\s+)?(?<position>[A-Z]{1,4})\\s+(?<name>\\S.*)$");

    private InactivesPost() {
    }

    /**
     * Returns the team's inactive players. The emergency third
     * quarterback is left out: he is listed inactive but may still enter
     * the game, so the list does not rule him out.
     *
     * @throws LayoutChanged when the post has no list for this team, or
     *         a line in it is not a numbered player
     */
    static List<TeamFact> parse(String html, OfficialTeams.Team team) {
        String name = team.name().toLowerCase(Locale.ROOT);
        List<TeamFact> inactive = new ArrayList<>();
        boolean ownList = false;
        boolean found = false;
        for (Element part : Jsoup.parse(html).select(".nfl-c-body-part--text")) {
            Element heading = part.selectFirst("h1, h2, h3, h4");
            if (heading != null) {
                ownList = heading.text().trim().toLowerCase(Locale.ROOT).endsWith(name);
                found |= ownList;
                continue;
            }
            if (ownList) {
                lines(part).forEach(line -> player(line).ifPresent(inactive::add));
            }
        }
        if (!found) {
            throw new LayoutChanged("inactives post has no list for the " + team.name());
        }
        return inactive;
    }

    private static List<String> lines(Element part) {
        part.select("br").forEach(br -> br.after("\n"));
        return part.select("p").stream()
                .flatMap(paragraph -> paragraph.wholeText().lines())
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private static Optional<TeamFact> player(String line) {
        Matcher player = LINE.matcher(line);
        if (!player.matches()) {
            throw new LayoutChanged("inactives line \"%s\" is not a numbered player"
                    .formatted(line));
        }
        if (player.group("emergencyQb") != null) {
            return Optional.empty();
        }
        return Optional.of(TeamFact.inactive(player.group("name").strip(),
                player.group("position")));
    }
}
