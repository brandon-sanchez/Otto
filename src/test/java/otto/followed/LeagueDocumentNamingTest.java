package otto.followed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import otto.OttoProperties;
import otto.scope.LeagueScope;

class LeagueDocumentNamingTest {

    private static final String CONFIG_LEAGUE = "100";
    private static final String OTHER_LEAGUE = "200";

    private final LeagueDocumentNaming naming = new LeagueDocumentNaming(new OttoProperties(
            CONFIG_LEAGUE, null, null, null, null, null, null, null, null, null, null, 0.0,
            null, null));

    @Test
    void aLeaguesOwnDocumentIsStoredApartForEachLeagueButTheConfigLeague() {
        assertThat(naming.stored("settings")).isEqualTo("settings");
        assertThat(LeagueScope.call(OTHER_LEAGUE, () -> naming.stored("settings")))
                .isEqualTo("settings@league-" + OTHER_LEAGUE);
        assertThat(LeagueDocumentNaming.isMarked("settings")).isTrue();
    }

    @Test
    void aSharedDocumentKeepsItsNameInEveryLeague() {
        assertThat(LeagueScope.call(OTHER_LEAGUE, () -> naming.stored("conversation")))
                .isEqualTo("conversation");
        assertThat(LeagueDocumentNaming.isMarked("conversation")).isTrue();
        assertThat(LeagueDocumentNaming.isMarked("nflverse-stats-player-2026")).isTrue();
    }

    @Test
    void aDocumentInNeitherListIsNotMarkedAndIsStoredAsShared() {
        assertThat(LeagueDocumentNaming.isMarked("brand-new-cache")).isFalse();
        assertThat(LeagueScope.call(OTHER_LEAGUE, () -> naming.stored("brand-new-cache")))
                .isEqualTo("brand-new-cache");
    }
}
