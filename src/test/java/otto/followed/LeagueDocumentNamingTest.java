package otto.followed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import otto.OttoProperties;
import otto.followed.LeagueDocumentNaming.Ownership;
import otto.scope.LeagueScope;

class LeagueDocumentNamingTest {

    private static final String CONFIG_LEAGUE = "100";
    private static final String OTHER_LEAGUE = "200";

    private final LeagueDocumentNaming naming = new LeagueDocumentNaming(new OttoProperties(
            CONFIG_LEAGUE, null, null, null, null, null, null, null, null, null, null, 0.0,
            null, null));

    private String storedInOtherLeague(String name) {
        return LeagueScope.call(OTHER_LEAGUE, () -> naming.stored(name));
    }

    @Test
    void aLeaguesOwnDocumentIsStoredApartForEachLeagueButTheConfigLeague() {
        assertThat(naming.stored("settings")).isEqualTo("settings");
        assertThat(storedInOtherLeague("settings")).isEqualTo("settings@league-" + OTHER_LEAGUE);
        assertThat(storedInOtherLeague("alert-delivery-key:lineup"))
                .isEqualTo("alert-delivery-key:lineup@league-" + OTHER_LEAGUE);
    }

    @Test
    void aSharedDocumentKeepsItsNameInEveryLeague() {
        assertThat(storedInOtherLeague("conversation")).isEqualTo("conversation");
        assertThat(storedInOtherLeague("alert-delivery-id:42")).isEqualTo("alert-delivery-id:42");
        assertThat(LeagueDocumentNaming.ownershipOf("nflverse-stats-player-2026"))
                .isEqualTo(Ownership.SHARED);
    }

    @Test
    void aDocumentInNeitherListIsUnlistedAndStoredAsShared() {
        assertThat(LeagueDocumentNaming.ownershipOf("brand-new-cache"))
                .isEqualTo(Ownership.UNLISTED);
        assertThat(storedInOtherLeague("brand-new-cache")).isEqualTo("brand-new-cache");
    }
}
