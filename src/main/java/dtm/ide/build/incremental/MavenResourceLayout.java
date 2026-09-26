package dtm.ide.build.incremental;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

final class MavenResourceLayout {

    private static final Set<String> RESOURCE_ELEMENTS = Set.of("resources", "testResources");

    private MavenResourceLayout() {
    }

    static boolean customizes(Path pom) {
        if (pom == null || !Files.isRegularFile(pom)) {
            return false;
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            Element project = document.getDocumentElement();
            if (buildCustomizesResources(child(project, "build"))) {
                return true;
            }
            Element profiles = child(project, "profiles");
            if (profiles != null) {
                NodeList children = profiles.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    if (children.item(i) instanceof Element profile
                            && "profile".equals(profile.getTagName())
                            && buildCustomizesResources(child(profile, "build"))) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static boolean buildCustomizesResources(Element build) {
        if (build == null) {
            return false;
        }
        NodeList children = build.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && RESOURCE_ELEMENTS.contains(element.getTagName())) {
                return true;
            }
        }
        return false;
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }
}
