package dtm.ide.deps;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenCentralClientTest {

    @Test
    void releaseVersionsAreStable() {
        assertTrue(MavenCentralClient.isStable("2.0.13"));
        assertTrue(MavenCentralClient.isStable("3.3.4"));
        assertTrue(MavenCentralClient.isStable("1.18.42"));
        assertTrue(MavenCentralClient.isStable("5.10.2"));
    }

    @Test
    void prereleaseVersionsAreNotStable() {
        assertFalse(MavenCentralClient.isStable("1.0.0-SNAPSHOT"));
        assertFalse(MavenCentralClient.isStable("2.0.0-alpha1"));
        assertFalse(MavenCentralClient.isStable("3.0.0-beta"));
        assertFalse(MavenCentralClient.isStable("4.0.0-rc1"));
        assertFalse(MavenCentralClient.isStable("6.0.0-M2"));
        assertFalse(MavenCentralClient.isStable("7.0.0-preview"));
        assertFalse(MavenCentralClient.isStable("21.0.0-ea"));
    }

    @Test
    void blankVersionIsNotStable() {
        assertFalse(MavenCentralClient.isStable(""));
        assertFalse(MavenCentralClient.isStable(null));
    }

    @Test
    void pastedCoordinatesBecomeAFieldQuery() {
        assertEquals("g:\"com.fasterxml.jackson.core\" AND a:\"jackson-databind\"",
                MavenCentralClient.toSolrQuery("com.fasterxml.jackson.core:jackson-databind"));
    }

    @Test
    void aSingleTermAlsoMatchesByPrefix() {
        assertEquals("lomb OR lomb*", MavenCentralClient.toSolrQuery("lomb"));
        assertEquals("jackson OR jackson*", MavenCentralClient.toSolrQuery("jackson"));
    }

    @Test
    void phrasesAndExistingWildcardsStayAsIs() {
        assertEquals("spring boot starter", MavenCentralClient.toSolrQuery("spring boot starter"));
        assertEquals("jack*", MavenCentralClient.toSolrQuery("jack*"));
    }

    @Test
    void incompleteCoordinatesAreTreatedAsText() {
        assertEquals("jackson:", MavenCentralClient.toSolrQuery("jackson:"));
        assertEquals(":jackson", MavenCentralClient.toSolrQuery(":jackson"));
    }

    @Test
    void latestStableVersionDoesNotTrustTheOrderReturnedByCentral() {
        MavenCentralClient client = new MavenCentralClient() {
            @Override
            public List<String> versions(String groupId, String artifactId) {
                return List.of("1.18.38", "1.18.46", "1.18.44", "1.18.48-RC1");
            }
        };

        assertEquals("1.18.46", client.latestStableVersion("org.projectlombok", "lombok"));
    }
}
