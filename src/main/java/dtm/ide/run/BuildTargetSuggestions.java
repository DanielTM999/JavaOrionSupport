package dtm.ide.run;

import dtm.ide.build.BuildToolModel;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class BuildTargetSuggestions {

    private BuildTargetSuggestions() {
    }

    public static List<String> targets(JavaProjectDescriptor descriptor, JavaModule module,
                                       boolean gradle) {
        return targets(descriptor == null ? null : BuildToolModel.load(descriptor), module, gradle);
    }

    public static List<String> targets(BuildToolModel model, JavaModule module, boolean gradle) {
        Set<String> targets = new LinkedHashSet<>();
        if (model != null) {
            collect(model, module, targets);
        }
        targets.addAll(gradle ? JavaRunTypes.DEFAULT_GRADLE_TASKS
                : JavaRunTypes.DEFAULT_MAVEN_GOALS);
        return List.copyOf(targets);
    }

    public static List<String> profiles(JavaProjectDescriptor descriptor) {
        if (descriptor == null || !descriptor.isMaven()) {
            return List.of();
        }
        return profiles(BuildToolModel.load(descriptor), true);
    }

    public static List<String> profiles(BuildToolModel model, boolean maven) {
        if (model == null || !maven) {
            return List.of();
        }
        return model.profiles().stream()
                .map(BuildToolModel.Node::name)
                .toList();
    }

    private static void collect(BuildToolModel model, JavaModule module, Set<String> targets) {
        for (BuildToolModel.Node project : model.projects()) {
            if (module != null && project.module() != null
                    && !project.module().root().equals(module.root())) {
                continue;
            }
            project.children().stream()
                    .filter(group -> group.kind() == BuildToolModel.Kind.GROUP)
                    .flatMap(group -> group.children().stream())
                    .forEach(node -> collectNode(node, targets));
        }
    }

    private static void collectNode(BuildToolModel.Node node, Set<String> targets) {
        if (node.kind() == BuildToolModel.Kind.COMMAND && !node.command().isEmpty()) {
            targets.addAll(node.command());
        }
        node.children().forEach(child -> collectNode(child, targets));
    }
}
