package otto.nflverse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.OptionalInt;

/**
 * nflverse's player register, trimmed to the skill players of this
 * season and the last: who each one is across the ids nflverse files
 * use, when he was born, and where he was drafted. It is one file with
 * no weeks, so its coverage is a single unit.
 *
 * @param season the season whose players were kept
 */
public record Players(
        String season,
        Instant assetUpdatedAt,
        Instant checkedAt,
        Coverage coverage,
        List<Player> rows) implements NflverseFeed {

    /**
     * One player.
     *
     * @param pfrId his Pro Football Reference id, the key of the snap
     *        counts, or null when the register has none
     * @param birthDate null when the register has none
     * @param draft null when the register names no pick: he went
     *        undrafted, or his pick was not recorded
     */
    public record Player(String gsisId, String pfrId, String name, String position,
            LocalDate birthDate, Draft draft) {

        /** Returns his age in whole years on the given day, or empty without a birth date. */
        public OptionalInt ageOn(LocalDate day) {
            return birthDate == null ? OptionalInt.empty()
                    : OptionalInt.of(Period.between(birthDate, day).getYears());
        }
    }

    /**
     * Where a player was drafted.
     *
     * @param pick the overall pick number, counted across every round
     * @param team the team that drafted him, in Sleeper's codes
     */
    public record Draft(int year, int round, int pick, String team) {
    }
}
