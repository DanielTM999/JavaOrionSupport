package dtm.ide.lsp.api;

public interface DebugAdapterSupport {

    boolean isDebugAdapterAvailable();

    boolean prepareDebugAdapter();

    int startDebugSession();
}
