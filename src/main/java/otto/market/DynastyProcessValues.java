package otto.market;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * DynastyProcess's dynasty values for players and draft picks, as Otto
 * stored them. DynastyProcess derives them from FantasyPros' expert
 * dynasty rankings and publishes them weekly as GPL-3 open data.
 *
 * @param day the UTC date they were read on
 * @param rows every player and pick in the file
 */
public record DynastyProcessValues(LocalDate day, Instant fetchedAt, List<Row> rows) {

    public DynastyProcessValues {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    /**
     * One player or pick.
     *
     * @param name as DynastyProcess writes it, such as "2027 Early 1st"
     * @param position {@code PICK} for a draft pick
     * @param fantasyProsId empty for a pick
     * @param oneQbValue the value in a one-quarterback league
     * @param twoQbValue the value in a Superflex league
     */
    public record Row(String name, String position, Optional<String> team,
            Optional<Double> age, Optional<String> fantasyProsId, int oneQbValue,
            int twoQbValue) {

        /** Returns the value in a league that starts this many quarterbacks. */
        public int value(int quarterbacks) {
            return quarterbacks == 2 ? twoQbValue : oneQbValue;
        }
    }

    /** Returns the row FantasyPros knows by this id. */
    public Optional<Row> player(String fantasyProsId) {
        return rows.stream()
                .filter(row -> row.fantasyProsId().filter(fantasyProsId::equals).isPresent())
                .findFirst();
    }
}
