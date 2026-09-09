package dtm.ide.spring;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaTypeGraphTest {

    private static final Path ROOT = Path.of("/projeto");

    @Test
    void resolvesATransitiveSupertypeChain() {
        SpringIndexSnapshot snapshot = snapshot(
                file("Envio.java", """
                        package com.example;

                        public interface Envio {
                        }
                        """),
                file("BaseEnvio.java", """
                        package com.example;

                        public abstract class BaseEnvio implements Envio {
                        }
                        """),
                file("EnvioRapido.java", """
                        package com.example;

                        @Service
                        public class EnvioRapido extends BaseEnvio {
                        }
                        """));

        List<SpringBean> providers = snapshot.beansProviding("Envio");

        assertEquals(1, providers.size());
        assertEquals("EnvioRapido", providers.getFirst().simpleName());
    }

    @Test
    void keepsWorkingForADirectSupertype() {
        SpringIndexSnapshot snapshot = snapshot(
                file("Envio.java", """
                        package com.example;

                        public interface Envio {
                        }
                        """),
                file("EnvioRapido.java", """
                        package com.example;

                        @Service
                        public class EnvioRapido implements Envio {
                        }
                        """));

        assertEquals(1, snapshot.beansProviding("Envio").size());
    }

    @Test
    void indexesEveryTypeAndNotOnlyTheAnnotatedOnes() {
        SpringIndexSnapshot snapshot = snapshot(
                file("Envio.java", """
                        package com.example;

                        public interface Envio {
                        }
                        """));

        assertEquals(1, snapshot.types().size());
        assertEquals(JavaType.Kind.INTERFACE, snapshot.types().getFirst().kind());
        assertTrue(snapshot.beans().isEmpty());
    }

    @Test
    void recognisesAnAnnotationDeclaration() {
        SpringIndexSnapshot snapshot = snapshot(
                file("Rapido.java", """
                        package com.example;

                        @Qualifier
                        public @interface Rapido {
                        }
                        """));

        assertEquals(JavaType.Kind.ANNOTATION, snapshot.types().getFirst().kind());
        assertTrue(snapshot.types().getFirst().isAnnotation());
    }

    @Test
    void readsTheImportsOfTheFile() {
        SpringIndexSnapshot snapshot = snapshot(
                file("EnvioRapido.java", """
                        package com.example.impl;

                        import com.example.api.Envio;
                        import java.util.List;

                        public class EnvioRapido implements Envio {
                        }
                        """));

        List<String> imports = snapshot.types().getFirst().imports();

        assertTrue(imports.contains("com.example.api.Envio"));
        assertTrue(imports.contains("java.util.List"));
    }

    @Test
    void disambiguatesSameSimpleNameUsingImports() {
        SpringIndexSnapshot snapshot = snapshot(
                file("api/Envio.java", """
                        package com.example.api;

                        public interface Envio {
                        }
                        """),
                file("legado/Envio.java", """
                        package com.example.legado;

                        public interface Envio {
                        }
                        """),
                file("EnvioRapido.java", """
                        package com.example.impl;

                        import com.example.api.Envio;

                        @Service
                        public class EnvioRapido implements Envio {
                        }
                        """));

        JavaTypeGraph graph = snapshot.typeGraph();
        JavaType impl = graph.byQualifiedName("com.example.impl.EnvioRapido").orElseThrow();

        assertTrue(graph.isAssignable(impl, "com.example.api.Envio", impl));
        assertFalse(graph.isAssignable(impl, "com.example.legado.Envio", impl));
    }

    @Test
    void doesNotLoopOnACircularHierarchy() {
        SpringIndexSnapshot snapshot = snapshot(
                file("A.java", """
                        package com.example;

                        public class A extends B {
                        }
                        """),
                file("B.java", """
                        package com.example;

                        public class B extends A {
                        }
                        """));

        JavaTypeGraph graph = snapshot.typeGraph();
        JavaType a = graph.byQualifiedName("com.example.A").orElseThrow();

        assertFalse(graph.isAssignable(a, "Envio", a));
    }

    private record Fixture(Path file, String source) {
    }

    private static Fixture file(String name, String source) {
        return new Fixture(ROOT.resolve(name), source);
    }

    private static SpringIndexSnapshot snapshot(Fixture... fixtures) {
        SpringIndexSnapshot snapshot = SpringIndexSnapshot.empty(ROOT);
        for (Fixture fixture : fixtures) {
            snapshot = snapshot.replacingFile(fixture.file(),
                    SpringSourceParser.parse(fixture.file(), fixture.source()));
        }
        return snapshot;
    }
}
