package dtm.ide.refactor;

import dtm.ide.api.project.editor.IdeWorkspaceEdit;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.stools.component.panels.editor.code.api.Position;
import dtm.stools.component.panels.editor.code.api.Range;
import dtm.stools.component.panels.editor.code.api.TextEdit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MavenModuleRename {

    public enum Scope { BOTH, DIRECTORY, MODULE }

    public enum Problem { EMPTY, SEPARATORS, INVALID, SAME, EXISTS, ARTIFACT_IN_USE }

    public record Target(JavaProjectDescriptor descriptor, JavaModule module, Path parentPom) {

        public Path directory() {
            return module.root();
        }

        public String directoryName() {
            Path name = module.root().getFileName();
            return name == null ? module.root().toString() : name.toString();
        }

        public String artifactId() {
            return module.artifactId();
        }
    }

    private record Leaf(String name, int start, int end, String value) {
    }

    private record Group(List<String> path, List<Leaf> leaves) {

        String name() {
            return path.getLast();
        }

        String parentName() {
            return path.size() < 2 ? "" : path.get(path.size() - 2);
        }

        Leaf first(String name) {
            for (Leaf leaf : leaves) {
                if (leaf.name().equals(name)) {
                    return leaf;
                }
            }
            return null;
        }

        String value(String name) {
            Leaf leaf = first(name);
            return leaf == null ? "" : leaf.value();
        }
    }

    private static final class Frame {
        private final String name;
        private final int contentStart;
        private final List<Leaf> leaves = new ArrayList<>();
        private boolean hasChildren;

        private Frame(String name, int contentStart) {
            this.name = name;
            this.contentStart = contentStart;
        }
    }

    private static final Pattern TAG = Pattern.compile(
            "<!--.*?-->|<!\\[CDATA\\[.*?]]>|<\\?.*?\\?>|<!.*?>|<(/?)([A-Za-z_][\\w.\\-:]*)[^>]*?(/?)>",
            Pattern.DOTALL);
    private static final Pattern ARTIFACT_ID = Pattern.compile("[A-Za-z0-9_.\\-]+");
    private static final String INVALID_FILE_CHARS = "<>:\"|?*";

    private MavenModuleRename() {
    }

    public static Optional<Target> of(JavaProjectDescriptor descriptor, Path directory, Function<Path, String> reader) {
        if (descriptor == null || directory == null || !descriptor.isMaven() || !descriptor.kind().isMultiModule()) {
            return Optional.empty();
        }
        Path normalized = JavaProjectConventions.normalize(directory);
        if (normalized == null || normalized.equals(descriptor.root())
                || !Files.isRegularFile(normalized.resolve(JavaProjectConventions.POM_FILE))) {
            return Optional.empty();
        }
        JavaModule module = descriptor.modules().stream()
                .filter(candidate -> candidate.root().equals(normalized))
                .findFirst()
                .orElse(null);
        if (module == null) {
            return Optional.empty();
        }
        for (Path ancestor = normalized.getParent();
             ancestor != null && ancestor.startsWith(descriptor.root());
             ancestor = ancestor.getParent()) {
            Path pom = ancestor.resolve(JavaProjectConventions.POM_FILE);
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            if (!moduleEntries(read(reader, pom), ancestor, normalized).isEmpty()) {
                return Optional.of(new Target(descriptor, module, pom));
            }
        }
        return Optional.empty();
    }

    public static Scope defaultScope(Target target) {
        return target.directoryName().equals(target.artifactId()) ? Scope.BOTH : Scope.DIRECTORY;
    }

    public static String initialName(Target target, Scope scope) {
        return scope == Scope.DIRECTORY ? target.directoryName() : target.artifactId();
    }

    public static Optional<Problem> validate(Target target, Scope scope, String name) {
        String candidate = name == null ? "" : name.trim();
        if (candidate.isEmpty()) {
            return Optional.of(Problem.EMPTY);
        }
        if (candidate.contains("/") || candidate.contains("\\")) {
            return Optional.of(Problem.SEPARATORS);
        }
        boolean renameDirectory = renamesDirectory(target, scope, candidate);
        boolean renameArtifact = renamesArtifact(target, scope, candidate);
        if (!renameDirectory && !renameArtifact) {
            return Optional.of(Problem.SAME);
        }
        if (renameArtifact && !ARTIFACT_ID.matcher(candidate).matches()) {
            return Optional.of(Problem.INVALID);
        }
        if (renameDirectory && !validDirectoryName(candidate)) {
            return Optional.of(Problem.INVALID);
        }
        if (renameDirectory && occupied(target.directory(), target.directory().resolveSibling(candidate))) {
            return Optional.of(Problem.EXISTS);
        }
        if (renameArtifact && target.descriptor().modules().stream()
                .filter(module -> !module.root().equals(target.directory()))
                .anyMatch(module -> module.artifactId().equals(candidate)
                        && module.groupId().equals(target.module().groupId()))) {
            return Optional.of(Problem.ARTIFACT_IN_USE);
        }
        return Optional.empty();
    }

    public static IdeWorkspaceEdit plan(Target target, Scope scope, String name, Function<Path, String> reader) {
        String candidate = name == null ? "" : name.trim();
        if (validate(target, scope, candidate).isPresent()) {
            return IdeWorkspaceEdit.empty();
        }
        boolean renameDirectory = renamesDirectory(target, scope, candidate);
        boolean renameArtifact = renamesArtifact(target, scope, candidate);
        Map<Path, List<TextEdit>> edits = new LinkedHashMap<>();
        Map<Path, String> texts = new LinkedHashMap<>();

        if (renameDirectory) {
            Path parentPom = target.parentPom();
            String text = texts.computeIfAbsent(parentPom, pom -> read(reader, pom));
            for (Leaf entry : moduleEntries(text, parentPom.getParent(), target.directory())) {
                add(edits, parentPom, text, entry, renamedModuleEntry(entry.value(), candidate));
            }
        }

        if (renameArtifact) {
            for (Path pom : reactorPoms(target.descriptor())) {
                String text = texts.computeIfAbsent(pom, file -> read(reader, file));
                boolean own = pom.getParent().equals(target.directory());
                for (Group group : scan(text)) {
                    Leaf artifact = group.first("artifactId");
                    if (artifact == null) {
                        continue;
                    }
                    if (own && group.path().size() == 1 && artifact.value().equals(target.artifactId())) {
                        add(edits, pom, text, artifact, candidate);
                    } else if (referencesModule(group, target.module())) {
                        add(edits, pom, text, artifact, candidate);
                    }
                }
            }
        }

        List<IdeWorkspaceEdit.Operation> operations = new ArrayList<>();
        edits.forEach((file, fileEdits) -> operations.add(new IdeWorkspaceEdit.TextEdits(file, fileEdits)));
        if (renameDirectory) {
            operations.add(new IdeWorkspaceEdit.RenameFile(target.directory(),
                    target.directory().resolveSibling(candidate)));
        }
        return new IdeWorkspaceEdit(operations);
    }

    private static boolean renamesDirectory(Target target, Scope scope, String name) {
        return scope != Scope.MODULE && !name.equals(target.directoryName());
    }

    private static boolean renamesArtifact(Target target, Scope scope, String name) {
        return scope != Scope.DIRECTORY && !name.equals(target.artifactId());
    }

    private static boolean validDirectoryName(String name) {
        if (name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || INVALID_FILE_CHARS.indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean occupied(Path directory, Path sibling) {
        if (!Files.exists(sibling)) {
            return false;
        }
        try {
            return !Files.isSameFile(directory, sibling);
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean referencesModule(Group group, JavaModule module) {
        boolean dependencyLike = group.name().equals("dependency") || group.name().equals("exclusion");
        boolean parent = group.name().equals("parent") && group.path().size() == 2;
        if (!dependencyLike && !parent) {
            return false;
        }
        if (!group.value("artifactId").equals(module.artifactId())) {
            return false;
        }
        String groupId = group.value("groupId");
        return groupId.isEmpty() || module.groupId().isEmpty() || groupId.equals(module.groupId())
                || groupId.contains("${");
    }

    private static List<Path> reactorPoms(JavaProjectDescriptor descriptor) {
        Set<Path> poms = new LinkedHashSet<>();
        Path rootPom = descriptor.root().resolve(JavaProjectConventions.POM_FILE);
        if (Files.isRegularFile(rootPom)) {
            poms.add(rootPom);
        }
        for (JavaModule module : descriptor.modules()) {
            Path pom = module.root().resolve(JavaProjectConventions.POM_FILE);
            if (Files.isRegularFile(pom)) {
                poms.add(pom);
            }
        }
        return List.copyOf(poms);
    }

    private static List<Leaf> moduleEntries(String text, Path pomDirectory, Path moduleRoot) {
        List<Leaf> entries = new ArrayList<>();
        for (Group group : scan(text)) {
            if (!group.name().equals("modules")) {
                continue;
            }
            boolean inProject = group.path().size() == 2;
            boolean inProfile = group.path().size() == 4 && group.parentName().equals("profile");
            if (!inProject && !inProfile) {
                continue;
            }
            for (Leaf leaf : group.leaves()) {
                if (leaf.name().equals("module") && !leaf.value().isEmpty()
                        && moduleRoot.equals(JavaProjectConventions.normalize(pomDirectory.resolve(leaf.value())))) {
                    entries.add(leaf);
                }
            }
        }
        return entries;
    }

    static String renamedModuleEntry(String entry, String newName) {
        String trimmed = entry;
        String suffix = "";
        while (trimmed.endsWith("/") || trimmed.endsWith("\\")) {
            suffix = trimmed.charAt(trimmed.length() - 1) + suffix;
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int separator = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        return trimmed.substring(0, separator + 1) + newName + suffix;
    }

    private static void add(Map<Path, List<TextEdit>> edits, Path file, String text, Leaf leaf, String newText) {
        if (Objects.equals(leaf.value(), newText)) {
            return;
        }
        edits.computeIfAbsent(file, ignored -> new ArrayList<>())
                .add(new TextEdit(new Range(position(text, leaf.start()), position(text, leaf.end())), newText));
    }

    private static Position position(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return Position.of(line, offset - lineStart);
    }

    private static String read(Function<Path, String> reader, Path file) {
        String text = reader == null ? null : reader.apply(file);
        if (text == null) {
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                text = "";
            }
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static List<Group> scan(String text) {
        List<Group> groups = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        Matcher matcher = TAG.matcher(text);
        while (matcher.find()) {
            String rawName = matcher.group(2);
            if (rawName == null) {
                continue;
            }
            String name = localName(rawName);
            boolean closing = !matcher.group(1).isEmpty();
            boolean selfClosing = !matcher.group(3).isEmpty();
            if (!closing) {
                if (!stack.isEmpty()) {
                    stack.peek().hasChildren = true;
                }
                if (!selfClosing) {
                    stack.push(new Frame(name, matcher.end()));
                }
                continue;
            }
            if (stack.isEmpty() || !stack.peek().name.equals(name)) {
                continue;
            }
            Frame frame = stack.pop();
            if (!frame.leaves.isEmpty()) {
                groups.add(new Group(path(stack, frame.name), List.copyOf(frame.leaves)));
            }
            if (!frame.hasChildren && !stack.isEmpty()) {
                int start = frame.contentStart;
                int end = matcher.start();
                while (start < end && Character.isWhitespace(text.charAt(start))) {
                    start++;
                }
                while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
                    end--;
                }
                stack.peek().leaves.add(new Leaf(name, start, end, text.substring(start, end)));
            }
        }
        return groups;
    }

    private static List<String> path(Deque<Frame> stack, String last) {
        List<String> path = new ArrayList<>(stack.size() + 1);
        stack.descendingIterator().forEachRemaining(frame -> path.add(frame.name));
        path.add(last);
        return List.copyOf(path);
    }

    private static String localName(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
