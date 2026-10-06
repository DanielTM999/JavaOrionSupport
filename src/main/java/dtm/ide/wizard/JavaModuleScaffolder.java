package dtm.ide.wizard;

import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.JavaModule;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JavaModuleScaffolder {
    public record Plan(Path directory, Path parentBuild, String previousParent, String updatedParent,
                       Map<Path, String> files, boolean convertsPackaging) { }
    private JavaModuleScaffolder() { }

    public static Plan prepare(JavaProjectDescriptor project, Path selected, String name) throws Exception {
        if (name == null || !name.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Nome de modulo invalido.");
        Path destination = selected.toAbsolutePath().normalize().resolve(name);
        if (!destination.startsWith(project.root()) || Files.exists(destination))
            throw new IllegalArgumentException("O destino ja existe ou esta fora do projeto: " + destination);
        Map<Path, String> files = new LinkedHashMap<>();
        if (project.isMaven()) {
            JavaModule owner = project.moduleOf(selected).orElse(project.rootModule());
            if (owner == null) throw new IllegalArgumentException("Modulo pai nao encontrado.");
            Path parentFile = owner.root().resolve("pom.xml");
            String original = Files.readString(parentFile);
            Model parent = new MavenXpp3Reader().read(new StringReader(original));
            String group = parent.getGroupId() != null ? parent.getGroupId()
                    : parent.getParent() == null ? null : parent.getParent().getGroupId();
            String version = parent.getVersion() != null ? parent.getVersion()
                    : parent.getParent() == null ? null : parent.getParent().getVersion();
            if (group == null || version == null || parent.getArtifactId() == null)
                throw new IllegalArgumentException("O POM pai precisa de groupId, artifactId e version validos.");
            String relative = owner.root().relativize(destination).toString().replace('\\', '/');
            if (parent.getModules().contains(relative)) throw new IllegalArgumentException("Modulo ja registrado.");
            boolean convert = !"pom".equals(parent.getPackaging());
            parent.setPackaging("pom");
            parent.addModule(relative);
            Model child = new Model();
            child.setModelVersion("4.0.0");
            child.setArtifactId(name);
            Parent inheritance = new Parent();
            inheritance.setGroupId(group); inheritance.setArtifactId(parent.getArtifactId()); inheritance.setVersion(version);
            inheritance.setRelativePath(destination.relativize(parentFile).toString().replace('\\', '/'));
            child.setParent(inheritance);
            files.put(destination.resolve("pom.xml"), xml(child));
            return new Plan(destination, parentFile, original, registerMavenModule(original, relative, convert), files, convert);
        }
        if (!project.isGradle()) throw new IllegalArgumentException("Criacao de modulo requer Maven ou Gradle.");
        Path settingsKts = project.root().resolve("settings.gradle.kts");
        boolean kotlin = Files.exists(settingsKts) || (!Files.exists(project.root().resolve("settings.gradle"))
                && Files.exists(project.root().resolve("build.gradle.kts")));
        Path settings = project.root().resolve(kotlin ? "settings.gradle.kts" : "settings.gradle");
        String original = Files.exists(settings) ? Files.readString(settings) : null;
        String modulePath = project.root().relativize(destination).toString().replace('\\', ':').replace('/', ':');
        if (original != null && java.util.regex.Pattern.compile("[\"']:?" + java.util.regex.Pattern.quote(modulePath) + "[\"']")
                .matcher(original).find()) throw new IllegalArgumentException("Modulo ja registrado nas configuracoes Gradle.");
        String include = "include(\":" + modulePath + "\")";
        String updated = (original == null ? "" : original) + "\n" + include + "\n";
        String build = kotlin ? "plugins { java }\n" : "plugins { id 'java' }\n";
        build += "group = rootProject.group\nversion = rootProject.version\n";
        if (project.jdkVersion() != null) build += "java { toolchain { languageVersion.set(JavaLanguageVersion.of("
                + project.jdkVersion() + ")) } }\n";
        files.put(destination.resolve(kotlin ? "build.gradle.kts" : "build.gradle"), build);
        return new Plan(destination, settings, original, updated, files, false);
    }

    static String registerMavenModule(String original, String module, boolean convert) {
        var comments = java.util.regex.Pattern.compile("(?s)<!--.*?-->").matcher(original);
        StringBuilder structural = new StringBuilder(original);
        while (comments.find()) for (int i = comments.start(); i < comments.end(); i++) structural.setCharAt(i, ' ');
        var tags = java.util.regex.Pattern.compile("<(/?)([A-Za-z_][\\w:.-]*)(?:\\s[^>]*?)?\\s*(/?)>").matcher(structural);
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        int projectEnd = -1, modulesEnd = -1, packagingStart = -1, packagingEnd = -1;
        int emptyModulesStart = -1, emptyModulesEnd = -1;
        while (tags.find()) {
            String name = tags.group(2);
            if (tags.group(1).equals("/")) {
                if (stack.size() == 2 && stack.peek().equals("modules")) modulesEnd = tags.start();
                if (stack.size() == 2 && stack.peek().equals("packaging")) packagingEnd = tags.end();
                if (stack.size() == 1 && stack.peek().equals("project")) projectEnd = tags.start();
                if (!stack.isEmpty()) stack.pop();
            } else if (tags.group(3).equals("/")) {
                if (stack.size() == 1 && name.equals("modules")) { emptyModulesStart = tags.start(); emptyModulesEnd = tags.end(); }
                if (stack.size() == 1 && name.equals("packaging")) { packagingStart = tags.start(); packagingEnd = tags.end(); }
            } else {
                if (stack.size() == 1 && name.equals("packaging")) packagingStart = tags.start();
                stack.push(name);
            }
        }
        if (projectEnd < 0) throw new IllegalArgumentException("POM pai invalido.");
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        record Edit(int start, int end, String text) { }
        List<Edit> edits = new ArrayList<>();
        String moduleElement = "<module>" + module + "</module>";
        if (emptyModulesStart >= 0) edits.add(new Edit(emptyModulesStart, emptyModulesEnd,
                "<modules>" + newline + "        " + moduleElement + newline + "    </modules>"));
        else if (modulesEnd >= 0) edits.add(new Edit(modulesEnd, modulesEnd, "    " + moduleElement + newline + "    "));
        else edits.add(new Edit(projectEnd, projectEnd, "    <modules>" + newline + "        " + moduleElement + newline + "    </modules>" + newline));
        if (convert) {
            if (packagingStart >= 0 && packagingEnd >= 0) edits.add(new Edit(packagingStart, packagingEnd, "<packaging>pom</packaging>"));
            else edits.add(new Edit(projectEnd, projectEnd, "    <packaging>pom</packaging>" + newline));
        }
        edits.sort(java.util.Comparator.comparingInt(Edit::start).reversed());
        StringBuilder result = new StringBuilder(original);
        for (Edit edit : edits) result.replace(edit.start(), edit.end(), edit.text());
        return result.toString();
    }

    private static String xml(Model model) throws IOException {
        StringWriter output = new StringWriter();
        new MavenXpp3Writer().write(output, model);
        return output.toString();
    }

    public static void create(Plan plan) throws IOException {
        if (Files.exists(plan.directory())) throw new IOException("Destino ja existe: " + plan.directory());
        String current = Files.exists(plan.parentBuild()) ? Files.readString(plan.parentBuild()) : null;
        if (!java.util.Objects.equals(current, plan.previousParent())) throw new IOException("O build pai mudou; abra o dialogo novamente.");
        List<Path> created = new ArrayList<>();
        boolean parentWritten = false;
        try {
            Files.createDirectory(plan.directory()); created.add(plan.directory());
            for (String relative : List.of("src", "src/main", "src/test", "src/main/java", "src/main/resources", "src/test/java", "src/test/resources")) {
                Path dir = plan.directory().resolve(relative); Files.createDirectory(dir); created.add(dir);
            }
            for (var file : plan.files().entrySet()) {
                Files.writeString(file.getKey(), file.getValue(), StandardOpenOption.CREATE_NEW); created.add(file.getKey());
            }
            parentWritten = true;
            Files.writeString(plan.parentBuild(), plan.updatedParent());
        } catch (IOException failure) {
            if (parentWritten) {
                try {
                    if (plan.previousParent() == null) Files.deleteIfExists(plan.parentBuild());
                    else Files.writeString(plan.parentBuild(), plan.previousParent());
                } catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            for (Path path : created.reversed()) {
                try { Files.deleteIfExists(path); } catch (IOException rollback) { failure.addSuppressed(rollback); }
            }
            throw failure;
        }
    }
}
