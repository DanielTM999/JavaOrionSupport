package dtm.ide.sdk;

import dtm.ide.api.exceptions.DisplayException;
import dtm.request_actions.http.download.core.DownloadObserver;
import dtm.request_actions.http.download.core.client.DownloadObserverStreamClient;
import dtm.request_actions.http.download.core.config.ObserverConfiguration;
import dtm.stools.component.popup.ModernDialog;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

@Slf4j
public final class SdkDownloader {

    private static String text(String key, String def) {
        return dtm.stools.i18n.I18n.getText(SdkDownloader.class, key, def);
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 1500;
    private static final long MAX_DOWNLOAD_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final String USER_AGENT = "JavaOrionSupport/1.0";

    private final DownloadObserver downloadObserver;

    public SdkDownloader(DownloadObserver downloadObserver) {
        this.downloadObserver = downloadObserver;
    }

    public void download(SdkArtifact artifact, Path root, DownloadProgressListener progressListener) {
        transfer(artifact, root, progressListener, true);
    }

    public Path downloadRaw(SdkArtifact artifact, Path root, DownloadProgressListener progressListener) {
        transfer(artifact, root, progressListener, false);
        return root.resolve(artifact.fileName());
    }

    private void transfer(SdkArtifact artifact, Path root, DownloadProgressListener progressListener,
                          boolean extract) {
        if (root == null) {
            throw error(text("error.resourceDirUnavailable",
                    "Plugin resource directory is not available."), null);
        }
        DownloadProgressListener listener =
                progressListener == null ? DownloadProgressListener.NOOP : progressListener;
        Path target = root.resolve(artifact.fileName());

        try {
            Files.createDirectories(root);
            if (Files.isRegularFile(target)) {
                if (extract) {
                    installArchive(target, root);
                }
                return;
            }
        } catch (Exception e) {
            throw error(text("error.sdkFolder", "Could not prepare the SDK folder:") + " " + root, e);
        }

        if (downloadObserver == null) {
            throw error(text("error.downloadServiceUnavailable", "Download service is not available."), null);
        }

        listener.onStart(artifact.progressId(), artifact.displayName());
        try {
            Exception lastError = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    downloadToFile(artifact, target, listener);
                    if (extract) {
                        listener.onProgress(artifact.progressId(),
                                text("progress.extracting", "Extracting files"), -1);
                        installArchive(target, root);
                    }
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw error(text("error.downloadInterrupted", "Download interrupted:")
                            + " " + artifact.fileName(), e);
                } catch (DisplayException e) {
                    throw e;
                } catch (Exception e) {
                    lastError = e;
                    deletePartial(target);
                    if (attempt < MAX_ATTEMPTS) {
                        log.warn("Falha ao baixar {} (tentativa {}/{}): {}. Tentando novamente...",
                                artifact.fileName(), attempt, MAX_ATTEMPTS, message(e));
                        listener.onProgress(artifact.progressId(),
                                artifact.displayName() + " - " + text("progress.attempt", "attempt")
                                        + " " + (attempt + 1) + "/" + MAX_ATTEMPTS, -1);
                        sleepBackoff(attempt);
                    }
                }
            }
            throw error(text("error.downloadFailed", "Failed to download") + " " + artifact.fileName()
                    + " - " + MAX_ATTEMPTS + " " + text("error.attempts", "attempts"), lastError);
        } finally {
            listener.onFinish(artifact.progressId());
        }
    }

    private void downloadToFile(SdkArtifact artifact, Path target, DownloadProgressListener listener)
            throws Exception {
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(temp);

        CompletableFuture<Path> done = new CompletableFuture<>();
        AtomicBoolean finished = new AtomicBoolean(false);
        int[] lastPercent = {-1};
        OutputStream output = Files.newOutputStream(temp,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        try {
            downloadObserver.newDownloadGetStream(
                    artifact.url(),
                    Map.of("User-Agent", USER_AGENT),
                    new DownloadObserverStreamClient() {
                        @Override
                        public void observerConfiguration(ObserverConfiguration observerConfiguration) {
                            observerConfiguration.setBufferSize(1024 * 128);
                            observerConfiguration.setTimeout(30, TimeUnit.MINUTES);
                            observerConfiguration.setReadTimeout(2, TimeUnit.MINUTES);
                            observerConfiguration.setMaxSizeDownload(MAX_DOWNLOAD_BYTES);
                        }

                        @Override
                        public void onProgress(byte[] content, long bytesRead, long expectedSize,
                                               Map<String, List<String>> headers) {
                            try {
                                output.write(content);
                                if (expectedSize > 0) {
                                    int percent = (int) Math.clamp((bytesRead * 100L) / expectedSize, 0, 99);
                                    if (percent != lastPercent[0]) {
                                        lastPercent[0] = percent;
                                        listener.onProgress(artifact.progressId(),
                                                artifact.displayName(), percent);
                                    }
                                }
                            } catch (Exception e) {
                                done.completeExceptionally(e);
                                throw new CompletionException(e);
                            }
                        }

                        @Override
                        public void onComplete(Map<String, List<String>> headers) {
                            done.complete(temp);
                        }

                        @Override
                        public void onError(Throwable exception) {
                            done.completeExceptionally(exception);
                        }

                        @Override
                        public void onDisconect() {
                            done.completeExceptionally(
                                    new IllegalStateException("Download desconectado: " + artifact.url()));
                        }
                    });

            done.get();
            output.close();
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            finished.set(true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        } finally {
            closeQuietly(output);
            if (!finished.get()) {
                Files.deleteIfExists(temp);
            }
        }
    }

    public static void installArchive(Path archive, Path targetDir) throws Exception {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            extractTarGz(archive, targetDir);
        } else if (name.endsWith(".zip") || name.endsWith(".jar")) {
            extractZip(archive, targetDir);
        } else {
            throw new IllegalStateException("Formato de arquivo nao suportado: " + archive.getFileName());
        }
        Files.deleteIfExists(archive);
        makeExecutablesRunnable(targetDir);
    }

    private static void extractTarGz(Path archive, Path targetDir) throws Exception {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (GZIPInputStream gzip = new GZIPInputStream(Files.newInputStream(archive));
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                Path output = normalizedTarget.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedTarget)) {
                    throw new IllegalStateException("Entrada tar invalida: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else if (entry.isFile()) {
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(tar, output, StandardCopyOption.REPLACE_EXISTING);
                    applyTarMode(output, entry.getMode());
                }
            }
        }
    }

    private static void extractZip(Path zip, Path targetDir) throws java.io.IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zip.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                Path output = normalizedTarget.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedTarget)) {
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Path parent = output.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    try (InputStream in = zipFile.getInputStream(entry)) {
                        Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    public static Optional<Path> singleChildDirectory(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> children = Files.list(root)) {
            List<Path> directories = children.filter(Files::isDirectory).limit(2).toList();
            return directories.size() == 1 ? Optional.of(directories.getFirst()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception e) {
            log.debug("Falha ao remover {}: {}", root, e.getMessage());
        }
    }

    private static void applyTarMode(Path file, int mode) {
        if (Platform.isWindowsHost() || mode <= 0) {
            return;
        }
        if ((mode & 0100) != 0) {
            file.toFile().setExecutable(true, false);
        }
    }

    private static void makeExecutablesRunnable(Path targetDir) {
        if (Platform.isWindowsHost()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(targetDir)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName() != null
                            && "bin".equals(path.getParent().getFileName().toString()))
                    .forEach(path -> path.toFile().setExecutable(true, false));
        } catch (Exception ignored) {
        }
    }

    private static void deletePartial(Path target) {
        try {
            Files.deleteIfExists(target);
            Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".part"));
        } catch (Exception ignored) {
        }
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_DELAY_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(OutputStream output) {
        try {
            output.close();
        } catch (Exception ignored) {
        }
    }

    private static String message(Throwable t) {
        if (t == null) {
            return "";
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    public static DisplayException error(String message, Throwable cause) {
        DisplayException exception = cause == null
                ? new DisplayException(message)
                : new DisplayException(message, cause);
        return exception
                .title(text("error.title", "Erro ao configurar a toolchain Java"))
                .type(ModernDialog.Type.ERROR)
                .draggable(true);
    }
}
