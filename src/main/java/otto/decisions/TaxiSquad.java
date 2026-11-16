package otto.decisions;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import otto.check.SeasonWeek;
import otto.check.WeekFacts;
import otto.lineup.GameWeek;
import otto.sleeper.LeagueRules;
import otto.sleeper.SleeperAdapter;

/**
 * Sleeper's rules for who may go on a league's taxi squad, and when.
 * Each check returns why a move onto the taxi squad is blocked, or
 * empty when the rule is verified as satisfied. A setting Sleeper did
 * not state blocks the move with that as the reason.
 *
 * <p>The rules mirror the taxi check in Sleeper's own web client and
 * the wording of its league settings screen:
 * <ul>
 * <li>Without "Allow Non-Rookies for Taxi" ({@code taxi_allow_vets}),
 * only a rookie ({@code years_exp} 0) may go on taxi. With it, a
 * player may go on taxi while his {@code years_exp} is at most
 * {@code taxi_years}, and {@code taxi_years} 0 means no limit.
 * <li>{@code taxi_deadline} 0 means no deadline. 1 to 3 close taxi at
 * the end of that preseason week, and 4 closes it when the first
 * regular-season game kicks off. Taxi is always open in the offseason.
 * Once the deadline passes, nobody may move onto taxi, including a
 * player promoted off it, until the next offseason.
 * </ul>
 * Sleeper has no separate rule that keeps a promoted player off taxi:
 * going back is the same move as going on the first time. The taxi
 * deadline does not apply to promotion off taxi.
 */
public final class TaxiSquad {

    private static final int START_OF_REGULAR_SEASON = 4;
    private static final Set<String> OFFSEASON = Set.of("off", "offseason");
    private static final Set<String> PRESEASON = Set.of("pre", "preseason");
    private static final Set<String> REGULAR_SEASON = Set.of("reg", "regular");
    private static final Set<String> POSTSEASON = Set.of("post", "postseason");

    private TaxiSquad() {
    }

    /** Whether the league has a taxi squad: Sleeper writes 0 slots into leagues without one. */
    public static boolean inLeague(LeagueRules rules) {
        return rules.taxi().slots().orElse(0) > 0;
    }

    /**
     * Why a player with this many seasons played may not go on the
     * league's taxi squad, empty when his experience allows it.
     *
     * @param yearsInLeague his {@code years_exp}, empty when unknown
     */
    public static Optional<String> experienceBlock(LeagueRules.Taxi taxi,
            Optional<Integer> yearsInLeague) {
        if (yearsInLeague.isEmpty()) {
            return Optional.of("Sleeper does not say how many NFL seasons he has played");
        }
        int years = yearsInLeague.get();
        if (taxi.allowsVeterans().isEmpty()) {
            return Optional.of("Sleeper did not say whether the league allows non-rookies on "
                    + "the taxi squad");
        }
        if (!taxi.allowsVeterans().get()) {
            return years == 0 ? Optional.empty()
                    : Optional.of("the league takes only rookies on the taxi squad, and he has "
                            + "played " + seasons(years));
        }
        if (taxi.maxYears().isEmpty()) {
            return Optional.of("Sleeper did not say the league's taxi experience limit");
        }
        int limit = taxi.maxYears().get();
        return limit == 0 || years <= limit ? Optional.empty()
                : Optional.of("the league's taxi squad takes players with at most %s played, "
                        .formatted(seasons(limit)) + "and he has played " + seasons(years));
    }

    /**
     * Why nobody may move onto the league's taxi squad now because of
     * its deadline, empty when the deadline has not passed.
     */
    public static Optional<String> deadlineBlock(LeagueRules.Taxi taxi, WeekFacts week,
            Instant now) {
        if (taxi.deadline().isEmpty()) {
            return Optional.of("Sleeper did not say the league's taxi deadline");
        }
        int deadline = taxi.deadline().get();
        if (deadline == 0) {
            return Optional.empty();
        }
        Optional<String> wording = deadlineWording(deadline);
        if (wording.isEmpty()) {
            return Optional.of("Sleeper wrote a taxi deadline (%d) Otto cannot read"
                    .formatted(deadline));
        }
        if (week.seasonWeek().isEmpty()) {
            return Optional.of("Otto could not read which week of the season it is, so it "
                    + "cannot check the taxi deadline");
        }
        SeasonWeek current = week.seasonWeek().get();
        String type = current.seasonType().toLowerCase(Locale.ROOT);
        String passed = "the league's taxi deadline, %s, has passed".formatted(wording.get());
        if (OFFSEASON.contains(type)) {
            return Optional.empty();
        }
        if (PRESEASON.contains(type)) {
            return current.number() <= deadline ? Optional.empty() : Optional.of(passed);
        }
        if (POSTSEASON.contains(type)) {
            return Optional.of(passed);
        }
        if (!REGULAR_SEASON.contains(type)) {
            return Optional.of("Sleeper did not say which part of the season it is, so Otto "
                    + "cannot check the taxi deadline");
        }
        if (deadline != START_OF_REGULAR_SEASON || current.number() != 1) {
            return Optional.of(passed);
        }
        return week.games()
                .map(games -> firstKickoff(games).filter(now::isBefore).isPresent()
                        ? Optional.<String>empty()
                        : Optional.of(passed))
                .orElseGet(() -> Optional.of("Otto could not read this week's games, so it "
                        + "cannot tell whether the first game of the regular season has started"));
    }

    /**
     * The league's taxi deadline as the user reads it, such as "no
     * deadline", empty for a value Sleeper's settings screen does not
     * offer.
     */
    public static Optional<String> deadlineWording(int deadline) {
        return switch (deadline) {
            case 0 -> Optional.of("no deadline");
            case 1 -> Optional.of("the end of the first week of preseason");
            case 2 -> Optional.of("the end of the second week of preseason");
            case 3 -> Optional.of("the end of the third week of preseason");
            case START_OF_REGULAR_SEASON -> Optional.of("the first game of the regular season");
            default -> Optional.empty();
        };
    }

    private static Optional<Instant> firstKickoff(GameWeek games) {
        return games.byTeam().values().stream()
                .map(SleeperAdapter.Game::startTime)
                .min(Instant::compareTo);
    }

    private static String seasons(int years) {
        return years == 1 ? "1 season" : years + " seasons";
    }
}
