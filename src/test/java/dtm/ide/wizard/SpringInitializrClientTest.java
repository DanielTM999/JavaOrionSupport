package dtm.ide.wizard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringInitializrClientTest {

    @Test
    void normalizesModernInitializrVersionIdsToPublishedArtifactVersions() {
        assertEquals("4.1.1", SpringInitializrClient.normalizeBootVersion("4.1.1.RELEASE"));
        assertEquals("4.1.2-SNAPSHOT",
                SpringInitializrClient.normalizeBootVersion("4.1.2.BUILD-SNAPSHOT"));
        assertEquals("4.2.0-M1", SpringInitializrClient.normalizeBootVersion("4.2.0.M1"));
        assertEquals("4.2.0-RC1", SpringInitializrClient.normalizeBootVersion("4.2.0.RC1"));
    }

    @Test
    void preservesLegacyAndAlreadyPublishedVersionFormats() {
        assertEquals("2.3.0.RELEASE",
                SpringInitializrClient.normalizeBootVersion("2.3.0.RELEASE"));
        assertEquals("4.1.1", SpringInitializrClient.normalizeBootVersion("4.1.1"));
        assertEquals("", SpringInitializrClient.normalizeBootVersion(null));
    }

    @Test
    void sendsTheNormalizedVersionToTheInitializr() {
        SpringInitializrClient.GenerateRequest request =
                new SpringInitializrClient.GenerateRequest(
                        "maven-project", "java", "4.1.1.RELEASE", "com.example", "demo",
                        "0.0.1-SNAPSHOT", "demo", "", "com.example.demo", "25", "jar",
                        List.of());

        assertEquals("4.1.1", request.bootVersion());
        assertTrue(SpringInitializrClient.buildUrl(request).contains("bootVersion=4.1.1"));
    }
}
