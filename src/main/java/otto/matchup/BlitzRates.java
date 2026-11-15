package otto.matchup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import otto.nflverse.FtnCharting;
import otto.nflverse.PlayByPlay;
import otto.nflverse.Players;

/**
 * Season-to-date blitz rates for every defense, and who every offense
 * targeted when it was blitzed, computed from FTN's charting joined to
 * the play-by-play's dropbacks on game and play id.
 *
 * <p>A dropback is a play FTN charted with at least one pass rusher, and
 * a blitzed dropback one with at least one blitzer. The play-by-play's
 * {@link PlayByPlay.Dropback} names its offense and defense, and its
 * targets name the receiver. A charted dropback the play-by-play does not
 * hold as a dropback, such as a play a penalty wiped out, a
 * postseason play, or a game the play-by-play has not caught up to,
 * feeds no rate and is counted in
 * {@link #unmatchedDropbacks()}.
 *
 * @param season the season both files cover
 * @param priorSeasonFinal true when these are last season's final rates
 * @param defenses every defense, in team-code order; the league
 *        rankings order them by rate
 * @param offenses every offense, in team-code order
 * @param unmatchedDropbacks charted dropbacks with no play-by-play row
 * @param weeks the weeks the matched dropbacks were played in, earliest
 *        first. A week the play-by-play holds and FTN has not charted
 *        feeds no rate and is not among them.
 */
