package dtm.ide.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPluginSettingsTest {
    @TempDir
    Path directory;

    @Test
    void theProjectIsBuiltOnOpenByDefaultAndThePreferencePersists() {
        JavaPluginSettings settings = new JavaPluginSettings(directory);
        assertTrue(settings.isBuildOnProjectOpen());
        settings.setBuildOnProjectOpen(false);
        settings.save();
        assertFalse(new JavaPluginSettings(directory).isBuildOnProjectOpen());
    }

    @Test
    void hotReloadIsManualByDefaultAndPersists() {
        JavaPluginSettings settings = new JavaPluginSettings(directory);
        assertEquals(HotReloadMode.MANUAL, settings.getHotReloadMode());
        settings.setHotReloadMode(HotReloadMode.AUTOMATIC);
        settings.save();
        assertEquals(HotReloadMode.AUTOMATIC,
                new JavaPluginSettings(directory).getHotReloadMode());
    }

    @Test
    void inlayHintsAndCaughtExceptionBreakpointsPersist() {
        JavaPluginSettings settings = new JavaPluginSettings(directory);
        assertEquals(InlayHintsMode.LITERALS, settings.getInlayHints());
        assertFalse(settings.isBreakOnCaughtExceptions());
        settings.setInlayHints(InlayHintsMode.ALL);
        settings.setBreakOnCaughtExceptions(true);
        settings.save();

        JavaPluginSettings reloaded = new JavaPluginSettings(directory);
        assertEquals(InlayHintsMode.ALL, reloaded.getInlayHints());
        assertTrue(reloaded.isBreakOnCaughtExceptions());
    }
}
