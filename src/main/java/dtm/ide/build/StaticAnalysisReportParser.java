package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
public final class StaticAnalysisReportParser {

    private static final List<String> REPORTS = List.of(
            "target/checkstyle-result.xml",
            "target/pmd.xml",
            "target/spotbugsXml.xml",
            "target/spotbugs.xml",
            "build/reports/checkstyle/main.xml",
            "build/reports/checkstyle/test.xml",
            "build/reports/pmd/main.xml",
            "build/reports/pmd/test.xml",
            "build/reports/spotbugs/main.xml",
            "build/reports/spotbugs/test.xml");

    private StaticAnalysisReportParser() {
    }

    public static List<BuildDiagnostic> discover(JavaProjectDescriptor descriptor) {
        if (descriptor == null) {
            return List.of();
        }
        Set<Path> roots = new LinkedHashSet<>();
        roots.add(descriptor.root());
        descriptor.buildableModules().stream().map(JavaModule::root).forEach(roots::add);
        List<BuildDiagnostic> diagnostics = new ArrayList<>();
        for (Path root : roots) {
            for (String relative : REPORTS) {
                Path report = root.resolve(relative);
                if (Files.isRegularFile(report)) {
                    diagnostics.addAll(parse(report, root));
                }
            }
        }
        return List.copyOf(diagnostics);
    }

    public static List<BuildDiagnostic> parse(Path report, Path moduleRoot) {
        if (report == null || !Files.isRegularFile(report)) {
            return List.of();
        }
        try {
            var builder = factory().newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override
                public void error(SAXParseException error) throws SAXException {
                    throw error;
                }

                @Override
                public void fatalError(SAXParseException error) throws SAXException {
                    throw error;
                }
            });
            Document document = builder.parse(report.toFile());
            Element root = document.getDocumentElement();
            String name = localName(root);
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "checkstyle" -> checkstyle(root, moduleRoot);
                case "pmd" -> pmd(root, moduleRoot);
                case "bugcollection" -> spotBugs(root, moduleRoot);
                default -> List.of();
            };
        } catch (Exception error) {
            log.debug("Falha ao ler relatorio de analise estatica {}: {}", report,
                    error.getMessage());
            return List.of();
        }
    }

    private static List<BuildDiagnostic> checkstyle(Element root, Path moduleRoot) {
        List<BuildDiagnostic> result = new ArrayList<>();
        for (Element file : descendants(root, "file")) {
            Path path = resolve(moduleRoot, file.getAttribute("name"));
            for (Element error : children(file, "error")) {
                String rule = simpleRule(error.getAttribute("source"));
                String message = error.getAttribute("message");
                result.add(new BuildDiagnostic(path, integer(error, "line"),
                        integer(error, "column"), severity(error.getAttribute("severity")),
                        decorate(message, rule), "checkstyle"));
            }
        }
        return List.copyOf(result);
    }

    private static List<BuildDiagnostic> pmd(Element root, Path moduleRoot) {
        List<BuildDiagnostic> result = new ArrayList<>();
        for (Element file : descendants(root, "file")) {
            Path path = resolve(moduleRoot, file.getAttribute("name"));
            for (Element violation : children(file, "violation")) {
                int priority = integer(violation, "priority");
                DiagnosticSeverity severity = priority > 0 && priority <= 2
                        ? DiagnosticSeverity.ERROR : priority == 3
                        ? DiagnosticSeverity.WARNING : DiagnosticSeverity.INFO;
                result.add(new BuildDiagnostic(path, integer(violation, "beginline"),
                        integer(violation, "begincolumn"), severity,
                        decorate(violation.getTextContent().trim(), violation.getAttribute("rule")),
                        "pmd"));
            }
        }
        return List.copyOf(result);
    }

    private static List<BuildDiagnostic> spotBugs(Element root, Path moduleRoot) {
        List<BuildDiagnostic> result = new ArrayList<>();
        for (Element bug : descendants(root, "BugInstance")) {
            Element source = firstDescendant(bug, "SourceLine");
            if (source == null) {
                continue;
            }
            String rawPath = source.getAttribute("sourcepath");
            if (rawPath.isBlank()) {
                rawPath = source.getAttribute("sourcefile");
            }
            String message = textOf(bug, "LongMessage");
            if (message.isBlank()) {
                message = textOf(bug, "ShortMessage");
            }
            String type = bug.getAttribute("type");
            int priority = integer(bug, "priority");
            DiagnosticSeverity severity = priority == 1 ? DiagnosticSeverity.ERROR
                    : priority == 2 ? DiagnosticSeverity.WARNING : DiagnosticSeverity.INFO;
            result.add(new BuildDiagnostic(resolveSource(moduleRoot, rawPath),
                    integer(source, "start"), 0, severity, decorate(message, type), "spotbugs"));
        }
        return List.copyOf(result);
    }

    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static List<Element> descendants(Element root, String wanted) {
        List<Element> result = new ArrayList<>();
        NodeList nodes = root.getElementsByTagNameNS("*", wanted);
        if (nodes.getLength() == 0) {
            nodes = root.getElementsByTagName(wanted);
        }
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index) instanceof Element element) {
                result.add(element);
            }
        }
        return result;
    }

    private static List<Element> children(Element parent, String wanted) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && wanted.equalsIgnoreCase(localName(element))) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element firstDescendant(Element parent, String wanted) {
        return descendants(parent, wanted).stream().findFirst().orElse(null);
    }

    private static String textOf(Element parent, String wanted) {
        Element element = firstDescendant(parent, wanted);
        return element == null ? "" : element.getTextContent().trim();
    }

    private static String localName(Element element) {
        return element.getLocalName() == null ? element.getTagName() : element.getLocalName();
    }

    private static int integer(Element element, String attribute) {
        try {
            return Integer.parseInt(element.getAttribute(attribute));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static DiagnosticSeverity severity(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "error" -> DiagnosticSeverity.ERROR;
            case "info", "information" -> DiagnosticSeverity.INFO;
            default -> DiagnosticSeverity.WARNING;
        };
    }

    private static String decorate(String message, String rule) {
        String clean = message == null || message.isBlank() ? "Static analysis finding" : message;
        return rule == null || rule.isBlank() ? clean : clean + " [" + rule + "]";
    }

    private static String simpleRule(String source) {
        if (source == null) {
            return "";
        }
        int dot = source.lastIndexOf('.');
        return dot < 0 ? source : source.substring(dot + 1);
    }

    private static Path resolve(Path moduleRoot, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(raw);
            return (path.isAbsolute() || moduleRoot == null ? path : moduleRoot.resolve(path))
                    .toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path resolveSource(Path moduleRoot, String raw) {
        Path direct = resolve(moduleRoot, raw);
        if (direct != null && Files.exists(direct)) {
            return direct;
        }
        if (moduleRoot != null && raw != null && !raw.isBlank()) {
            Path main = moduleRoot.resolve("src/main/java").resolve(raw).toAbsolutePath().normalize();
            if (Files.exists(main)) {
                return main;
            }
            Path test = moduleRoot.resolve("src/test/java").resolve(raw).toAbsolutePath().normalize();
            if (Files.exists(test)) {
                return test;
            }
        }
        return direct;
    }
}
