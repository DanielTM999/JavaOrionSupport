package dtm.ide.project;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MavenPom {

    private final Element root;

    private MavenPom(Element root) {
        this.root = root;
    }

    public static MavenPom parse(Path pomFile) {
        return parseContent(JavaProjectConventions.readOrEmpty(pomFile));
    }

    public static MavenPom parseContent(String xml) {
        if (xml == null || xml.isBlank()) {
            return new MavenPom(null);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            Document document = builder.parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return new MavenPom(document.getDocumentElement());
        } catch (Exception e) {
            return new MavenPom(null);
        }
    }

    public boolean isValid() {
        return root != null;
    }

    public String value(String tag) {
        return textOf(child(root, tag));
    }

    public String value(String outer, String inner) {
        return textOf(child(child(root, outer), inner));
    }

    public List<String> values(String outer, String inner) {
        Element parent = child(root, outer);
        if (parent == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Element element : children(parent, inner)) {
            String text = textOf(element);
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return values;
    }

    public List<List<String>> entries(String outer, String inner, String... fields) {
        Element parent = child(root, outer);
        if (parent == null || fields == null || fields.length == 0) {
            return List.of();
        }
        List<List<String>> entries = new ArrayList<>();
        for (Element element : children(parent, inner)) {
            List<String> values = new ArrayList<>(fields.length);
            for (String field : fields) {
                values.add(textOf(child(element, field)));
            }
            entries.add(List.copyOf(values));
        }
        return entries;
    }

    public String property(String name) {
        return value("properties", name);
    }

    public String firstProperty(String... names) {
        for (String name : names) {
            String value = property(name);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static Element child(Element parent, String tag) {
        List<Element> found = children(parent, tag);
        return found.isEmpty() ? null : found.getFirst();
    }

    private static List<Element> children(Element parent, String tag) {
        if (parent == null) {
            return List.of();
        }
        List<Element> found = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element && matches(element, tag)) {
                found.add(element);
            }
        }
        return found;
    }

    private static boolean matches(Element element, String tag) {
        String name = element.getNodeName();
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return name.toLowerCase(Locale.ROOT).equals(tag.toLowerCase(Locale.ROOT));
    }

    private static String textOf(Element element) {
        if (element == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        NodeList nodes = element.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE) {
                text.append(node.getNodeValue());
            }
        }
        return text.toString().trim();
    }
}
