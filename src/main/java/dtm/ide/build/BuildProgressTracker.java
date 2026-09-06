package dtm.ide.build;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Converte a saida das ferramentas de build em atualizacoes curtas para o progress loader. */
public final class BuildProgressTracker implements Consumer<String> {

    private static final Pattern ANSI = Pattern.compile(
            "\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");

    public record Update(String label, int percent) {
    }

    private record Candidate(JavaModule module, List<String> aliases) {
    }

    private final String action;
    private final List<JavaModule> modules;
    private final List<Candidate> candidates;
    private final Consumer<Update> listener;
    private final Set<JavaModule> seen = new LinkedHashSet<>();

    private String lastLabel;
    private int lastPercent = Integer.MIN_VALUE;

    public BuildProgressTracker(String action, JavaProjectDescriptor descriptor,
                                JavaModule target, Consumer<Update> listener) {
        this.action = action == null || action.isBlank() ? "Build" : action.trim();
        this.listener = listener == null ? update -> { } : listener;
        this.modules = selectModules(descriptor, target);
        this.candidates = candidates(descriptor, modules);
    }

    public Update initial() {
        if (modules.size() == 1) {
            return new Update(moduleLabel(modules.getFirst(), 1), -1);
        }
        return new Update(action, modules.isEmpty() ? -1 : 0);
    }

    @Override
    public void accept(String line) {
        acceptProgress(line, -1);
    }

    /** Recebe tambem os eventos $/progress produzidos pelo JDT LS. */
    public void acceptProgress(String message, int reportedPercent) {
        String clean = clean(message);
        if (clean.isBlank()) {
            return;
        }
        JavaModule module = detectModule(clean);
        if (module != null) {
            seen.add(module);
            int position = positionOf(module);
            int percent = reportedPercent >= 0
                    ? clamp(reportedPercent)
                    : estimatedPercent(position);
            publish(new Update(moduleLabel(module, position), percent));
            return;
        }
        if (reportedPercent >= 0) {
            publish(new Update(action + " - " + removeJavaPrefix(clean),
                    clamp(reportedPercent)));
        }
    }

    public Update completed() {
        return new Update(lastLabel == null ? action : lastLabel, 100);
    }

    private void publish(Update update) {
        if (update.label().equals(lastLabel) && update.percent() == lastPercent) {
            return;
        }
        lastLabel = update.label();
        lastPercent = update.percent();
        listener.accept(update);
    }

    private JavaModule detectModule(String line) {
        String normalized = line.toLowerCase(Locale.ROOT);
        if (!looksLikeModuleProgress(normalized)) {
            return null;
        }
        for (Candidate candidate : candidates) {
            for (String alias : candidate.aliases()) {
                if (containsToken(normalized, alias)) {
                    return candidate.module();
                }
            }
        }
        return null;
    }

    private static boolean looksLikeModuleProgress(String line) {
        return line.contains("building ")
                || line.contains("compiling ")
                || line.contains("compilando ")
                || line.contains("> task ")
                || line.contains(" @ ");
    }

    private static boolean containsToken(String text, String token) {
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf(token, from);
            if (index < 0) {
                return false;
            }
            int end = index + token.length();
            boolean left = index == 0 || !isNameCharacter(text.charAt(index - 1));
            boolean right = end == text.length() || !isNameCharacter(text.charAt(end));
            if (left && right) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private static boolean isNameCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-';
    }

    private int positionOf(JavaModule module) {
        int index = modules.indexOf(module);
        return index < 0 ? Math.max(1, seen.size()) : index + 1;
    }

    private int estimatedPercent(int position) {
        if (modules.size() <= 1) {
            return -1;
        }
        return clamp(Math.max(1, ((position - 1) * 100) / modules.size()));
    }

    private String moduleLabel(JavaModule module, int position) {
        String label = action + " - " + module.name();
        return modules.size() > 1
                ? label + " (" + position + "/" + modules.size() + ")"
                : label;
    }

    private static List<JavaModule> selectModules(JavaProjectDescriptor descriptor,
                                                   JavaModule target) {
        if (target != null && !target.isAggregator()) {
            return List.of(target);
        }
        if (descriptor == null) {
            return target == null ? List.of() : List.of(target);
        }
        List<JavaModule> buildable = descriptor.buildableModules();
        return buildable.isEmpty() && descriptor.rootModule() != null
                ? List.of(descriptor.rootModule()) : List.copyOf(buildable);
    }

    private static List<Candidate> candidates(JavaProjectDescriptor descriptor,
                                               List<JavaModule> modules) {
        List<Candidate> result = new ArrayList<>();
        for (JavaModule module : modules) {
            Set<String> aliases = new LinkedHashSet<>();
            addAlias(aliases, module.name());
            addAlias(aliases, module.artifactId());
            if (descriptor != null) {
                try {
                    String relative = descriptor.root().relativize(module.root()).toString();
                    addAlias(aliases, relative);
                    addAlias(aliases, relative.replace(File.separatorChar, ':').replace('/', ':'));
                } catch (IllegalArgumentException ignored) {
                }
            }
            List<String> ordered = aliases.stream()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .toList();
            result.add(new Candidate(module, ordered));
        }
        result.sort(Comparator.comparingInt((Candidate candidate) -> candidate.aliases().stream()
                .mapToInt(String::length).max().orElse(0)).reversed());
        return List.copyOf(result);
    }

    private static void addAlias(Set<String> aliases, String value) {
        if (value != null && !value.isBlank() && !".".equals(value.trim())) {
            aliases.add(value.trim().toLowerCase(Locale.ROOT));
        }
    }

    private static String clean(String message) {
        return message == null ? "" : ANSI.matcher(message).replaceAll("").trim();
    }

    private static String removeJavaPrefix(String message) {
        return message.regionMatches(true, 0, "Java:", 0, 5)
                ? message.substring(5).trim() : message;
    }

    private static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }
}
