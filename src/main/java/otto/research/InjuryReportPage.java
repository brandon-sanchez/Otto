package otto.research;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * A team's official injury-report page ({@code /team/injury-report/}):
 * one table per club in the coming game, with each listed player's
 * practice participation by day and his game status. Only the team's own
 * table is read. The opponent's table on the same page is the opponent's
 * report relayed, not this team's word.
 */
final class InjuryReportPage {

    private static final Pattern WEEK = Pattern.compile("/week/((?:PRE|REG|POST)-\\d+)$");

    private static final Set<String> PRACTICE_DAYS =
            Set.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

    private static final Set<String> EMPTY_CELL_MARKS = Set.of("", "(-)", "-", "--", "UNSPECIFIED");

    private InjuryReportPage() {
    }

    /**
     * The team's report as the page shows it.
     *
     * @param week the week the page shows, such as {@code REG-10}
     * @param players every player the team lists, in page order
     */
    record Report(String week, List<TeamFact> players) {

        Report {
            players = List.copyOf(players);
        }
    }

    /**
     * Reads the team's own table.
     *
     * @throws LayoutChanged when the page names no week, has no table for
     *         this team, or its columns or cells are not the ones read here
     */
    static Report parse(String html, OfficialTeams.Team team) {
        Document page = Jsoup.parse(html);
        Element selected = page.selectFirst(".d3-o-season-selector option[selected]");
        Matcher week = WEEK.matcher(selected == null ? "" : selected.attr("value"));
        if (!week.find()) {
            throw new LayoutChanged("injury report page names no week");
        }
        Element table = ownTable(page, team);
        List<String> columns = table.select("thead th").eachText();
        int days = columns.size() - 4;
        if (days < 1 || !columns.subList(0, 3).equals(List.of("Player", "Position", "Injury"))
                || !columns.getLast().equals("Game Status")
                || !PRACTICE_DAYS.containsAll(columns.subList(3, 3 + days))) {
            throw new LayoutChanged("injury report columns are " + columns);
        }
        List<TeamFact> players = new ArrayList<>();
        for (Element row : table.select("tbody > tr")) {
            Elements cells = row.select("> td");
            if (cells.size() != columns.size()) {
                throw new LayoutChanged("injury report row has %d cells for %d columns"
                        .formatted(cells.size(), columns.size()));
            }
            Element name = cells.getFirst().selectFirst(".d3-o-player-roster__player-name");
            String player = (name == null ? cells.getFirst() : name).text().trim();
            if (player.isEmpty()) {
                throw new LayoutChanged("injury report row without a player name");
            }
            Map<String, TeamFact.Participation> practice = new LinkedHashMap<>();
            for (int day = 0; day < days; day++) {
                String mark = cells.get(3 + day).text().trim();
                if (!EMPTY_CELL_MARKS.contains(mark)) {
                    practice.put(columns.get(3 + day), participation(mark));
                }
            }
            players.add(new TeamFact(player, cells.get(1).text().trim(),
                    cells.get(2).text().trim(), practice, status(cells.getLast().text()), false));
        }
        return new Report(week.group(1), players);
    }

    private static Element ownTable(Document page, OfficialTeams.Team team) {
        String name = team.name().toLowerCase(Locale.ROOT);
        for (Element club : page.select(".nfl-o-injury-report__container")) {
            Element clubName = club.selectFirst(".nfl-o-injury-report__club-name");
            Element table = club.selectFirst("table");
            if (clubName != null && table != null
                    && clubName.text().trim().toLowerCase(Locale.ROOT).endsWith(name)) {
                return table;
            }
        }
        throw new LayoutChanged("injury report page has no table for the " + team.name());
    }

    private static TeamFact.Participation participation(String mark) {
        return switch (mark) {
            case "DNP" -> TeamFact.Participation.DID_NOT_PRACTICE;
            case "LP" -> TeamFact.Participation.LIMITED;
            case "FP" -> TeamFact.Participation.FULL;
            default -> throw new LayoutChanged("unknown practice mark \"%s\"".formatted(mark));
        };
    }

    private static TeamFact.GameStatus status(String cell) {
        String status = cell.trim().toUpperCase(Locale.ROOT);
        if (EMPTY_CELL_MARKS.contains(status)) {
            return null;
        }
        return switch (status) {
            case "OUT" -> TeamFact.GameStatus.OUT;
            case "DOUBTFUL" -> TeamFact.GameStatus.DOUBTFUL;
            case "QUESTIONABLE" -> TeamFact.GameStatus.QUESTIONABLE;
            default -> throw new LayoutChanged("unknown game status \"%s\"".formatted(cell));
        };
    }
}