public record BlitzRates(String season, boolean priorSeasonFinal, List<DefenseRate> defenses,
        List<OffenseTargets> offenses, int unmatchedDropbacks, List<Integer> weeks) {

    public BlitzRates {
        defenses = List.copyOf(defenses);
        offenses = List.copyOf(offenses);
        weeks = List.copyOf(weeks);
    }

    /**
     * How often one defense blitzed.
     *
     * @param team the defense, in Sleeper's codes
     * @param dropbacks the opposing dropbacks it faced
     * @param blitzedDropbacks those on which it blitzed
     */
    public record DefenseRate(String team, int dropbacks, int blitzedDropbacks) {

        /** Blitzed dropbacks per dropback; empty when it faced none. */
        public OptionalDouble rate() {
            return Ratio.of(blitzedDropbacks, dropbacks);
        }
    }

    /**
     * Who one offense targeted on the dropbacks on which it was blitzed.
     *
     * @param team the offense, in Sleeper's codes
     * @param blitzedDropbacks the dropbacks on which it was blitzed
     * @param targets the targets thrown on those dropbacks; a sack or
     *        throwaway is a blitzed dropback with none
     * @param players every receiver targeted, the most targets first
     * @param byPosition targets by the receiver's position in the player
     *        register (QB, RB, WR or TE), holding only positions targeted
     * @param unplacedTargets targets to receivers the register does not
     *        list, which no position in {@code byPosition} counts
     */
    public record OffenseTargets(String team, int blitzedDropbacks, int targets,
            List<PlayerTargets> players, Map<String, Integer> byPosition, int unplacedTargets) {

        public OffenseTargets {
            players = List.copyOf(players);
            byPosition = Map.copyOf(byPosition);
        }
    }

    /**
     * One receiver's targets against the blitz.
     *
     * @param player his name in the player register, or null when it does not list him
     * @param position his register position, or null when it does not list him
     */
    public record PlayerTargets(String gsisId, String player, String position, int targets) {
    }

    /**
     * Computes the rates from the charting and the play-by-play.
     *
     * @param players the register that names each receiver and his position
     * @return empty when the charting and the play-by-play cover different
     *         seasons, as they do before the season's first game
     */
    public static Optional<BlitzRates> of(PlayByPlay playByPlay, FtnCharting charting,
            Players players) {
        if (!playByPlay.season().equals(charting.season())) {
            return Optional.empty();
        }
        Map<PlayKey, PlayByPlay.Dropback> plays = playByPlay.dropbacks().stream()
                .collect(Collectors.toMap(play -> new PlayKey(play.gameId(), play.playId()),
                        Function.identity(), (first, second) -> first));
        Map<PlayKey, List<PlayByPlay.Opportunity>> targets = playByPlay.rows().stream()
                .filter(row -> row.kind() == PlayByPlay.Kind.TARGET)
                .collect(Collectors.groupingBy(row -> new PlayKey(row.gameId(), row.playId())));
        Map<String, Players.Player> register = players.rows().stream()
                .collect(Collectors.toMap(Players.Player::gsisId, Function.identity(),
                        (first, second) -> first));
        Map<String, DefenseTally> defenses = new TreeMap<>();
        Map<String, OffenseTally> offenses = new TreeMap<>();
        int unmatched = 0;
        SortedSet<Integer> weeks = new TreeSet<>();
        for (FtnCharting.Dropback dropback : charting.dropbacks()) {
            PlayKey key = new PlayKey(dropback.gameId(), dropback.playId());
            PlayByPlay.Dropback play = plays.get(key);
            if (play == null) {
                unmatched++;
                continue;
            }
            weeks.add(play.week());
            defenses.computeIfAbsent(play.defense(), DefenseTally::new).add(dropback);
            if (dropback.blitzed()) {
                offenses.computeIfAbsent(play.offense(), OffenseTally::new)
                        .add(targets.getOrDefault(key, List.of()), register);
            }
        }
        return Optional.of(new BlitzRates(playByPlay.season(), playByPlay.priorSeasonFinal(),
                defenses.values().stream().map(DefenseTally::rate).toList(),
                offenses.values().stream().map(OffenseTally::targets).toList(),
                unmatched, List.copyOf(weeks)));
    }

    /** Returns one defense's rate, by its Sleeper team code; empty when it faced no dropback. */
    public Optional<DefenseRate> defense(String team) {
        return defenses.stream().filter(rate -> rate.team().equals(team)).findFirst();
    }

    /** Returns one offense's targets, by its Sleeper team code; empty when it was never blitzed. */
    public Optional<OffenseTargets> offense(String team) {
        return offenses.stream().filter(targets -> targets.team().equals(team)).findFirst();
    }

    private record PlayKey(String gameId, String playId) {
    }

    private static final class DefenseTally {

        private final String team;
        private int dropbacks;
        private int blitzed;

        DefenseTally(String team) {
            this.team = team;
        }

        void add(FtnCharting.Dropback dropback) {
            dropbacks++;
            if (dropback.blitzed()) {
                blitzed++;
            }
        }

        DefenseRate rate() {
            return new DefenseRate(team, dropbacks, blitzed);
        }
    }

    private static final class OffenseTally {

        private final String team;
        private final Map<String, PlayerTargets> players = new HashMap<>();
        private final Map<String, Integer> byPosition = new TreeMap<>();
        private int blitzedDropbacks;
        private int targets;
        private int unplaced;

        OffenseTally(String team) {
            this.team = team;
        }

        void add(List<PlayByPlay.Opportunity> targets, Map<String, Players.Player> register) {
            blitzedDropbacks++;
            targets.forEach(target -> target(target.gsisId(), register.get(target.gsisId())));
        }

        private void target(String gsisId, Players.Player listed) {
            targets++;
            String position = listed == null ? null : listed.position();
            players.merge(gsisId, new PlayerTargets(gsisId, listed == null ? null : listed.name(),
                    position, 1), (held, more) -> new PlayerTargets(gsisId, held.player(),
                            held.position(), held.targets() + 1));
            if (position == null) {
                unplaced++;
            } else {
                byPosition.merge(position, 1, Integer::sum);
            }
        }

        OffenseTargets targets() {
            List<PlayerTargets> ranked = new ArrayList<>(players.values());
            ranked.sort(Comparator.comparingInt(PlayerTargets::targets).reversed()
                    .thenComparing(PlayerTargets::gsisId));
            return new OffenseTargets(team, blitzedDropbacks, targets, ranked, byPosition,
                    unplaced);
        }
    }
}
