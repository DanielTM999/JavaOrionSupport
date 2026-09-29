package dtm.ide.run.form;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringProfileFileTest {

    @TempDir Path module;

    @Test
    void updatesOnlyTheProfileInProperties() throws IOException {
        Path file = SpringProfileFile.path(module, "application.properties");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "# keep\nserver.port=8080\nspring.profiles.active=old\n");

        SpringProfileFile.write(file, "old", "dev,local");
        assertEquals("# keep\nserver.port=8080\nspring.profiles.active=dev,local\n",
                Files.readString(file));
        SpringProfileFile.write(file, "dev,local", "");
        assertEquals("# keep\nserver.port=8080\n", Files.readString(file));
    }

    @Test
    void editsNestedYamlAndPreservesOtherKeys() throws IOException {
        Path file = SpringProfileFile.path(module, "application.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "spring:\n  profiles:\n    active: old # keep\nserver:\n  port: 8080\n");

        SpringProfileFile.write(file, "old", "dev,local");
        assertEquals("dev,local", SpringProfileFile.read(file));
        assertEquals("spring:\n  profiles:\n    active: dev,local # keep\nserver:\n  port: 8080\n",
                Files.readString(file));
    }

    @Test
    void createsPropertiesAndRejectsAnExternalChange() throws IOException {
        Path file = SpringProfileFile.path(module, "application.properties");
        SpringProfileFile.write(file, "", "dev");
        assertEquals(List.of("application.properties"), SpringProfileFile.existing(module));
        assertEquals("dev", SpringProfileFile.read(file));
        assertThrows(IOException.class, () -> SpringProfileFile.write(file, "", "prod"));
        assertFalse(Files.readString(file).contains("prod"));
    }
}
