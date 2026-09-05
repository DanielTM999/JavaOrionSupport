package dtm.ide.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaClassFileNavigationTest {

    @Test
    void recognizesOnlyJdtClassDocuments() {
        assertTrue(JavaClassFileNavigation.isClassFileUri(
                "jdt://contents/guava.jar/com/google/common/base/Preconditions.class?=demo"));
        assertFalse(JavaClassFileNavigation.isClassFileUri("file:///project/Demo.java"));
        assertFalse(JavaClassFileNavigation.isClassFileUri("not a uri"));
        assertFalse(JavaClassFileNavigation.isClassFileUri(null));
    }

    @Test
    void derivesAJavaNameForTheVirtualEditor() {
        String uri = "jdt://contents/guava.jar/com/google/common/base/Preconditions.class?=demo";

        assertEquals("Preconditions.java", JavaClassFileNavigation.sourceFileName(uri));
        assertEquals("Decompiled.java", JavaClassFileNavigation.sourceFileName("file:///Demo.java"));
    }

    @Test
    void tabIdentityIsStablePerClassButDifferentBetweenClasses() {
        String first = JavaClassFileNavigation.tabKey("jdt://contents/a/A.class");

        assertEquals(first, JavaClassFileNavigation.tabKey("jdt://contents/a/A.class"));
        assertNotEquals(first, JavaClassFileNavigation.tabKey("jdt://contents/a/B.class"));
    }
}
