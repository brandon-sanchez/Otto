package otto.matchup;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import otto.nflverse.NextGenStats;

/**
 * The fantasy positions Otto ranks and reports usage for. Each knows its
 * name in words, the words a person uses for it, the player stats its
 * matchup evidence ranks, and the Next Gen Stats leaderboard it reads.
 */
public enum Position {

    QB("quarterbacks", Set.of("qb", "quarterback"), NextGenStats.Kind.PASSING,
            List.of(PlayerStat.PRESSURED_RATE)),

    RB("running backs", Set.of("rb", "running back", "back", "backfield"),
            NextGenStats.Kind.RUSHING, List.of(PlayerStat.CARRY_SHARE, PlayerStat.TARGET_SHARE,
                    PlayerStat.YARDS_BEFORE_CONTACT_PER_CARRY)),

    WR("wide receivers", Set.of("wr", "receiver", "wide receiver"), NextGenStats.Kind.RECEIVING,
            List.of(PlayerStat.TARGET_SHARE, PlayerStat.TARGETS_PER_GAME)),

    TE("tight ends", Set.of("te", "tight end"), NextGenStats.Kind.RECEIVING,
            List.of(PlayerStat.TARGET_SHARE, PlayerStat.TARGETS_PER_GAME));

    /** The data code of a fullback, who is counted with the running backs. */
    private static final String FULLBACK = "FB";

    private final String plural;
    private final Set<String> words;
    private final NextGenStats.Kind nextGenKind;
    private final List<PlayerStat> playerStats;

    Position(String plural, Set<String> words, NextGenStats.Kind nextGenKind,
            List<PlayerStat> playerStats) {
        this.plural = plural;
        this.words = words;
        this.nextGenKind = nextGenKind;
        this.playerStats = playerStats;
    }

    /** Returns the position's players in words, such as "tight ends". */
    public String plural() {
        return plural;
    }

    /** Returns the Next Gen Stats leaderboard that covers the position. */
    public NextGenStats.Kind nextGenKind() {
        return nextGenKind;
    }

    /** Returns the player stats a matchup item ranks at the position, the lead one first. */
    public List<PlayerStat> playerStats() {
        return playerStats;
    }

    /**
     * Returns the position a data source's code names, such as "TE" from
     * Sleeper or nflverse; empty for null or any other code.
     */
    public static Optional<Position> of(String code) {
        return code == null ? Optional.empty() : Arrays.stream(values())
                .filter(position -> position.name().equalsIgnoreCase(code.trim()))
                .findFirst();
    }

    /** Returns the position group a code plays in, where a fullback is a running back. */
    public static Optional<Position> groupOf(String code) {
        return FULLBACK.equalsIgnoreCase(code == null ? "" : code.trim()) ? Optional.of(RB)
                : of(code);
    }

    /**
     * Returns the position a person names, such as "RB", "running backs"
     * or "the backfield"; empty when the words name none.
     */
    public static Optional<Position> parse(String words) {
        if (words == null) {
            return Optional.empty();
        }
        String word = words.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^the\\s+", "").replaceAll("s$", "");
        return Arrays.stream(values())
                .filter(position -> position.words.contains(word))
                .findFirst();
    }
}
