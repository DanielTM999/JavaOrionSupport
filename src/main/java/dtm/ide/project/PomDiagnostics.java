package dtm.ide.project;

import dtm.ide.inspection.DiagnosticRanges;
import dtm.stools.component.panels.editor.code.diagnostics.Diagnostic;
import dtm.stools.component.panels.editor.code.diagnostics.DiagnosticSeverity;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblemCollectorRequest;
import org.apache.maven.model.interpolation.DefaultModelVersionProcessor;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.validation.DefaultModelValidator;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PomDiagnostics {

    private static final String NAMESPACE = "http://maven.apache.org/POM/4.0.0";
    private static final String SOURCE = "maven-pom";
    private static final int MAX_PROBLEMS = 30;
    private static final Pattern FIRST_WORD = Pattern.compile("\\S+");
    private static final Pattern ELEMENT_WITH_TEXT = Pattern.compile("Element '([^']+)' cannot have character");
    private static final Schema SCHEMA = loadSchema();

    private record TextSpan(String parent, int offset, int length) {
    }

    private PomDiagnostics() {
    }

    public static List<Diagnostic> validate(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Diagnostic> diagnostics = new ArrayList<>();
        validateSchema(text, diagnostics);
        if (diagnostics.isEmpty()) {
            validateModel(text, diagnostics);
        }
        return DiagnosticRanges.clamp(diagnostics, text);
    }

    private static Schema loadSchema() {
        try (InputStream resource = PomDiagnostics.class.getResourceAsStream("/schema/maven-4.0.0.xsd")) {
            if (resource == null) {
                throw new IllegalStateException("Maven POM schema is missing from the plugin");
            }
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newSchema(new StreamSource(resource));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load Maven POM schema", error);
        }
    }

    private static void validateSchema(String text, List<Diagnostic> diagnostics) {
        try {
            List<TextSpan> textSpans = new ArrayList<>();
            SAXParserFactory parserFactory = SAXParserFactory.newInstance();
            parserFactory.setNamespaceAware(true);
            parserFactory.setXIncludeAware(false);
            parserFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            parserFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            parserFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            parserFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            XMLReader reader = parserFactory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            XMLFilterImpl namespaceFilter = new XMLFilterImpl(reader) {
                private final Deque<String> elements = new ArrayDeque<>();
                private Locator locator;
                private int scannedOffset;

                @Override
                public void setDocumentLocator(Locator locator) {
                    this.locator = locator;
                    super.setDocumentLocator(locator);
                }

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes)
                        throws SAXException {
                    elements.push(localName);
                    super.startElement(uri.isEmpty() ? NAMESPACE : uri, localName, qName, attributes);
                }

                @Override
                public void endElement(String uri, String localName, String qName) throws SAXException {
                    super.endElement(uri.isEmpty() ? NAMESPACE : uri, localName, qName);
                    elements.pop();
                }

                @Override
                public void characters(char[] characters, int start, int length) throws SAXException {
                    String value = new String(characters, start, length);
                    Matcher word = FIRST_WORD.matcher(value);
                    if (!elements.isEmpty() && word.find()) {
                        String token = word.group();
                        int reportedOffset = locator == null ? text.length()
                                : offsetOf(text, locator.getLineNumber(), locator.getColumnNumber());
                        int offset = text.lastIndexOf(token, Math.min(text.length() - 1, reportedOffset));
                        if (offset < scannedOffset) {
                            offset = text.indexOf(token, scannedOffset);
                        }
                        if (offset >= 0) {
                            textSpans.add(new TextSpan(elements.peek(), offset, token.length()));
                            scannedOffset = offset + token.length();
                        }
                    }
                    super.characters(characters, start, length);
                }
            };
            Validator validator = SCHEMA.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.setErrorHandler(new DefaultHandler() {
                @Override
                public void warning(SAXParseException error) {
                    addSchemaProblem(diagnostics, text, textSpans, error, DiagnosticSeverity.WARNING);
                }

                @Override
                public void error(SAXParseException error) {
                    addSchemaProblem(diagnostics, text, textSpans, error, DiagnosticSeverity.ERROR);
                }

                @Override
                public void fatalError(SAXParseException error) throws SAXException {
                    error(error);
                    throw error;
                }
            });
            validator.validate(new SAXSource(namespaceFilter, new InputSource(new StringReader(text))));
        } catch (SAXParseException error) {
            if (diagnostics.isEmpty()) {
                add(diagnostics, text, error.getLineNumber(), error.getColumnNumber(),
                        DiagnosticSeverity.ERROR, error.getMessage());
            }
        } catch (Exception error) {
            add(diagnostics, text, 1, 1, DiagnosticSeverity.ERROR, error.getMessage());
        }
    }

    private static void addSchemaProblem(List<Diagnostic> diagnostics, String text, List<TextSpan> textSpans,
                                         SAXParseException error, DiagnosticSeverity severity) {
        Matcher element = ELEMENT_WITH_TEXT.matcher(error.getMessage());
        if (element.find()) {
            int reportedOffset = offsetOf(text, error.getLineNumber(), error.getColumnNumber());
            for (int index = textSpans.size() - 1; index >= 0; index--) {
                TextSpan span = textSpans.get(index);
                if (span.parent().equals(element.group(1)) && span.offset() <= reportedOffset) {
                    addAtOffset(diagnostics, text, span.offset(), span.length(), severity, error.getMessage());
                    return;
                }
            }
        }
        add(diagnostics, text, error.getLineNumber(), error.getColumnNumber(), severity, error.getMessage());
    }

    private static void addAtOffset(List<Diagnostic> diagnostics, String text, int offset, int length,
                                    DiagnosticSeverity severity, String message) {
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < offset; index++) {
            if (text.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        int column = offset - lineStart;
        int diagnosticLine = line;
        boolean duplicate = diagnostics.stream().anyMatch(d -> d.startLine() == diagnosticLine
                && d.startCol() == column && d.endCol() == column + length);
        if (!duplicate && diagnostics.size() < MAX_PROBLEMS) {
            diagnostics.add(new Diagnostic(line, column, line, column + length,
                    severity, message, SOURCE, null));
        }
    }

    private static int offsetOf(String text, int oneBasedLine, int oneBasedColumn) {
        int line = 1;
        int start = 0;
        while (line < oneBasedLine && start < text.length()) {
            int newline = text.indexOf('\n', start);
            if (newline < 0) {
                return text.length();
            }
            start = newline + 1;
            line++;
        }
        int end = text.indexOf('\n', start);
        return Math.min(end < 0 ? text.length() : end, start + Math.max(0, oneBasedColumn - 1));
    }

    private static void validateModel(String text, List<Diagnostic> diagnostics) {
        try {
            Model model = new MavenXpp3Reader().read(new StringReader(text), true);
            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest()
                    .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_1)
                    .setLocationTracking(true);
            new DefaultModelValidator(new DefaultModelVersionProcessor()).validateRawModel(model, request,
                    problem -> addModelProblem(diagnostics, text, problem));
        } catch (XmlPullParserException error) {
            add(diagnostics, text, error.getLineNumber(), error.getColumnNumber(),
                    DiagnosticSeverity.ERROR, error.getMessage());
        } catch (Exception error) {
            add(diagnostics, text, 1, 1, DiagnosticSeverity.ERROR, error.getMessage());
        }
    }

    private static void addModelProblem(List<Diagnostic> diagnostics, String text,
                                        ModelProblemCollectorRequest problem) {
        int line = problem.getLocation() == null ? 1 : problem.getLocation().getLineNumber();
        int column = problem.getLocation() == null ? 1 : problem.getLocation().getColumnNumber();
        DiagnosticSeverity severity = problem.getSeverity() == ModelProblem.Severity.ERROR
                || problem.getSeverity() == ModelProblem.Severity.FATAL
                ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING;
        add(diagnostics, text, line, column, severity, problem.getMessage());
    }

    private static void add(List<Diagnostic> diagnostics, String text, int oneBasedLine,
                            int oneBasedColumn, DiagnosticSeverity severity, String message) {
        if (diagnostics.size() >= MAX_PROBLEMS) {
            return;
        }
        String[] lines = text.split("\n", -1);
        int line = Math.max(0, Math.min(oneBasedLine - 1, lines.length - 1));
        while (line > 0 && lines[line].isBlank()) {
            line--;
        }
        String lineText = lines[line].stripTrailing();
        int column = Math.max(0, Math.min(oneBasedColumn - 1, lineText.length() - 1));
        while (column > 0 && Character.isWhitespace(lineText.charAt(column))) {
            column--;
        }
        int start = column;
        int end = Math.min(lineText.length(), column + 1);
        int tagStart = lineText.lastIndexOf('<', column);
        int tagEnd = lineText.indexOf('>', column);
        if (tagStart >= 0 && tagEnd >= column) {
            start = tagStart;
            end = tagEnd + 1;
        } else {
            while (start > 0 && !Character.isWhitespace(lineText.charAt(start - 1))
                    && lineText.charAt(start - 1) != '>') {
                start--;
            }
            while (end < lineText.length() && !Character.isWhitespace(lineText.charAt(end))
                    && lineText.charAt(end) != '<') {
                end++;
            }
        }
        String detail = message == null || message.isBlank() ? "Invalid Maven POM" : message;
        diagnostics.add(new Diagnostic(line, start, line, end, severity, detail, SOURCE, null));
    }
}
