package dtm.ide.lsp;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FakeJdtLsServer {

    public static final String WIRE_LOG = "fake-jdtls-wire.log";

    private static final Pattern REQUEST =
            Pattern.compile("^\\{\"jsonrpc\":\"2\\.0\",\"id\":(\\d+|\"[^\"]*\"),\"method\":\"([^\"]+)\"");
    private static final Pattern NOTIFICATION =
            Pattern.compile("^\\{\"jsonrpc\":\"2\\.0\",\"method\":\"([^\"]+)\"");

    private static final String CAPABILITIES = "{\"capabilities\":{"
            + "\"definitionProvider\":true,\"typeDefinitionProvider\":true,\"implementationProvider\":true,"
            + "\"referencesProvider\":true,\"documentSymbolProvider\":true,\"documentHighlightProvider\":true,"
            + "\"codeLensProvider\":{\"resolveProvider\":true},\"renameProvider\":{\"prepareProvider\":true},"
            + "\"documentFormattingProvider\":true,\"documentRangeFormattingProvider\":true,"
            + "\"codeActionProvider\":{\"resolveProvider\":true},"
            + "\"signatureHelpProvider\":{\"triggerCharacters\":[\"(\",\",\"]},"
            + "\"inlayHintProvider\":true,\"semanticTokensProvider\":{\"full\":true},"
            + "\"callHierarchyProvider\":true,\"executeCommandProvider\":{\"commands\":[]},"
            + "\"workspaceSymbolProvider\":true,\"typeHierarchyProvider\":true,\"foldingRangeProvider\":true,"
            + "\"completionProvider\":{\"resolveProvider\":true,\"triggerCharacters\":[\".\",\"@\"]},"
            + "\"textDocumentSync\":{\"openClose\":true,\"change\":2,\"save\":{\"includeText\":true}}}}";

    private static final Map<String, String> RESULTS = Map.ofEntries(
            Map.entry("initialize", CAPABILITIES),
            Map.entry("shutdown", "null"),
            Map.entry("textDocument/completion",
                    "{\"isIncomplete\":false,\"items\":[{\"label\":\"length()\",\"kind\":2,"
                            + "\"sortText\":\"999\",\"insertText\":\"length()\"}]}"),
            Map.entry("completionItem/resolve", "{\"label\":\"length()\",\"kind\":2}"),
            Map.entry("textDocument/hover", "{\"contents\":{\"kind\":\"markdown\",\"value\":\"String\"}}"),
            Map.entry("textDocument/signatureHelp", "null"),
            Map.entry("textDocument/definition", "[]"),
            Map.entry("textDocument/implementation", "[]"),
            Map.entry("textDocument/typeDefinition", "[]"),
            Map.entry("textDocument/references", "[]"),
            Map.entry("textDocument/documentSymbol", "[]"),
            Map.entry("textDocument/documentHighlight", "[]"),
            Map.entry("textDocument/prepareRename", "null"),
            Map.entry("textDocument/rename", "{\"changes\":{}}"),
            Map.entry("textDocument/codeAction", "[]"),
            Map.entry("codeAction/resolve", "{\"title\":\"x\"}"),
            Map.entry("textDocument/inlayHint", "[]"),
            Map.entry("textDocument/semanticTokens/full", "{\"data\":[]}"),
            Map.entry("textDocument/codeLens", "[]"),
            Map.entry("codeLens/resolve", "null"),
            Map.entry("textDocument/formatting", "[]"),
            Map.entry("textDocument/foldingRange", "[]"),
            Map.entry("textDocument/selectionRange", "[]"),
            Map.entry("textDocument/prepareTypeHierarchy", "[]"),
            Map.entry("textDocument/prepareCallHierarchy", "[]"),
            Map.entry("workspace/symbol", "[]"),
            Map.entry("workspace/willRenameFiles", "null"),
            Map.entry("java/classFileContents", "\"class Decompiled {}\""),
            Map.entry("java/listOverridableMethods", "{\"type\":\"Demo\",\"methods\":[]}"),
            Map.entry("java/checkToStringStatus", "{\"type\":\"Demo\",\"fields\":[],\"exists\":false}"),
            Map.entry("java/checkHashCodeEqualsStatus",
                    "{\"type\":\"Demo\",\"fields\":[],\"existingMethods\":[]}"),
            Map.entry("java/checkConstructorsStatus", "{\"constructors\":[],\"fields\":[]}"),
            Map.entry("java/resolveUnimplementedAccessors", "[]"),
            Map.entry("java/checkDelegateMethodsStatus", "{\"delegateFields\":[]}"),
            Map.entry("java/getMoveDestinations", "{\"destinations\":[]}"),
            Map.entry("java/buildWorkspace", "0"));

    private static final Map<String, String> COMMAND_RESULTS = Map.of(
            "java.project.getAll", "[]",
            "java.project.getClasspaths", "{\"classpaths\":[\"lib.jar\"],\"modulepaths\":[]}");

    private static final Pattern COMMAND = Pattern.compile("\"command\":\"([^\"]+)\"");

    private FakeJdtLsServer() {
    }

    public static void main(String[] args) throws IOException {
        Path log = Path.of(WIRE_LOG).toAbsolutePath();
        Files.deleteIfExists(log);
        append(log, "{\"launch\":{\"jvm\":" + array(ManagementFactory.getRuntimeMXBean().getInputArguments())
                + ",\"args\":" + array(List.of(args)) + "}}");
        InputStream in = new BufferedInputStream(System.in);
        OutputStream out = System.out;
        String body;
        while ((body = read(in)) != null) {
            append(log, body);
            Matcher request = REQUEST.matcher(body);
            if (request.find()) {
                String method = request.group(2);
                String result = RESULTS.get(method);
                if ("workspace/executeCommand".equals(method)) {
                    Matcher command = COMMAND.matcher(body);
                    result = command.find() ? COMMAND_RESULTS.get(command.group(1)) : null;
                }
                write(out, "{\"jsonrpc\":\"2.0\",\"id\":" + request.group(1) + ",\"result\":"
                        + (result == null ? "null" : result) + "}");
                continue;
            }
            Matcher notification = NOTIFICATION.matcher(body);
            if (!notification.find()) {
                continue;
            }
            String method = notification.group(1);
            if ("initialized".equals(method)) {
                write(out, "{\"jsonrpc\":\"2.0\",\"id\":\"cfg-1\",\"method\":\"workspace/configuration\","
                        + "\"params\":{\"items\":[{\"section\":\"java.format\"},{\"section\":\"java.completion\"},"
                        + "{\"section\":\"\"}]}}");
                write(out, "{\"jsonrpc\":\"2.0\",\"method\":\"language/status\","
                        + "\"params\":{\"type\":\"ServiceReady\",\"message\":\"ServiceReady\"}}");
            } else if ("exit".equals(method)) {
                System.exit(0);
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int length = -1;
        int value;
        while ((value = in.read()) != -1) {
            header.write(value);
            String text = header.toString(StandardCharsets.US_ASCII);
            if (text.endsWith("\r\n\r\n")) {
                for (String line : text.split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    }
                }
                break;
            }
        }
        if (length < 0) {
            return null;
        }
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static synchronized void write(OutputStream out, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static void append(Path log, String line) throws IOException {
        Files.writeString(log, line.replace("\r", "").replace("\n", "") + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String array(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return json.append(']').toString();
    }
}
