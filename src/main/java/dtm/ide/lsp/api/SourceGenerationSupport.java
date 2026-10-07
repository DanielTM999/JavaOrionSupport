package dtm.ide.lsp.api;

import dtm.stools.component.panels.editor.code.api.TextEdit;

import java.nio.file.Path;
import java.util.List;

public interface SourceGenerationSupport {

    String OVERRIDE_METHODS_PROMPT = "java.action.overrideMethodsPrompt";
    String HASHCODE_EQUALS_PROMPT = "java.action.hashCodeEqualsPrompt";
    String GENERATE_TOSTRING_PROMPT = "java.action.generateToStringPrompt";
    String GENERATE_ACCESSORS_PROMPT = "java.action.generateAccessorsPrompt";
    String GENERATE_CONSTRUCTORS_PROMPT = "java.action.generateConstructorsPrompt";
    String GENERATE_DELEGATE_METHODS_PROMPT = "java.action.generateDelegateMethodsPrompt";

    record SourceItem(Object value, String label, String detail, boolean selected) {
    }

    record OverrideStatus(String type, List<SourceItem> methods) {
    }

    record FieldsStatus(String type, List<SourceItem> fields, List<String> existingMethods, boolean exists) {
    }

    record ConstructorsStatus(List<SourceItem> constructors, List<SourceItem> fields) {
    }

    record DelegateTarget(Object field, String label, List<SourceItem> methods) {
    }

    OverrideStatus overridableMethods(Path filePath, String text, int line, int col);

    List<TextEdit> generateOverridableMethods(Path filePath, String text, int line, int col,
                                              List<SourceItem> methods);

    ConstructorsStatus constructorsStatus(Path filePath, String text, int line, int col);

    List<TextEdit> generateConstructors(Path filePath, String text, int line, int col,
                                        List<SourceItem> constructors, List<SourceItem> fields);

    List<SourceItem> accessorsStatus(Path filePath, String text, int line, int col);

    List<TextEdit> generateAccessors(Path filePath, String text, int line, int col, List<SourceItem> accessors);

    FieldsStatus hashCodeEqualsStatus(Path filePath, String text, int line, int col);

    List<TextEdit> generateHashCodeEquals(Path filePath, String text, int line, int col, List<SourceItem> fields,
                                          boolean regenerate);

    FieldsStatus toStringStatus(Path filePath, String text, int line, int col);

    List<TextEdit> generateToString(Path filePath, String text, int line, int col, List<SourceItem> fields);

    List<DelegateTarget> delegateTargets(Path filePath, String text, int line, int col);

    List<TextEdit> generateDelegateMethods(Path filePath, String text, int line, int col, DelegateTarget target,
                                           List<SourceItem> methods);
}
