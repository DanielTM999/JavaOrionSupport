package dtm.ide.run.chain;

import java.util.ArrayList;
import java.util.List;

public record RunChainStep(String configurationId, boolean debug, boolean waitForExit, String label) {

    private static final String FIELD_SEPARATOR = "|";
    private static final String MODE_DEBUG = "debug";
    private static final String MODE_RUN = "run";

    public RunChainStep {
        configurationId = configurationId == null ? "" : configurationId.trim();
        label = label == null ? "" : label.trim();
    }

    public static RunChainStep of(String configurationId, boolean debug, String label) {
        return new RunChainStep(configurationId, debug, true, label);
    }

    public boolean isValid() {
        return !configurationId.isEmpty();
    }

    public String display() {
        return label.isEmpty() ? configurationId : label;
    }

    public RunChainStep withDebug(boolean value) {
        return new RunChainStep(configurationId, value, waitForExit, label);
    }

    public RunChainStep withWaitForExit(boolean value) {
        return new RunChainStep(configurationId, debug, value, label);
    }

    public RunChainStep withLabel(String value) {
        return new RunChainStep(configurationId, debug, waitForExit, value);
    }

    public String encode() {
        return String.join(FIELD_SEPARATOR,
                configurationId,
                debug ? MODE_DEBUG : MODE_RUN,
                Boolean.toString(waitForExit),
                label.replace(FIELD_SEPARATOR, " ").replace("\n", " "));
    }

    public static String encodeAll(List<RunChainStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        steps.stream().filter(RunChainStep::isValid).forEach(step -> lines.add(step.encode()));
        return String.join("\n", lines);
    }

    public static List<RunChainStep> decodeAll(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<RunChainStep> steps = new ArrayList<>();
        for (String line : raw.split("\\R")) {
            decode(line).filter(RunChainStep::isValid).ifPresent(steps::add);
        }
        return List.copyOf(steps);
    }

    public static java.util.Optional<RunChainStep> decode(String line) {
        if (line == null || line.isBlank()) {
            return java.util.Optional.empty();
        }
        String[] fields = line.split("\\" + FIELD_SEPARATOR, -1);
        String configurationId = fields[0].trim();
        if (configurationId.isEmpty()) {
            return java.util.Optional.empty();
        }
        boolean debug = fields.length > 1 && MODE_DEBUG.equalsIgnoreCase(fields[1].trim());
        boolean waitForExit = fields.length <= 2 || !"false".equalsIgnoreCase(fields[2].trim());
        String label = fields.length > 3 ? fields[3].trim() : "";
        return java.util.Optional.of(new RunChainStep(configurationId, debug, waitForExit, label));
    }
}
