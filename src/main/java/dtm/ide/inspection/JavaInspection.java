package dtm.ide.inspection;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum JavaInspection {

    SPRING_FIELD_INJECTION("spring.fieldInjection", "Injecao por campo"),
    SPRING_TRANSACTIONAL("spring.transactional", "@Transactional nao interceptavel"),
    SPRING_UNSATISFIED("spring.unsatisfied", "Injecao sem bean"),
    JPA_EAGER_RELATION("jpa.eagerRelation", "Relacao singular EAGER"),
    JPA_NO_ARG_CONSTRUCTOR("jpa.noArgConstructor", "Entidade sem construtor vazio"),
    VALUE_UNKNOWN_KEY("value.unknownKey", "Propriedade nao definida"),
    INFRA_NO_ENABLE_SCHEDULING("infra.noEnableScheduling", "Sem @EnableScheduling"),
    INFRA_NO_ENABLE_CACHING("infra.noEnableCaching", "Sem @EnableCaching"),
    INFRA_NO_ENABLE_METHOD_SECURITY("infra.noEnableMethodSecurity", "Sem @EnableMethodSecurity"),
    INFRA_CACHE_WITHOUT_NAME("infra.cacheWithoutName", "Cache sem nome"),
    CONFIG_DEPRECATED_KEY("config.deprecatedKey", "Propriedade obsoleta"),
    CONFIG_UNKNOWN_KEY("config.unknownKey", "Propriedade desconhecida"),
    UNUSED_METHOD("unused", "Metodo sem uso"),
    UNUSED_FIELD("unused.field", "Campo sem uso");

    private final String id;
    private final String label;

    JavaInspection(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static Optional<JavaInspection> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.id.equals(id)).findFirst();
    }

    public static List<JavaInspection> all() {
        return List.of(values());
    }
}
