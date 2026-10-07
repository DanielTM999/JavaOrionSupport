package dtm.ide.lsp;

import dtm.ide.lsp.api.LanguageServerState;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
final class LspDocumentSync {
    enum ResyncMode {
        REOPEN,
        TOUCH
    }

    private static final long DOCUMENT_RECOVERY_COOLDOWN_MS = 5_000;
    private static final int MAX_REOPEN_DOCUMENTS = 30;

    interface Host {
        LspJsonRpcClient client();
        LanguageServerState state();
        ServerCapabilities capabilities();
        Path projectRoot();
        void documentContentChanged(Path filePath, String uri, String previous,
                                    String content, boolean deferCodeLensRefresh);
        void forgetRefreshTicket(String uri);
        void forgetSymbols(String uri);
        void discardCodeLenses(String uri);
        void forgetCompletion(String uri);
        void invalidateWorkspaceNavigation();
        void cancelInFlightForUri(String uri);
        void removeDiagnostics(Path path);
        void codeLensRefresh(Path path);
        void status(String message);
        void resynchronizeOpenDocuments(ResyncMode mode, boolean clearDiagnostics, String statusMessage);
    }

    private final JdtDocumentStore documents;
    private final ExecutorService executor;
    private final Host host;
    private final Set<String> openedDuringImport = ConcurrentHashMap.newKeySet();
    private final AtomicLong lastDocumentRecovery = new AtomicLong();

    LspDocumentSync(JdtDocumentStore documents, ExecutorService executor, Host host) {
        this.documents = documents;
        this.executor = executor;
        this.host = host;
    }

    void reset() {
        openedDuringImport.clear();
    }

    void openDocument(Path filePath, String text) {
        if (filePath == null) {
            return;
        }
        String uri = LspConversions.toUri(filePath);
        String content = text == null ? "" : text;
        String previous = documents.put(uri, content);
        if (!content.equals(previous)) {
            host.documentContentChanged(filePath, uri, previous, content, false);
        }
        if (!canSyncDocuments()) {
            return;
        }
        if (documents.markSynced(uri)) {
            sendDidOpen(uri, content);
        } else if (!content.equals(previous)) {
            sendDidChange(uri, previous, content);
        }
    }

    void changeDocument(Path filePath, String text) {
        if (filePath == null) {
            return;
        }
        String uri = LspConversions.toUri(filePath);
        String content = text == null ? "" : text;
        if (content.equals(documents.content(uri))) {
            return;
        }
        String previous = documents.put(uri, content);
        host.documentContentChanged(filePath, uri, previous, content, true);

        if (!canSyncDocuments()) {
            return;
        }
        if (documents.markSynced(uri)) {
            sendDidOpen(uri, content);
            return;
        }
        sendDidChange(uri, previous, content);
    }

    void closeDocument(Path filePath) {
        if (filePath == null) {
            return;
        }
        String uri = LspConversions.toUri(filePath);
        host.forgetRefreshTicket(uri);
        boolean wasSynced = documents.unmarkSynced(uri);
        documents.remove(uri);
        host.forgetSymbols(uri);
        host.discardCodeLenses(uri);
        host.forgetCompletion(uri);
        host.invalidateWorkspaceNavigation();
        host.cancelInFlightForUri(uri);

        LspJsonRpcClient rpc = host.client();
        if (wasSynced && rpc != null && canSyncDocuments()) {
            rpc.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
        }
    }

    void saveDocument(Path filePath, String text) {
        if (filePath == null) {
            return;
        }
        changeDocument(filePath, text);
        String uri = LspConversions.toUri(filePath);
        LspJsonRpcClient rpc = host.client();
        if (rpc != null && canSyncDocuments() && documents.isSynced(uri)) {
            rpc.notify("textDocument/didSave", Map.of("textDocument", Map.of("uri", uri)));
        }
    }

    void flushOpenDocuments() {
        Path root = host.projectRoot();
        documents.forEach((uri, content) -> {
            Path path = LspConversions.toPath(uri);
            if (root != null && (path == null || !path.startsWith(root))) {
                return;
            }
            if (documents.markSynced(uri)) {
                sendDidOpen(uri, content);
            }
        });
    }

    boolean canSyncDocuments() {
        LanguageServerState current = host.state();
        LspJsonRpcClient rpc = host.client();
        return (current == LanguageServerState.INDEXING || current == LanguageServerState.READY)
                && rpc != null && !rpc.isClosed();
    }

