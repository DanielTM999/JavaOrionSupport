package dtm.ide.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaPluginSettingsTest {
    @TempDir
    Path directory;

    @Test
    void hotReloadIsManualByDefaultAndPersists() {
        JavaPluginSettings settings = new JavaPluginSettings(directory);
        assertEquals(HotReloadMode.MANUAL, settings.getHotReloadMode());
        settings.setHotReloadMode(HotReloadMode.AUTOMATIC);
        settings.save();
        assertEquals(HotReloadMode.AUTOMATIC,
                new JavaPluginSettings(directory).getHotReloadMode());
    }
}
