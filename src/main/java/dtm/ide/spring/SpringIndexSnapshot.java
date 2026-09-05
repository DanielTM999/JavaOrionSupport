package dtm.ide.spring;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record SpringIndexSnapshot(
        Path projectRoot,
        List<SpringBean> beans,
        List<SpringInjection> injections,
        List<SpringEndpoint> endpoints
) {

    public SpringIndexSnapshot {
        beans = beans == null ? List.of() : List.copyOf(beans);
        injections = injections == null ? List.of() : List.copyOf(injections);
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    public static SpringIndexSnapshot empty(Path projectRoot) {
        return new SpringIndexSnapshot(projectRoot, List.of(), List.of(), List.of());
    }

    public List<SpringEndpoint> sortedEndpoints() {
        return endpoints.stream().sorted().toList();
    }

    public boolean isEmpty() {
        return beans.isEmpty();
    }

    public List<SpringBean> beansProviding(String type) {
        return beans.stream().filter(bean -> bean.provides(type)).toList();
    }

    public Optional<SpringBean> resolve(SpringInjection injection) {
        if (injection == null) {
            return Optional.empty();
        }
        List<SpringBean> candidates = beansProviding(injection.targetType());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        if (injection.hasQualifier()) {
            return candidates.stream()
                    .filter(bean -> injection.qualifier().equals(bean.qualifier())
                            || injection.qualifier().equals(bean.name()))
                    .findFirst();
        }
        if (candidates.size() == 1) {
            return Optional.of(candidates.getFirst());
        }
        List<SpringBean> primary = candidates.stream().filter(SpringBean::primary).toList();
        if (primary.size() == 1) {
            return Optional.of(primary.getFirst());
        }
        return candidates.stream()
                .filter(bean -> bean.name().equals(injection.memberName()))
                .findFirst();
    }

    public List<SpringInjection> injectionsOf(SpringBean bean) {
        if (bean == null) {
            return List.of();
        }
        return injections.stream()
                .filter(injection -> resolve(injection)
                        .map(resolved -> resolved.type().equals(bean.type()))
                        .orElse(false))
                .toList();
    }

    public List<SpringInjection> dependenciesOf(SpringBean bean) {
        if (bean == null) {
            return List.of();
        }
        return injections.stream()
                .filter(injection -> injection.ownerType().equals(bean.type()))
                .toList();
    }

    public List<SpringBean> beansIn(Path file) {
        if (file == null) {
            return List.of();
        }
        return beans.stream().filter(bean -> file.equals(bean.file())).toList();
    }

    public List<SpringInjection> injectionsIn(Path file) {
        if (file == null) {
            return List.of();
        }
        return injections.stream().filter(injection -> file.equals(injection.file())).toList();
    }

    public Optional<SpringBean> beanNamed(String name) {
        return beans.stream().filter(bean -> bean.name().equals(name)).findFirst();
    }

    public Map<SpringStereotype, List<SpringBean>> byStereotype() {
        Map<SpringStereotype, List<SpringBean>> grouped = new LinkedHashMap<>();
        for (SpringStereotype stereotype : SpringStereotype.values()) {
            List<SpringBean> group = beans.stream()
                    .filter(bean -> bean.stereotype() == stereotype)
                    .sorted(Comparator.comparing(SpringBean::simpleName))
                    .toList();
            if (!group.isEmpty()) {
                grouped.put(stereotype, group);
            }
        }
        return grouped;
    }

    public SpringIndexSnapshot replacingFile(Path file, SpringSourceParser.ParseResult parsed) {
        List<SpringBean> updatedBeans = new ArrayList<>(
                beans.stream().filter(bean -> !file.equals(bean.file())).toList());
        List<SpringInjection> updatedInjections = new ArrayList<>(
                injections.stream().filter(injection -> !file.equals(injection.file())).toList());

        List<SpringEndpoint> updatedEndpoints = new ArrayList<>(
                endpoints.stream().filter(endpoint -> !file.equals(endpoint.file())).toList());

        updatedBeans.addAll(parsed.beans());
        updatedInjections.addAll(parsed.injections());
        updatedEndpoints.addAll(parsed.endpoints());
        return new SpringIndexSnapshot(projectRoot, updatedBeans, updatedInjections, updatedEndpoints);
    }
}
