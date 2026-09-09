package dtm.ide.coverage;

import lombok.extern.slf4j.Slf4j;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.ISourceFileCoverage;
import org.jacoco.core.tools.ExecFileLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public final class JacocoExecReader {

    private static final String UNSUPPORTED_MARKER = "unsupported class file major version";

    private JacocoExecReader() {
    }

    public static CoverageReadResult read(Path execFile, List<Path> classDirectories,
                                          List<Path> sourceRoots) {
        if (execFile == null || !Files.isRegularFile(execFile)) {
            return CoverageReadResult.failed(CoverageReadResult.Failure.MISSING_EXEC,
                    execFile == null ? "" : execFile.toString());
        }
        List<Path> classes = existingDirectories(classDirectories);
        if (classes.isEmpty()) {
            return CoverageReadResult.failed(CoverageReadResult.Failure.NO_CLASSES, "");
        }

        ExecFileLoader loader = new ExecFileLoader();
        try {
            loader.load(execFile.toFile());
        } catch (Exception error) {
            return CoverageReadResult.failed(CoverageReadResult.Failure.UNREADABLE_EXEC,
                    messageOf(error));
        }

        CoverageBuilder builder = new CoverageBuilder();
        Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
        for (Path directory : classes) {
            try {
                analyzer.analyzeAll(directory.toFile());
            } catch (Exception error) {
                if (isUnsupportedBytecode(error)) {
                    return CoverageReadResult.failed(
                            CoverageReadResult.Failure.UNSUPPORTED_BYTECODE, messageOf(error));
                }
                log.debug("Falha ao analisar {} para cobertura: {}", directory, messageOf(error));
            }
        }

        return CoverageReadResult.of(buildReport(builder, existingDirectories(sourceRoots)));
    }

    private static CoverageReport buildReport(CoverageBuilder builder, List<Path> sourceRoots) {
        Map<Path, FileCoverage> byFile = new LinkedHashMap<>();
        Map<String, FileCoverage> byClass = new LinkedHashMap<>();

        for (ISourceFileCoverage source : builder.getSourceFiles()) {
            Map<Integer, LineStatus> lines = new HashMap<>();
            int coveredBranches = 0;
            int totalBranches = 0;
            for (int line = source.getFirstLine(); line <= source.getLastLine(); line++) {
                if (line <= 0) {
                    continue;
                }
                ILine data = source.getLine(line);
                LineStatus status = LineStatus.fromJacoco(data.getStatus());
                if (!status.isExecutable()) {
                    continue;
                }
                lines.put(line, status);
                coveredBranches += data.getBranchCounter().getCoveredCount();
                totalBranches += data.getBranchCounter().getTotalCount();
            }
            if (lines.isEmpty()) {
                continue;
            }
            Path resolved = resolveSource(sourceRoots, source.getPackageName(), source.getName());
            FileCoverage coverage = new FileCoverage(resolved, lines, coveredBranches, totalBranches);
            if (resolved != null) {
                byFile.put(resolved, coverage);
            }
            byClass.put(qualifiedNameOf(source.getPackageName(), source.getName()), coverage);
        }

        return new CoverageReport(byFile, byClass);
    }

    static String qualifiedNameOf(String packageName, String sourceName) {
        String simple = sourceName == null ? "" : sourceName;
        int dot = simple.lastIndexOf('.');
        if (dot > 0) {
            simple = simple.substring(0, dot);
        }
        String prefix = packageName == null ? "" : packageName.replace('/', '.');
        return prefix.isBlank() ? simple : prefix + "." + simple;
    }

    static Path resolveSource(List<Path> sourceRoots, String packageName, String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return null;
        }
        String relative = packageName == null || packageName.isBlank()
                ? sourceName
                : packageName.replace('/', java.io.File.separatorChar)
                        .replace('.', java.io.File.separatorChar)
                        + java.io.File.separator + sourceName;
        for (Path root : sourceRoots) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static List<Path> existingDirectories(List<Path> paths) {
        List<Path> found = new ArrayList<>();
        if (paths == null) {
            return found;
        }
        for (Path path : paths) {
            if (path != null && Files.isDirectory(path)) {
                Path normalized = path.toAbsolutePath().normalize();
                if (!found.contains(normalized)) {
                    found.add(normalized);
                }
            }
        }
        return found;
    }

    private static boolean isUnsupportedBytecode(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains(UNSUPPORTED_MARKER)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
