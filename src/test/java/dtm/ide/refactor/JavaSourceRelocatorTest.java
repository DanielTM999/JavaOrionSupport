package dtm.ide.refactor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSourceRelocatorTest {

    @Test
    void replacesThePackageDeclarationKeepingTheRest() {
        String source = "package demo.a;\n\npublic class Foo {}\n";

        assertEquals("package demo.c;\n\npublic class Foo {}\n", JavaSourceRelocator.withPackage(source, "demo.c"));
    }

    @Test
    void insertsAPackageWhenTheFileHadNoneKeepingCrlfAndBom() {
        String source = "﻿public class Foo {}\r\n";

        assertEquals("﻿package demo;\r\n\r\npublic class Foo {}\r\n",
                JavaSourceRelocator.withPackage(source, "demo"));
    }

    @Test
    void removesThePackageWhenMovingToTheDefaultPackage() {
        String source = "package demo.a;\n\npublic class Foo {}\n";

        assertEquals("public class Foo {}\n", JavaSourceRelocator.withPackage(source, ""));
    }

    @Test
    void ignoresPackageWordsInsideComments() {
        String source = "// package fake;\npackage demo.a;\nclass Foo {}\n";

        assertEquals("// package fake;\npackage demo.b;\nclass Foo {}\n", JavaSourceRelocator.withPackage(source, "demo.b"));
    }

    @Test
    void renamesTheTypeButNotStringsCommentsOrQualifiedMembers() {
        String source = """
                public class Foo {
                    // Foo is great
                    private final String name = "Foo";
                    public Foo() {}
                    static Foo create() { return new Foo(); }
                    Object other = other.Foo;
                    FooBar notThis;
                }
                """;

        String renamed = JavaSourceRelocator.renameType(source, "Foo", "Bar");

        assertTrue(renamed.contains("public class Bar {"), renamed);
        assertTrue(renamed.contains("// Foo is great"), renamed);
        assertTrue(renamed.contains("\"Foo\""), renamed);
        assertTrue(renamed.contains("public Bar() {}"), renamed);
        assertTrue(renamed.contains("static Bar create() { return new Bar(); }"), renamed);
        assertTrue(renamed.contains("other.Foo"), renamed);
        assertTrue(renamed.contains("FooBar notThis;"), renamed);
    }

    @Test
    void replacesQualifiedPrefixesOnlyOnSegmentBoundaries() {
        String source = """
                package demo.a.sub;
                import demo.a.sub.inner.Deep;
                import demo.a.subway.Other;
                class X { demo.a.sub.Bar bar; String s = "demo.a.sub"; }
                """;

        String updated = JavaSourceRelocator.replaceQualifiedPrefix(source, "demo.a.sub", "demo.x.sub");

        assertTrue(updated.contains("package demo.x.sub;"), updated);
        assertTrue(updated.contains("import demo.x.sub.inner.Deep;"), updated);
        assertTrue(updated.contains("import demo.a.subway.Other;"), updated);
        assertTrue(updated.contains("demo.x.sub.Bar bar;"), updated);
        assertTrue(updated.contains("\"demo.a.sub\""), updated);
    }

    @Test
    void movedTypeImportsTheSiblingsItUsedFromTheOldPackage() {
        String source = "package demo.a;\n\npublic class Foo {\n    Sibling sibling;\n    Unused u;\n}\n";

        String moved = JavaSourceRelocator.relocateMovedType(source, "demo.c", "Foo", "Foo", "demo.a",
                List.of("Foo", "Sibling", "Lonely"));

        assertTrue(moved.startsWith("package demo.c;"), moved);
        assertTrue(moved.contains("import demo.a.Sibling;"), moved);
        assertTrue(!moved.contains("import demo.a.Lonely;"), moved);
        assertTrue(!moved.contains("import demo.a.Foo;"), moved);
    }
}
