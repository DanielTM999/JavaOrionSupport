package dtm.ide.coverage;

import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.LoggerRuntime;
import org.jacoco.core.runtime.RuntimeData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JacocoExecReaderEndToEndTest {

    private static final String CLASS_NAME = "com.app.Calc";

    private static final String SOURCE = """
            package com.app;

            public class Calc {

                public int classify(int value) {
                    if (value > 0) {
                        return 1;
                    }
                    return -1;
                }

                public int neverCalled() {
                    return 42;
                }
            }
            """;

    private static int lineOf(String needle) {
        String[] lines = SOURCE.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].contains(needle)) {
                return index + 1;
            }
        }
        throw new IllegalArgumentException("trecho ausente na fixture: " + needle);
    }

    private static final class InstrumentedLoader extends ClassLoader {

        private final byte[] bytes;

        InstrumentedLoader(byte[] bytes) {
            super(InstrumentedLoader.class.getClassLoader());
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (CLASS_NAME.equals(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
            return super.loadClass(name, resolve);
        }
    }

    @Test
    void readsRealCoverageProducedByTheJacocoRuntime(@TempDir Path root) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "a suite precisa rodar sobre uma JDK");

        Path sources = Files.createDirectories(root.resolve("src").resolve("com").resolve("app"));
        Path sourceFile = Files.writeString(sources.resolve("Calc.java"), SOURCE);
        Path classes = Files.createDirectories(root.resolve("classes"));

        int compiled = compiler.run(null, null, null,
                "-g", "-d", classes.toString(), sourceFile.toString());
        assertEquals(0, compiled, "a fixture nao compilou");

        Path execFile = root.resolve(".orion").resolve("coverage").resolve("jacoco.exec");
        Files.createDirectories(execFile.getParent());
        runInstrumented(classes, execFile);

        CoverageReadResult result = JacocoExecReader.read(
                execFile, List.of(classes), List.of(root.resolve("src")));

        assertTrue(result.isSuccess(), "leitura falhou: " + result.failure() + " " + result.detail());

        FileCoverage coverage = result.report().forClass(CLASS_NAME).orElse(null);
        assertNotNull(coverage, "classe ausente no relatorio");

        assertEquals(LineStatus.COVERED, coverage.statusAt(lineOf("return 1;")));
        assertEquals(LineStatus.UNCOVERED, coverage.statusAt(lineOf("return -1;")));
        assertEquals(LineStatus.UNCOVERED, coverage.statusAt(lineOf("return 42;")));
        assertEquals(LineStatus.PARTIAL, coverage.statusAt(lineOf("if (value > 0)")));

        assertEquals(1, coverage.coveredBranches());
        assertEquals(2, coverage.totalBranches());
        assertTrue(coverage.coveredLines() < coverage.totalLines());

        Path resolved = coverage.file();
        assertEquals(sourceFile.toAbsolutePath().normalize(), resolved);
        assertTrue(result.report().forFile(sourceFile).isPresent());
    }

    private static void runInstrumented(Path classes, Path execFile) throws Exception {
        IRuntime runtime = new LoggerRuntime();
        RuntimeData data = new RuntimeData();
        runtime.startup(data);
        try {
            byte[] instrumented;
            Path classFile = classes.resolve("com").resolve("app").resolve("Calc.class");
            try (InputStream original = Files.newInputStream(classFile)) {
                instrumented = new Instrumenter(runtime).instrument(original, CLASS_NAME);
            }

            Class<?> type = new InstrumentedLoader(instrumented).loadClass(CLASS_NAME);
            Method classify = type.getMethod("classify", int.class);
            assertEquals(1, classify.invoke(type.getDeclaredConstructor().newInstance(), 5));

            ExecutionDataStore executionData = new ExecutionDataStore();
            SessionInfoStore sessionInfos = new SessionInfoStore();
            data.collect(executionData, sessionInfos, false);

            try (OutputStream out = new FileOutputStream(execFile.toFile())) {
                ExecutionDataWriter writer = new ExecutionDataWriter(out);
                sessionInfos.accept(writer);
                executionData.accept(writer);
            }
        } finally {
            runtime.shutdown();
        }
    }
}
