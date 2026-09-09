package dtm.ide.spring;

import dtm.ide.spring.infra.SpringInfraModel;
import dtm.ide.spring.jpa.JpaEntity;
import dtm.ide.spring.jpa.JpaPropertyResolver;
import dtm.ide.spring.jpa.JpaRepositoryInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public record SpringIndexSnapshot(
        Path projectRoot,
        List<SpringBean> beans,
        List<SpringInjection> injections,
        List<SpringEndpoint> endpoints,
        List<JpaEntity> entities,
        List<JpaRepositoryInfo> repositories,
        List<SpringPropertyUsage> propertyUsages,
        List<JavaType> types,
        List<SpringBean> runtimeBeans,
        SpringInfraModel infra,
        JavaTypeGraph graph
) {

    public SpringIndexSnapshot {
        beans = beans == null ? List.of() : List.copyOf(beans);
        injections = injections == null ? List.of() : List.copyOf(injections);
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        entities = entities == null ? List.of() : List.copyOf(entities);
        repositories = repositories == null ? List.of() : List.copyOf(repositories);
        propertyUsages = propertyUsages == null ? List.of() : List.copyOf(propertyUsages);
        types = types == null ? List.of() : List.copyOf(types);
        runtimeBeans = runtimeBeans == null ? List.of() : List.copyOf(runtimeBeans);
        infra = infra == null ? SpringInfraModel.empty() : infra;
        graph = graph == null ? JavaTypeGraph.of(types) : graph;
    }

    public SpringIndexSnapshot(Path projectRoot, List<SpringBean> beans,
                               List<SpringInjection> injections, List<SpringEndpoint> endpoints) {
        this(projectRoot, beans, injections, endpoints, List.of(), List.of(), List.of(),
                List.of(), List.of(), SpringInfraModel.empty(), null);
    }

    public SpringIndexSnapshot(Path projectRoot, List<SpringBean> beans,
                               List<SpringInjection> injections, List<SpringEndpoint> endpoints,
                               List<JpaEntity> entities, List<JpaRepositoryInfo> repositories) {
        this(projectRoot, beans, injections, endpoints, entities, repositories, List.of(),
                List.of(), List.of(), SpringInfraModel.empty(), null);
    }

    public SpringIndexSnapshot(Path projectRoot, List<SpringBean> beans,
                               List<SpringInjection> injections, List<SpringEndpoint> endpoints,
                               List<JpaEntity> entities, List<JpaRepositoryInfo> repositories,
                               List<SpringPropertyUsage> propertyUsages, List<JavaType> types) {
        this(projectRoot, beans, injections, endpoints, entities, repositories, propertyUsages,
                types, List.of(), SpringInfraModel.empty(), null);
    }

    public SpringIndexSnapshot(Path projectRoot, List<SpringBean> beans,
                               List<SpringInjection> injections, List<SpringEndpoint> endpoints,
                               List<JpaEntity> entities, List<JpaRepositoryInfo> repositories,
                               List<SpringPropertyUsage> propertyUsages, List<JavaType> types,
                               SpringInfraModel infra) {
        this(projectRoot, beans, injections, endpoints, entities, repositories, propertyUsages,
                types, List.of(), infra, null);
    }

    public static SpringIndexSnapshot empty(Path projectRoot) {
        return new SpringIndexSnapshot(projectRoot, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(),
                SpringInfraModel.empty(), null);
    }

    public List<SpringEndpoint> sortedEndpoints() {
        return endpoints.stream().sorted().toList();
    }

    public boolean isEmpty() {
        return beans.isEmpty();
    }

    public List<SpringBean> beansProviding(String type) {
        return beansProviding(type, null);
    }

    public List<SpringBean> beansProviding(String type, Path requestedFrom) {
        InjectionTarget target = InjectionTarget.of(type);
        JavaType context = contextOf(requestedFrom);
        List<SpringBean> matched = allBeans().stream()
                .filter(bean -> providesType(bean, target.type(), context))
                .toList();
        return matched.isEmpty() && !target.type().equals(type)
                ? allBeans().stream().filter(bean -> providesType(bean, type, context)).toList()
                : matched;
    }

    public List<SpringBean> allBeans() {
        if (runtimeBeans.isEmpty()) {
            return beans;
        }
        List<SpringBean> merged = new ArrayList<>(beans);
        Set<String> knownTypes = beans.stream()
                .map(SpringBean::type)
                .collect(java.util.stream.Collectors.toSet());
        for (SpringBean runtime : runtimeBeans) {
            if (!knownTypes.contains(runtime.type())) {
                merged.add(runtime);
            }
        }
        return List.copyOf(merged);
    }

    public SpringIndexSnapshot withRuntimeBeans(List<SpringBean> discovered) {
        return new SpringIndexSnapshot(projectRoot, beans, injections, endpoints, entities,
                repositories, propertyUsages, types, discovered, infra, graph);
    }

    public boolean hasRuntimeBeans() {
        return !runtimeBeans.isEmpty();
    }

    private boolean providesType(SpringBean bean, String requestedType, JavaType context) {
        JavaType declared = graph.byQualifiedName(bean.type()).orElse(null);
        if (declared != null && graph.isAssignable(declared, requestedType, context)) {
            return true;
        }
        return bean.provides(requestedType);
    }

    private JavaType contextOf(Path file) {
        if (file == null) {
            return null;
        }
        return types.stream().filter(type -> file.equals(type.file())).findFirst().orElse(null);
    }

    public JavaTypeGraph typeGraph() {
        return graph;
    }

    public InjectionTarget targetOf(SpringInjection injection) {
        return injection == null ? InjectionTarget.of("") : InjectionTarget.of(injection.targetType());
    }

    public Optional<SpringBean> resolve(SpringInjection injection) {
        if (injection == null) {
            return Optional.empty();
        }
        List<SpringBean> candidates = beansProviding(injection.targetType(), injection.file());
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String qualifier = effectiveQualifier(injection);
        if (!qualifier.isBlank()) {
            return candidates.stream()
                    .filter(bean -> qualifierMatches(bean, qualifier))
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

        List<JpaEntity> updatedEntities = new ArrayList<>(
                entities.stream().filter(entity -> !file.equals(entity.file())).toList());
        List<JpaRepositoryInfo> updatedRepositories = new ArrayList<>(
                repositories.stream().filter(repository -> !file.equals(repository.file())).toList());
        List<SpringPropertyUsage> updatedUsages = new ArrayList<>(
                propertyUsages.stream().filter(usage -> !file.equals(usage.file())).toList());
        List<JavaType> updatedTypes = new ArrayList<>(
                types.stream().filter(type -> !file.equals(type.file())).toList());

        updatedBeans.addAll(parsed.beans());
        updatedInjections.addAll(parsed.injections());
        updatedEndpoints.addAll(parsed.endpoints());
        updatedEntities.addAll(parsed.entities());
        updatedRepositories.addAll(parsed.repositories());
        updatedUsages.addAll(parsed.propertyUsages());
        updatedTypes.addAll(parsed.types());
        return new SpringIndexSnapshot(projectRoot, updatedBeans, updatedInjections,
                updatedEndpoints, updatedEntities, updatedRepositories, updatedUsages,
                updatedTypes, runtimeBeans, infra.replacingFile(file, parsed.infra()), null);
    }

    public List<SpringPropertyUsage> propertyUsagesIn(Path file) {
        if (file == null) {
            return List.of();
        }
        return propertyUsages.stream().filter(usage -> file.equals(usage.file())).toList();
    }

    public List<SpringPropertyUsage> usagesOfProperty(String key) {
        if (key == null || key.isBlank()) {
            return List.of();
        }
        String needle = key.trim();
        return propertyUsages.stream()
                .filter(usage -> usage.isPrefix()
                        ? needle.startsWith(usage.key())
                        : usage.key().equals(needle))
                .toList();
    }

    public Optional<JpaEntity> entityNamed(String type) {
        if (type == null || type.isBlank()) {
            return Optional.empty();
        }
        String simple = SpringBean.simpleNameOf(type);
        return entities.stream()
                .filter(entity -> entity.type().equals(type)
                        || entity.simpleName().equals(simple))
                .findFirst();
    }

    public JpaPropertyResolver.EntityLookup entityLookup() {
        return this::entityNamed;
    }

    public List<JpaRepositoryInfo> repositoriesFor(JpaEntity entity) {
        if (entity == null) {
            return List.of();
        }
        return repositories.stream()
                .filter(repository -> repository.entitySimpleName().equals(entity.simpleName()))
                .toList();
    }

    public List<JpaEntity> entitiesIn(Path file) {
        if (file == null) {
            return List.of();
        }
        return entities.stream().filter(entity -> file.equals(entity.file())).toList();
    }

    public List<JpaRepositoryInfo> repositoriesIn(Path file) {
        if (file == null) {
            return List.of();
        }
        return repositories.stream().filter(repository -> file.equals(repository.file())).toList();
    }

    public List<SpringBean> candidatesFor(SpringInjection injection) {
        if (injection == null) {
            return List.of();
        }
        List<SpringBean> candidates = beansProviding(injection.targetType(), injection.file());
        String qualifier = effectiveQualifier(injection);
        if (qualifier.isBlank()) {
            return candidates;
        }
        List<SpringBean> matching = candidates.stream()
                .filter(bean -> qualifierMatches(bean, qualifier))
                .toList();
        return matching.isEmpty() ? candidates : matching;
    }

    public List<SpringBean> beansMatchingQualifier(String qualifier) {
        if (qualifier == null || qualifier.isBlank()) {
            return List.of();
        }
        return allBeans().stream().filter(bean -> qualifierMatches(bean, qualifier)).toList();
    }

    public Set<String> metaQualifierAnnotations() {
        return types.stream()
                .filter(JavaType::isAnnotation)
                .filter(type -> type.annotatedWith("qualifier"))
                .map(type -> type.simpleName().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public String effectiveQualifier(SpringInjection injection) {
        if (injection == null) {
            return "";
        }
        if (injection.hasQualifier()) {
            return injection.qualifier();
        }
        Set<String> meta = metaQualifierAnnotations();
        return injection.annotations().stream()
                .filter(meta::contains)
                .findFirst()
                .orElse("");
    }

    public boolean qualifierMatches(SpringBean bean, String requested) {
        if (bean == null || requested == null || requested.isBlank()) {
            return false;
        }
        if (bean.matchesQualifier(requested)) {
            return true;
        }
        Set<String> meta = metaQualifierAnnotations();
        String needle = requested.toLowerCase(Locale.ROOT);
        return meta.contains(needle) && bean.traits().qualifierAnnotations().contains(needle);
    }

    public List<String> qualifiersOf(SpringBean bean) {
        List<String> names = new ArrayList<>();
        if (bean.hasQualifier()) {
            names.add(bean.qualifier());
        } else if (!bean.name().isBlank()) {
            names.add(bean.name());
        }
        Set<String> meta = metaQualifierAnnotations();
        for (String annotation : bean.traits().qualifierAnnotations()) {
            if (meta.contains(annotation)) {
                names.add(annotation);
            }
        }
        return List.copyOf(names);
    }

    public List<String> beanNames() {
        return allBeans().stream()
                .flatMap(bean -> qualifiersOf(bean).stream())
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    public List<String> profileNames() {
        return beans.stream()
                .flatMap(bean -> bean.profiles().stream())
                .filter(profile -> !profile.isBlank())
                .distinct()
                .sorted()
                .toList();
    }
}
