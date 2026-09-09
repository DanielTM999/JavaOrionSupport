package dtm.ide.test;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
public final class SurefireReportParser {

    private static final List<String> REPORT_DIRECTORIES = List.of(
            "target/surefire-reports",
            "target/failsafe-reports",
            "build/test-results/test");

    private SurefireReportParser() {
    }

    public static List<TestResult> readModuleReports(Path moduleRoot) {
        if (moduleRoot == null) {
            return List.of();
        }
        List<TestResult> results = new ArrayList<>();
        for (String directory : REPORT_DIRECTORIES) {
            results.addAll(readDirectory(moduleRoot.resolve(directory)));
        }
        return results;
    }

    public static List<TestResult> readDirectory(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        List<TestResult> results = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(file -> {
                        String name = file.getFileName().toString();
                        return name.startsWith("TEST-") && name.endsWith(".xml");
                    })
                    .forEach(file -> results.addAll(readReport(file)));
        } catch (Exception e) {
            log.debug("Falha ao ler os relatorios de {}: {}", directory, e.getMessage());
        }
        return results;
    }

    public static List<TestResult> readReport(Path reportFile) {
        if (reportFile == null || !Files.isRegularFile(reportFile)) {
            return List.of();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Element root = builder.parse(reportFile.toFile()).getDocumentElement();

            List<TestResult> results = new ArrayList<>();
            NodeList cases = root.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                if (cases.item(i) instanceof Element testCase) {
                    results.add(readTestCase(testCase));
                }
            }
            return mergeInvocations(results);
        } catch (Exception e) {
            log.debug("Relatorio ilegivel {}: {}", reportFile, e.getMessage());
            return List.of();
        }
    }

    static String baseMethodName(String rawName) {
        if (rawName == null) {
            return "";
        }
        String name = rawName.trim();
        int cut = name.length();
        int parenthesis = name.indexOf('(');
        if (parenthesis >= 0) {
            cut = parenthesis;
        }
        int bracket = name.indexOf('[');
        if (bracket >= 0 && bracket < cut) {
            cut = bracket;
        }
        return name.substring(0, cut).trim();
    }

    static List<TestResult> mergeInvocations(List<TestResult> results) {
        Map<String, TestResult> merged = new LinkedHashMap<>();
        for (TestResult result : results) {
            merged.merge(result.key(), result, SurefireReportParser::worstOf);
        }
        return List.copyOf(merged.values());
    }

    private static TestResult worstOf(TestResult current, TestResult candidate) {
        long duration = current.durationMs() + candidate.durationMs();
        TestResult dominant = rank(candidate.status()) > rank(current.status()) ? candidate : current;
        return new TestResult(dominant.className(), dominant.methodName(), dominant.status(),
                duration, dominant.message(), dominant.stackTrace());
    }

    private static int rank(TestResult.Status status) {
        return switch (status) {
            case ERROR -> 3;
            case FAILED -> 2;
            case SKIPPED -> 1;
            case PASSED -> 0;
        };
    }

    private static TestResult readTestCase(Element testCase) {
        String rawName = testCase.getAttribute("name");
        String methodName = baseMethodName(rawName);
        String className = testCase.getAttribute("classname");
        long duration = parseDurationMs(testCase.getAttribute("time"));

        Element failure = firstChild(testCase, "failure");
        if (failure != null) {
            return new TestResult(className, methodName, TestResult.Status.FAILED, duration,
                    failure.getAttribute("message"), textOf(failure));
        }
        Element error = firstChild(testCase, "error");
        if (error != null) {
            return new TestResult(className, methodName, TestResult.Status.ERROR, duration,
                    error.getAttribute("message"), textOf(error));
        }
        Element skipped = firstChild(testCase, "skipped");
        if (skipped != null) {
            return new TestResult(className, methodName, TestResult.Status.SKIPPED, duration,
                    skipped.getAttribute("message"), "");
        }
        return new TestResult(className, methodName, TestResult.Status.PASSED, duration, "", "");
    }

    static long parseDurationMs(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.round(Double.parseDouble(raw.trim().replace(",", ".")) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Element firstChild(Element parent, String tag) {
        NodeList children = parent.getElementsByTagName(tag);
        return children.getLength() > 0 && children.item(0) instanceof Element element
                ? element
                : null;
    }

    private static String textOf(Element element) {
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        return text.toString();
    }

    public static void clearReports(Path moduleRoot) {
        if (moduleRoot == null) {
            return;
        }
        for (String directory : REPORT_DIRECTORIES) {
            Path path = moduleRoot.resolve(directory);
            if (!Files.isDirectory(path)) {
                continue;
            }
            try (Stream<Path> files = Files.list(path)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".xml"))
                        .forEach(file -> {
                            try {
                                Files.deleteIfExists(file);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception e) {
                log.debug("Falha ao limpar {}: {}", path, e.getMessage());
            }
        }
    }
}
