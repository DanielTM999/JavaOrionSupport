package dtm.ide.spring.jpa;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaSourceParserTest {

    private static final Path FILE = Path.of("/projeto/src/main/java/com/example/Arquivo.java");

    @Test
    void readsAnEntityWithTableAndColumns() {
        JpaSourceParser.ParseResult result = parse("""
                package com.example.dominio;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;

                @Entity
                @Table(name = "clientes")
                public class Cliente {

                    @Id
                    private Long id;

                    @Column(name = "nome_completo")
                    private String nomeCompleto;

                    private String email;
                }
                """);

        assertEquals(1, result.entities().size());
        JpaEntity entity = result.entities().getFirst();
        assertEquals("com.example.dominio.Cliente", entity.type());
        assertEquals("clientes", entity.effectiveTable());
        assertTrue(entity.idField().isPresent());
        assertEquals("id", entity.idField().get().name());
        assertEquals("nome_completo", entity.fieldNamed("nomeCompleto").orElseThrow().column());
        assertEquals("email", entity.fieldNamed("email").orElseThrow().effectiveColumn());
    }

    @Test
    void derivesTableNameWhenTableIsAbsent() {
        JpaSourceParser.ParseResult result = parse("""
                package com.example;

                @Entity
                public class PedidoItem {
                    @Id
                    private Long id;
                }
                """);

        assertEquals("pedido_item", result.entities().getFirst().effectiveTable());
    }

    @Test
    void readsRelationsAndTheirTargets() {
        JpaSourceParser.ParseResult result = parse("""
                package com.example;

                @Entity
                public class Pedido {
                    @Id
                    private Long id;

                    @ManyToOne
                    private Cliente cliente;

                    @OneToMany(mappedBy = "pedido")
                    private List<PedidoItem> itens;
                }
                """);

        JpaEntity entity = result.entities().getFirst();
        JpaField cliente = entity.fieldNamed("cliente").orElseThrow();
        assertEquals(JpaField.Relation.MANY_TO_ONE, cliente.relation());
        assertEquals("Cliente", cliente.targetEntity());

        JpaField itens = entity.fieldNamed("itens").orElseThrow();
        assertEquals(JpaField.Relation.ONE_TO_MANY, itens.relation());
        assertEquals("PedidoItem", itens.targetEntity());
        assertTrue(itens.relation().isCollection());
    }

    @Test
    void marksMappedSuperclassAsNotPersistent() {
        JpaSourceParser.ParseResult result = parse("""
                package com.example;

                @MappedSuperclass
                public class Base {
                    @Id
                    private Long id;
                }
                """);

        JpaEntity entity = result.entities().getFirst();
        assertTrue(entity.mappedSuperclass());
        assertFalse(entity.persistent());
    }

    @Test
    void readsRepositoryEntityAndIdFromGenerics() {
        JpaSourceParser.ParseResult result = parse("""
                package com.example.repositorio;

                import org.springframework.data.jpa.repository.JpaRepository;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {

                    List<Cliente> findByNomeCompleto(String nome);

                    long countByAtivoIsTrue();
                }
                """);

        assertEquals(1, result.repositories().size());
        JpaRepositoryInfo repository = result.repositories().getFirst();
        assertEquals("Cliente", repository.entityType());
        assertEquals("Long", repository.idType());
        assertTrue(repository.bound());
        assertEquals(2, repository.methods().size());
        assertTrue(repository.methods().getFirst().validatable());
    }

    @Test
    void keepsDeclaredQueryOutOfDerivedValidation() {
        JpaSourceParser.ParseResult result = parse("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {

                    @Query("select c from Cliente c where c.email = ?1")
                    Optional<Cliente> findByQualquerCoisa(String email);
                }
                """);

        JpaQueryMethod method = result.repositories().getFirst().methods().getFirst();
        assertTrue(method.hasDeclaredQuery());
        assertFalse(method.validatable());
    }

    @Test
    void readsAQueryDeclaredAsATextBlock() {
        String source = String.join("\n",
                "package com.example;",
                "public interface ClienteRepository extends JpaRepository<Cliente, Long> {",
                "    @Query(\"\"\"",
                "        select count(c)",
                "        from Cliente c",
                "        where c.email = :email",
                "        \"\"\")",
                "    long findByNomeInexistente(@Param(\"email\") String email);",
                "}");

        JpaQueryMethod method = parse(source).repositories().getFirst().methods().getFirst();

        assertTrue(method.hasDeclaredQuery());
        assertTrue(method.jpql().contains("select count(c)"));
        assertEquals(List.of("email"), method.parameters());
        assertFalse(method.validatable());
        assertTrue(method.validatableQuery());
    }

    @Test
    void annotationPresenceSuppressesDerivedValidationWhenQueryUsesAConstant() {
        JpaQueryMethod method = parse("""
                package com.example;

                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query(CONSULTA_CLIENTES)
                    List<Cliente> findByPropriedadeInexistente();
                }
                """).repositories().getFirst().methods().getFirst();

        assertTrue(method.hasDeclaredQuery());
        assertTrue(method.jpql().isBlank());
        assertFalse(method.validatable());
        assertFalse(method.validatableQuery());
    }

    @Test
    void readsNamedTextBlockValueWithoutConfusingQueryEqualsWithAnnotationAttributes() {
        String source = String.join("\n",
                "package com.example;",
                "public interface ClienteRepository extends JpaRepository<Cliente, Long> {",
                "    @Query(value = \"\"\"",
                "        select c from Cliente c",
                "        where c.email = :email",
                "        \"\"\", nativeQuery = false)",
                "    List<Cliente> buscar(@Param(\"email\") String email);",
                "}");

        JpaQueryMethod method = parse(source).repositories().getFirst().methods().getFirst();

        assertTrue(method.jpql().contains("where c.email = :email"));
        assertFalse(method.jpql().contains("\"\"\""));
        assertTrue(method.validatableQuery());
    }

    @Test
    void joinsAQueryBuiltFromStringLiterals() {
        JpaQueryMethod method = parse("""
                package com.example;
                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c from Cliente c "
                            + "where c.email = :email")
                    List<Cliente> buscar(@Param("email") String email);
                }
                """).repositories().getFirst().methods().getFirst();

        assertEquals("select c from Cliente c where c.email = :email", method.jpql());
        assertTrue(method.validatableQuery());
    }

    @Test
    void keepsCommasInsideAnUnnamedQueryWhenOtherAttributesFollow() {
        JpaQueryMethod method = parse("""
                package com.example;
                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c.id, c.email from Cliente c", nativeQuery = false)
                    List<Cliente> buscar();
                }
                """).repositories().getFirst().methods().getFirst();

        assertEquals("select c.id, c.email from Cliente c", method.jpql());
        assertTrue(method.validatableQuery());
    }

    @Test
    void doesNotPartiallyAnalyzeAQueryContainingAConstant() {
        JpaQueryMethod method = parse("""
                package com.example;
                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select c from Cliente c " + ACTIVE_FILTER)
                    List<Cliente> buscar();
                }
                """).repositories().getFirst().methods().getFirst();

        assertTrue(method.hasDeclaredQuery());
        assertTrue(method.jpql().isBlank());
        assertFalse(method.validatableQuery());
    }

    @Test
    void skipsSemanticValidationForSpelQueries() {
        JpaQueryMethod method = parse("""
                package com.example;
                public interface ClienteRepository extends JpaRepository<Cliente, Long> {
                    @Query("select e from #{#entityName} e")
                    List<Cliente> buscar();
                }
                """).repositories().getFirst().methods().getFirst();

        assertFalse(method.validatableQuery());
    }

    @Test
    void ignoresInterfacesThatAreNotRepositories() {
        JpaSourceParser.ParseResult result = parse("""
                package com.example;

                public interface ServicoDeEmail {
                    void enviar(String destino);
                }
                """);

        assertTrue(result.repositories().isEmpty());
    }

    @Test
    void ignoresStaticFieldsOfAnEntity() {
        JpaSourceParser.ParseResult result = parse("""
                package com.example;

                @Entity
                public class Cliente {
                    private static final long serialVersionUID = 1L;

                    @Id
                    private Long id;
                }
                """);

        assertEquals(1, result.entities().getFirst().fields().size());
    }

    @Test
    void detectsExplicitNoArgConstructor() {
        JpaSourceParser.ParseResult result = parse("""
                package com.example;

                @Entity
                public class Cliente {
                    @Id
                    private Long id;

                    public Cliente(Long id) {
                        this.id = id;
                    }
                }
                """);

        assertFalse(result.entities().getFirst().hasNoArgConstructor());
    }

    private static JpaSourceParser.ParseResult parse(String source) {
        return JpaSourceParser.parse(FILE, source);
    }
}
