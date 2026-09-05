package dtm.ide.test;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSelectorsTest {

    @Test
    void theAllScopeSendsNoSelector() {
        assertTrue(TestSelectors.maven(TestScope.ALL, "").isEmpty());
        assertTrue(TestSelectors.gradle(TestScope.ALL, "").isEmpty());
    }

    @Test
    void mavenUsesPathPatternsForPackages() {
        assertEquals(List.of("-Dtest=com/exemplo/servico/**", "-DfailIfNoTests=false"),
                TestSelectors.maven(TestScope.PACKAGE, "com.exemplo.servico"));
    }

    @Test
    void gradleUsesDotPatternsForPackages() {
        assertEquals(List.of("--tests", "com.exemplo.servico.*"),
                TestSelectors.gradle(TestScope.PACKAGE, "com.exemplo.servico"));
    }

    @Test
    void aClassIsSentAsIsToBothTools() {
        assertEquals(List.of("-Dtest=com.exemplo.MinhaClasseTest", "-DfailIfNoTests=false"),
                TestSelectors.maven(TestScope.CLASS, "com.exemplo.MinhaClasseTest"));
        assertEquals(List.of("--tests", "com.exemplo.MinhaClasseTest"),
                TestSelectors.gradle(TestScope.CLASS, "com.exemplo.MinhaClasseTest"));
    }

    @Test
    void methodSelectorsUseTheSeparatorEachToolExpects() {
        assertEquals(List.of("-Dtest=com.exemplo.MinhaClasseTest#deveSomar",
                        "-DfailIfNoTests=false"),
                TestSelectors.maven(TestScope.METHOD, "com.exemplo.MinhaClasseTest#deveSomar"));
        assertEquals(List.of("--tests", "com.exemplo.MinhaClasseTest.deveSomar"),
                TestSelectors.gradle(TestScope.METHOD, "com.exemplo.MinhaClasseTest#deveSomar"));
    }

    @Test
    void freePatternsAreForwardedUnchanged() {
        assertEquals(List.of("-Dtest=*IntegrationTest", "-DfailIfNoTests=false"),
                TestSelectors.maven(TestScope.PATTERN, "*IntegrationTest"));
        assertEquals(List.of("--tests", "*IntegrationTest"),
                TestSelectors.gradle(TestScope.PATTERN, "*IntegrationTest"));
    }

    @Test
    void aBlankTargetProducesNoSelector() {
        assertTrue(TestSelectors.maven(TestScope.CLASS, "  ").isEmpty());
        assertTrue(TestSelectors.gradle(TestScope.CLASS, null).isEmpty());
    }

    @Test
    void forBuildToolPicksTheRightSyntax() {
        assertEquals(TestSelectors.gradle(TestScope.CLASS, "A"),
                TestSelectors.forBuildTool(true, TestScope.CLASS, "A"));
        assertEquals(TestSelectors.maven(TestScope.CLASS, "A"),
                TestSelectors.forBuildTool(false, TestScope.CLASS, "A"));
    }

    @Test
    void scopesParseLenientlyAndFallBackToAll() {
        assertEquals(TestScope.CLASS, TestScope.parse("class"));
        assertEquals(TestScope.METHOD, TestScope.parse("METHOD"));
        assertEquals(TestScope.ALL, TestScope.parse("desconhecido"));
        assertEquals(TestScope.ALL, TestScope.parse(null));
    }

    @Test
    void onlyTheAllScopeWorksWithoutATarget() {
        assertTrue(TestScope.CLASS.requiresTarget());
        assertTrue(TestScope.PATTERN.requiresTarget());
        assertTrue(!TestScope.ALL.requiresTarget());
    }

    @Test
    void classAndMethodTargetsBecomeJavaTests() {
        List<JavaTest> classTests = TestSelectors.asTests(TestScope.CLASS, "com.exemplo.UmTest");
        assertEquals(1, classTests.size());
        assertTrue(classTests.getFirst().isClassLevel());

        List<JavaTest> methodTests = TestSelectors.asTests(TestScope.METHOD,
                "com.exemplo.UmTest#deveSomar");
        assertEquals("com.exemplo.UmTest", methodTests.getFirst().className());
        assertEquals("deveSomar", methodTests.getFirst().methodName());
    }

    @Test
    void packageAndPatternTargetsHaveNoJavaTestEquivalent() {
        assertTrue(TestSelectors.asTests(TestScope.PACKAGE, "com.exemplo").isEmpty());
        assertTrue(TestSelectors.asTests(TestScope.PATTERN, "*Test").isEmpty());
    }
}
