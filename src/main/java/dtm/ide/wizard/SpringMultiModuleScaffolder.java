package dtm.ide.wizard;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converte um projeto Maven criado pelo Spring Initializr em um reactor multi-modulo.
 *
 * <p>O primeiro modulo informado e o executavel. Ele conserva as dependencias, fontes e
 * configuracoes produzidas pelo Initializr e passa a depender dos demais modulos. O POM raiz
 * herda do parent do Spring Boot e centraliza a versao dos artefatos internos.</p>
 */
final class SpringMultiModuleScaffolder {

    private static final Pattern MODULE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private SpringMultiModuleScaffolder() {
    }

    static Path create(JavaProjectScaffolder.ProjectRequest project,
                       SpringInitializrClient.GenerateRequest initializrRequest,
                       SpringInitializrClient initializr) throws Exception {
        validateModules(project.modules());
        Path staging = Files.createTempDirectory("orion-spring-multimodule");
        try {
            Path generated = initializr.generate(initializrRequest, staging.resolve("generated"));
            return assemble(project, generated);
        } finally {
            deleteRecursively(staging);
        }
    }

    static Path assemble(JavaProjectScaffolder.ProjectRequest project, Path generated)
            throws Exception {
        List<String> modules = project.modules();
        validateModules(modules);
        Path generatedPom = generated.resolve("pom.xml");
        if (!Files.isRegularFile(generatedPom)) {
            throw new IOException("O Spring Initializr nao produziu um pom.xml.");
        }

        Document applicationPom = readPom(generatedPom);
        String applicationModule = modules.getFirst();
        String bootVersion = adaptApplicationPom(applicationPom, project, applicationModule, modules);

        Path target = project.directory();
        Files.createDirectories(target);
        copyGeneratedFiles(generated, target, applicationModule);
        write(target.resolve("pom.xml"), rootPom(project, bootVersion));
        writePom(applicationPom, target.resolve(applicationModule).resolve("pom.xml"));

        for (String module : modules) {
            if (!module.equals(applicationModule)) {
                createLibraryModule(project, module);
            }
        }
        return target;
    }

    private static void validateModules(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um modulo para o projeto.");
        }
        Set<String> unique = new HashSet<>();
        for (String module : modules) {
            if (module == null || !MODULE_NAME.matcher(module).matches()
                    || ".".equals(module) || "..".equals(module)) {
                throw new IllegalArgumentException("Nome de modulo invalido: " + module);
            }
            if (!unique.add(module.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Modulo repetido: " + module);
            }
        }
    }

