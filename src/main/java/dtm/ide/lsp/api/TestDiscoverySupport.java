package dtm.ide.lsp.api;

import dtm.ide.test.JavaTest;

import java.nio.file.Path;
import java.util.List;

public interface TestDiscoverySupport {

    boolean isTestRunnerAvailable();

    List<JavaTest> testsIn(Path file);
}
