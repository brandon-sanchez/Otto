package otto.decisions;

import java.time.Instant;
import java.util.Optional;

import otto.directory.PlayerHealth;
import otto.research.OfficialUpdate;

/**
 * Two of the three separate facts behind a player decision: whether the
 * player plays (the football fact) and the designation Sleeper shows.
 * The third, which roster moves Sleeper allows, is {@link RosterPlans},
 * built from the designation alone, so a team announcement never opens
 * a move Sleeper has not.
 *
 * <p>The newer source states the football fact. A team announcement
 * published after Sleeper last changed the designation is newer; when
 * Otto never saw the designation change, any announcement inside the
 * report window is. When the two disagree, the disagreement is kept for
 * the advice to state rather than settled in silence.
 *
 * @param designation what Sleeper shows now
 * @param designatedSince when Otto saw Sleeper change to it, empty when
 *        Otto has not seen it change
 * @param announcement the team's latest plain statement on whether he
 *        plays, empty when the team has made none
 */
public record PlayerAvailability(PlayerHealth designation, Optional<Instant> designatedSince,
        Optional<OfficialUpdate> announcement) {

    /** True when the team's announcement is newer than Sleeper's designation. */
    public boolean announcementIsNewer() {
        return announcement
                .filter(update -> designatedSince
                        .map(since -> update.published().isAfter(since))
                        .orElse(true))
                .isPresent();
    }

    /**
     * True when the team and Sleeper disagree about whether he plays:
     * the team rules him out while Sleeper's designation still lets him
     * play, or the team says he plays while Sleeper says he is in doubt
     * or out.
     */
    public boolean sourcesDisagree() {
        return announcement.map(update -> switch (update.availability()) {
            case WILL_NOT_PLAY -> !designation.rulesOutPlaying();
            case WILL_PLAY -> designation.isWorseThan(PlayerHealth.PROBABLE);
        }).orElse(false);
    }

    /** True when the football fact is that he will not play. */
    public boolean ruledOut() {
        return announcementIsNewer()
                ? announcement.orElseThrow().availability()
                        == OfficialUpdate.Availability.WILL_NOT_PLAY
                : designation.rulesOutPlaying();
    }
}
