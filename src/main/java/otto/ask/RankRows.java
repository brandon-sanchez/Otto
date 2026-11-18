package otto.ask;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import otto.advice.EvidenceBundle;
import otto.advice.EvidenceItem;
import otto.format.TelegramHtml;

/**
 * Lays out the rank block of a checked player-stats answer from the
 * lines code wrote, so the ranks the user reads never depend on the
 * model copying them: a row the model reworded gets its computed line
 * back, and a rank header the model left with no rows under it gets the
 * rows that best show the player's role.
 */
final class RankRows {

    /** How many rows a ranks answer shows when the model named none. */
    private static final int ROWS_FOR_A_RANKS_ANSWER = 4;

    /** How many rows follow a stat sentence as season context when the model named none. */
    private static final int ROWS_FOR_CONTEXT = 2;

    private static final String RANK_MARK = ": <b>";

    private RankRows() {
    }

    /**
     * Returns the message with its rank block as code wrote it.
     *
     * <p>A message that opens with the rank header is a ranks answer: the
     * rank rows sit directly under the header and any other sentence
     * comes after a blank line. So is a message that opens with the
     * season's points sentence and then leaves the header with no usage
     * rank under it: the header moves to the top, the leading rank rows
     * follow, and the points become the one row the evidence wrote for
     * them. A header anywhere else is season context under a stat
     * sentence and keeps its place. A message with no header only has its
     * reworded rows restored.
     *
     * @param bundle the evidence the message was checked against
     * @param message the checked message
     */
    static String layout(EvidenceBundle bundle, String message) {
        List<EvidenceItem> ranked = bundle.evidence().stream()
                .filter(item -> item.facts().containsKey(PlayerStatsEvidence.RANK_HEADER_FACT)
                        && item.facts().getOrDefault(PlayerStatsEvidence.RANK_LINE_FACT, "")
                                .contains(RANK_MARK))
                .toList();
        if (ranked.isEmpty()) {
            return message;
        }
        List<String> rows = ranked.stream()
                .map(item -> item.facts().get(PlayerStatsEvidence.RANK_LINE_FACT))
                .toList();
        String header = ranked.getFirst().facts().get(PlayerStatsEvidence.RANK_HEADER_FACT);
        List<String> restored = message.lines().map(line -> restored(rows, line)).toList();
        List<String> lines = restored.stream().filter(line -> !line.isBlank()).toList();
        int headerAt = indexOf(lines, header);
        if (headerAt < 0) {
            return String.join("\n", restored);
        }
        List<String> lead = lines.subList(0, headerAt);
        List<String> after = lines.subList(headerAt + 1, lines.size());
        List<String> shown = after.stream().filter(rows::contains).distinct().toList();
        List<String> prose = after.stream().filter(line -> !rows.contains(line)).toList();
        Points points = Points.of(bundle);
        if (lead.isEmpty() || shown.isEmpty() && points.foldsIntoARow(lead)) {
            return ranksAnswer(header, orLeading(shown, rows, ROWS_FOR_A_RANKS_ANSWER),
                    lead.isEmpty() ? Optional.empty() : points.row(),
                    prose.stream().filter(line -> lead.isEmpty() || !points.wrote(line))
                            .toList());
        }
        return contextAnswer(lead, header, orLeading(shown, rows, ROWS_FOR_CONTEXT), prose);
    }

    private static String ranksAnswer(String header, List<String> rows,
            Optional<String> pointsRow, List<String> prose) {
        List<String> block = new ArrayList<>();
        block.add(header);
        block.addAll(rows);
        pointsRow.ifPresent(block::add);
        return withProse(String.join("\n", block), prose);
    }

    private static String contextAnswer(List<String> lead, String header, List<String> rows,
            List<String> prose) {
        return withProse(String.join("\n", lead) + "\n\n" + header + "\n"
                + String.join("\n", rows), prose);
    }

    private static String withProse(String block, List<String> prose) {
        return prose.isEmpty() ? block : block + "\n\n" + String.join("\n", prose);
    }

    /** Returns the rows the model showed, or the leading rows when it showed none. */
    private static List<String> orLeading(List<String> shown, List<String> rows, int most) {
        return shown.isEmpty() ? rows.subList(0, Math.min(rows.size(), most)) : shown;
    }

    /** Returns the computed row a line rewords, or the line itself. */
    private static String restored(List<String> rows, String line) {
        String visible = TelegramHtml.words(line).strip();
        return rows.stream().filter(row -> {
            String shown = TelegramHtml.visible(row);
            String label = shown.substring(shown.indexOf(' ') + 1, shown.indexOf(':') + 1);
            String ordinal = row.substring(row.indexOf("<b>") + 3, row.indexOf("</b>"));
            return visible.regionMatches(true, 0, label, 0, label.length())
                    && visible.substring(label.length()).stripLeading()
                            .matches(Pattern.quote(ordinal) + "\\b.*");
        }).findFirst().orElse(line);
    }

    private static int indexOf(List<String> lines, String header) {
        for (int index = 0; index < lines.size(); index++) {
            if (same(lines.get(index), header)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean same(String line, String fact) {
        return words(line).equals(words(fact));
    }

    private static String words(String text) {
        return TelegramHtml.words(text).strip().toLowerCase(Locale.ROOT);
    }

    /**
     * The league-points item's ready lines, empty when the bundle has no
     * such item.
     */
    private record Points(Map<String, String> facts) {

        static Points of(EvidenceBundle bundle) {
            return new Points(bundle.evidence().stream()
                    .filter(item -> item.id().startsWith(LeaguePointsEvidence.ID_PREFIX))
                    .findFirst()
                    .map(EvidenceItem::facts)
                    .orElse(Map.of()));
        }

        Optional<String> row() {
            return Optional.ofNullable(facts.get(LeaguePointsEvidence.RANK_ROW_FACT));
        }

        /**
         * Returns whether the lines are only what the points row already
         * says: the season's points sentence and his points rank. A
         * per-game sentence says more than the row, and a week or a span
         * with no rank has no row, so those keep their place.
         */
        boolean foldsIntoARow(List<String> lines) {
            return row().isPresent() && lines.stream().allMatch(line ->
                    is(line, LeaguePointsEvidence.LINE_FACT)
                            || is(line, LeaguePointsEvidence.RANK_LINE_FACT));
        }

        /** Returns whether the line is one of the item's ready points lines. */
        boolean wrote(String line) {
            return is(line, LeaguePointsEvidence.LINE_FACT)
                    || is(line, LeaguePointsEvidence.RANK_LINE_FACT)
                    || is(line, LeaguePointsEvidence.RANK_ROW_FACT);
        }

        private boolean is(String line, String fact) {
            String written = facts.get(fact);
            return written != null && same(line, written);
        }
    }
}
