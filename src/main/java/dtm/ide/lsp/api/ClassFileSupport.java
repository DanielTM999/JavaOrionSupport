package dtm.ide.lsp.api;

import dtm.stools.component.panels.editor.code.api.Location;
import dtm.stools.component.panels.editor.code.hover.HoverInfo;

import java.util.List;

public interface ClassFileSupport {

    boolean isClassFileUri(String uri);

    String classFileSourceName(String uri);

    String classFileTabKey(String uri);

    String classFileContents(String uri);

    List<Location> definitionsAtUri(String uri, int line, int col);

    HoverInfo hoverAtUri(String uri, int line, int col);
}
