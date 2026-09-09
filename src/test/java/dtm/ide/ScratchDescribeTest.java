package dtm.ide;

import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.lsp.LombokAgentResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class ScratchDescribeTest {

    @Test
    void describe() {
        Path root = Path.of("D:/dev/cautcar/java/Cautcar_Laudos_2.0");
        long started = System.nanoTime();
        JavaProjectDescriptor descriptor = JavaProjectConventions.describe(root);
        long ms = (System.nanoTime() - started) / 1_000_000;
        System.out.println("describe em " + ms + " ms -> " + (descriptor == null ? "NULL" : descriptor.kind()
                + " modules=" + descriptor.modules().size() + " spring=" + descriptor.spring()));
        if (descriptor == null) return;
        started = System.nanoTime();
        LombokAgentResolver resolver = new LombokAgentResolver(Path.of("C:/temp/sdk"));
        System.out.println("detect -> " + resolver.detect(descriptor));
        System.out.println("detect em " + ((System.nanoTime() - started) / 1_000_000) + " ms");
    }
}