    private static String rootPom(JavaProjectScaffolder.ProjectRequest project, String bootVersion) {
        StringBuilder moduleEntries = new StringBuilder();
        StringBuilder managedDependencies = new StringBuilder();
        for (String module : project.modules()) {
            moduleEntries.append("        <module>").append(escapeXml(module))
                    .append("</module>\n");
            managedDependencies.append("            <dependency>\n")
                    .append("                <groupId>").append(escapeXml(project.groupId()))
                    .append("</groupId>\n")
                    .append("                <artifactId>").append(escapeXml(module))
                    .append("</artifactId>\n")
                    .append("                <version>${project.version}</version>\n")
                    .append("            </dependency>\n");
        }
        String description = project.description().isBlank() ? ""
                : "    <description>" + escapeXml(project.description()) + "</description>\n";

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>%s</version>
                        <relativePath/>
                    </parent>

                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>
                    <packaging>pom</packaging>
                    <name>%s</name>
                %s
                    <properties>
                        <java.version>%d</java.version>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>

                    <modules>
                %s    </modules>

                    <dependencyManagement>
                        <dependencies>
                %s        </dependencies>
                    </dependencyManagement>
                </project>
                """.formatted(escapeXml(bootVersion), escapeXml(project.groupId()),
                escapeXml(project.artifactId()), escapeXml(project.version()),
                escapeXml(project.artifactId()), description, project.javaVersion(),
                moduleEntries, managedDependencies);
    }

    private static String adaptApplicationPom(Document document,
                                              JavaProjectScaffolder.ProjectRequest project,
                                              String applicationModule,
                                              List<String> modules) {
        Element root = document.getDocumentElement();
        Element parent = directChild(root, "parent");
        if (parent == null) {
            throw new IllegalArgumentException("O POM gerado nao declara o parent do Spring Boot.");
        }
        Element bootVersion = directChild(parent, "version");
        if (bootVersion == null || bootVersion.getTextContent().isBlank()) {
            throw new IllegalArgumentException("O POM gerado nao declara a versao do Spring Boot.");
        }
        String springBootVersion = SpringInitializrClient.normalizeBootVersion(
                bootVersion.getTextContent());

        setChildText(document, parent, "groupId", project.groupId());
        setChildText(document, parent, "artifactId", project.artifactId());
        setChildText(document, parent, "version", project.version());
        setChildText(document, parent, "relativePath", "../pom.xml");
        removeDirectChild(root, "groupId");
        removeDirectChild(root, "version");
        setChildText(document, root, "artifactId", applicationModule);
        setChildText(document, root, "name", applicationModule);

        Element dependencies = directChild(root, "dependencies");
        if (dependencies == null) {
            dependencies = document.createElementNS(root.getNamespaceURI(), "dependencies");
            Element build = directChild(root, "build");
            root.insertBefore(dependencies, build);
        }
        for (String module : modules) {
            if (module.equals(applicationModule)) {
                continue;
            }
            Element dependency = document.createElementNS(root.getNamespaceURI(), "dependency");
            appendText(document, dependency, "groupId", project.groupId());
            appendText(document, dependency, "artifactId", module);
            dependencies.appendChild(dependency);
        }
        return springBootVersion;
    }

    private static void createLibraryModule(JavaProjectScaffolder.ProjectRequest project,
                                            String module) throws IOException {
        Path moduleRoot = project.directory().resolve(module);
        write(moduleRoot.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>

                    <parent>
                        <groupId>%s</groupId>
                        <artifactId>%s</artifactId>
                        <version>%s</version>
                        <relativePath>../pom.xml</relativePath>
                    </parent>

                    <artifactId>%s</artifactId>
                    <name>%s</name>
                </project>
                """.formatted(escapeXml(project.groupId()), escapeXml(project.artifactId()),
                escapeXml(project.version()), escapeXml(module), escapeXml(module)));

        String modulePackage = project.modulePackageName(module);
        String typeName = typeName(module) + "Module";
        Path source = moduleRoot.resolve("src/main/java")
                .resolve(project.modulePackagePath(module)).resolve(typeName + ".java");
        write(source, """
                package %s;

                public final class %s {

                    private %s() {
                    }
                }
                """.formatted(modulePackage, typeName, typeName));
    }

    private static void copyGeneratedFiles(Path generated, Path target, String applicationModule)
            throws IOException {
        try (var entries = Files.list(generated)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals("pom.xml") || name.equals("target")) {
                    continue;
                }
                Path destination = name.equals("src")
                        ? target.resolve(applicationModule).resolve("src")
                        : target.resolve(name);
                copy(entry, destination);
            }
        }
    }

    private static void copy(Path source, Path destination) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(destination);
            try (var entries = Files.list(source)) {
                for (Path child : entries.toList()) {
                    copy(child, destination.resolve(child.getFileName().toString()));
                }
            }
            return;
        }
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static Document readPom(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(pom.toFile());
    }

    private static void writePom(Document document, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var transformer = factory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.transform(new DOMSource(document), new StreamResult(target.toFile()));
    }

    private static Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && name.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static void setChildText(Document document, Element parent, String name, String value) {
        Element child = directChild(parent, name);
        if (child == null) {
            child = document.createElementNS(parent.getNamespaceURI(), name);
            parent.appendChild(child);
        }
        child.setTextContent(value);
    }

    private static void appendText(Document document, Element parent, String name, String value) {
        Element child = document.createElementNS(parent.getNamespaceURI(), name);
        child.setTextContent(value);
        parent.appendChild(child);
    }

    private static void removeDirectChild(Element parent, String name) {
        Element child = directChild(parent, name);
        if (child != null) {
            parent.removeChild(child);
        }
    }

    private static String typeName(String module) {
        StringBuilder result = new StringBuilder();
        for (String part : module.split("[^A-Za-z0-9]+")) {
            if (!part.isBlank()) {
                result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
        }
        if (result.isEmpty() || !Character.isJavaIdentifierStart(result.charAt(0))) {
            result.insert(0, "Generated");
        }
        return result.toString();
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A falha ao limpar staging nao deve ocultar o resultado da geracao.
        }
    }
}
