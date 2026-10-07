package dtm.ide.lsp.api;

import java.nio.file.Path;

public interface JavaAgentSupport {

    boolean setLombokAgentJar(Path jar);

    boolean needsRestartForLombokAgent();
}
