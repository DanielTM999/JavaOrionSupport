package dtm.ide.run.form;

import dtm.ide.run.MainClassScanner;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RunFormChoices(
        List<String> modules,
        Map<String, String> jdks,
        List<MainClassScanner.MainClass> mainClasses,
        List<Path> jars,
        List<String> buildTargets,
        List<String> mavenProfiles,
        List<String> springProfiles
) {

    public RunFormChoices {
        modules = modules == null ? List.of() : List.copyOf(modules);
        jdks = jdks == null ? Map.of() : new LinkedHashMap<>(jdks);
        mainClasses = mainClasses == null ? List.of() : List.copyOf(mainClasses);
        jars = jars == null ? List.of() : List.copyOf(jars);
        buildTargets = buildTargets == null ? List.of() : List.copyOf(buildTargets);
        mavenProfiles = mavenProfiles == null ? List.of() : List.copyOf(mavenProfiles);
        springProfiles = springProfiles == null ? List.of() : List.copyOf(springProfiles);
    }

    public RunFormChoices(List<String> modules, Map<String, String> jdks,
                          List<MainClassScanner.MainClass> mainClasses, List<Path> jars,
                          List<String> buildTargets, List<String> mavenProfiles) {
        this(modules, jdks, mainClasses, jars, buildTargets, mavenProfiles, List.of());
    }

    public static RunFormChoices empty() {
        return new RunFormChoices(List.of(), Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    public List<String> mainClassNames() {
        return mainClasses.stream().map(MainClassScanner.MainClass::qualifiedName).toList();
    }
}
