package dtm.ide.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdtLsProvisionerTest {

    @TempDir
    Path root;

    @Test
    void usesJava25CompatibleLanguageServer() {
        assertEquals("1.60.0", JdtLsProvisioner.DEFAULT_VERSION);
    }

    @Test
    void keepsWorkspaceOutsideProjectAndVersionsTheCache() {
        Path sdk = root.resolve("shared-sdk");
        JdtLsProvisioner provisioner = new JdtLsProvisioner(null, sdk);
        Path project = root.resolve("project");
        String key = Integer.toHexString(project.toAbsolutePath().normalize().toString()
                .toLowerCase(java.util.Locale.ROOT).hashCode());

        assertEquals(sdk.resolve("workspaces/1.60.0/project-" + key)
                        .toAbsolutePath().normalize(),
                provisioner.workspaceFor(project));
    }
}