    void recoverDocumentSynchronization() {
        long now = System.currentTimeMillis();
        long previous = lastDocumentRecovery.get();
        if (now - previous < DOCUMENT_RECOVERY_COOLDOWN_MS
                || !lastDocumentRecovery.compareAndSet(previous, now)
                || !canSyncDocuments()) {
            return;
        }
        LspJsonRpcClient rpc = host.client();
        if (rpc == null) {
            return;
        }
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS, executor)
                .execute(this::performDocumentResynchronization);
    }

    void performDocumentResynchronization() {
        log.warn("JDT LS perdeu a posicao de um documento; resincronizando {} buffer(s)",
                documents.size());
        host.resynchronizeOpenDocuments(ResyncMode.REOPEN, true, "Java: documentos resincronizados");
    }

    ResyncMode resyncMode() {
        return documents.size() > MAX_REOPEN_DOCUMENTS ? ResyncMode.TOUCH : ResyncMode.REOPEN;
    }

    void resynchronizeOpenDocuments(ResyncMode mode, boolean clearDiagnostics,
                                                         String statusMessage) {
        if (!canSyncDocuments()) {
            return;
        }
        LspJsonRpcClient rpc = host.client();
        if (rpc == null) {
            return;
        }
        documents.forEach((uri, content) -> {
            host.cancelInFlightForUri(uri);
            if (mode == ResyncMode.REOPEN) {
                if (documents.unmarkSynced(uri)) {
                    rpc.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
                }
                documents.incrementVersion(uri);
                if (documents.markSynced(uri)) {
                    sendDidOpen(uri, content);
                }
            } else if (documents.isSynced(uri)) {
                sendDidChange(uri, content, content);
            }
            host.forgetSymbols(uri);
            host.discardCodeLenses(uri);
            host.forgetCompletion(uri);
            Path path = LspConversions.toPath(uri);
            if (path == null) {
                return;
            }
            if (clearDiagnostics) {
                host.removeDiagnostics(path);
            }
            host.codeLensRefresh(path);
        });
        if (statusMessage != null) {
            host.status(statusMessage);
        }
    }

    void reopenDocumentsOpenedDuringImport() {
        LspJsonRpcClient rpc = host.client();
        if (rpc == null || openedDuringImport.isEmpty()) {
            openedDuringImport.clear();
            return;
        }
        List<String> uris = List.copyOf(openedDuringImport);
        openedDuringImport.clear();
        for (String uri : uris) {
            String content = documents.content(uri);
            if (content == null || !documents.unmarkSynced(uri)) {
                continue;
            }
            rpc.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
            documents.incrementVersion(uri);
            if (documents.markSynced(uri)) {
                sendDidOpen(uri, content);
            }
        }
        log.debug("{} documento(s) aberto(s) durante a importacao foram reabertos", uris.size());
    }

    void sendDidOpen(String uri, String content) {
        LspJsonRpcClient rpc = host.client();
        if (rpc == null) {
            return;
        }
        int version = documents.ensureVersion(uri);
        if (host.state() != LanguageServerState.READY) {
            openedDuringImport.add(uri);
        }
        rpc.notify("textDocument/didOpen", Map.of("textDocument", Map.of(
                "uri", uri,
                "languageId", "java",
                "version", version,
                "text", content)));
    }

    void sendDidChange(String uri, String previous, String content) {
        LspJsonRpcClient rpc = host.client();
        if (rpc == null) {
            return;
        }
        int version = documents.incrementVersion(uri);
        Map<String, Object> change = host.capabilities().incrementalSync()
                ? incrementalDocumentChange(previous, content)
                : Map.of("text", content);
        rpc.notify("textDocument/didChange", Map.of(
                "textDocument", Map.of("uri", uri, "version", version),
                "contentChanges", List.of(change)));
    }

    static Map<String, Object> incrementalDocumentChange(String previous, String current) {
        String before = previous == null ? "" : previous;
        String after = current == null ? "" : current;
        int prefix = 0;
        int shared = Math.min(before.length(), after.length());
        while (prefix < shared && before.charAt(prefix) == after.charAt(prefix)) {
            prefix++;
        }
        if (prefix > 0 && prefix < before.length()
                && Character.isLowSurrogate(before.charAt(prefix))
                && Character.isHighSurrogate(before.charAt(prefix - 1))) {
            prefix--;
        }
        int beforeEnd = before.length();
        int afterEnd = after.length();
        while (beforeEnd > prefix && afterEnd > prefix
                && before.charAt(beforeEnd - 1) == after.charAt(afterEnd - 1)) {
            beforeEnd--;
            afterEnd--;
        }
        Map<String, Integer> start = lspPosition(before, prefix);
        Map<String, Integer> end = lspPosition(before, beforeEnd);
        return Map.of(
                "range", Map.of("start", start, "end", end),
                "rangeLength", beforeEnd - prefix,
                "text", after.substring(prefix, afterEnd));
    }

    private static Map<String, Integer> lspPosition(String text, int offset) {
        int bounded = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int index = 0; index < bounded; index++) {
            if (text.charAt(index) == '\n') {
                line++;
                lineStart = index + 1;
            }
        }
        return Map.of("line", line, "character", bounded - lineStart);
    }

    boolean syncBeforeRequest(Path filePath, String text) {
        return syncBeforeRequest(filePath, text, false);
    }

    boolean syncBeforeRequest(Path filePath, String text, boolean authoritative) {
        if (filePath == null || text == null) {
            return filePath != null;
        }
        String uri = LspConversions.toUri(filePath);
        String current = documents.content(uri);
        if (current == null) {
            openDocument(filePath, text);
            return true;
        }
        if (!current.equals(text)) {
            if (!authoritative && documents.hasSeen(uri, text)) {
                log.debug("Requisicao descartada para {}: revisao anterior do editor", uri);
                return false;
            }
            log.debug("Sincronizando {} antes da requisicao interativa", uri);
            changeDocument(filePath, text);
        }
        return true;
    }

}
