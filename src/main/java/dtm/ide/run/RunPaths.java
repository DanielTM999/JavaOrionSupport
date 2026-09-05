package dtm.ide.run;

import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectDescriptor;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolucao de caminhos das configuracoes de execucao.
 *
 * <p>Um caminho relativo digitado pelo usuario e sempre resolvido contra a raiz do modulo
 * selecionado ou, na falta dele, contra a raiz do projeto -- nunca contra o diretorio de
 * trabalho da IDE.</p>
 */
public final class RunPaths {

    private RunPaths() {
    }

    /** Base usada para resolver caminhos relativos: o modulo ou a raiz do projeto. */
    public static Optional<Path> base(JavaModule module, JavaProjectDescriptor descriptor) {
        if (module != null) {
            return Optional.of(module.root());
        }
        return Optional.ofNullable(descriptor).map(JavaProjectDescriptor::root);
    }

    /**
     * Resolve {@code raw} contra o modulo/projeto.
     *
     * @return vazio quando {@code raw} esta em branco ou nao e um caminho valido.
     */
    public static Optional<Path> resolve(String raw, JavaModule module,
                                         JavaProjectDescriptor descriptor) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Path candidate = Path.of(raw.trim());
            if (candidate.isAbsolute()) {
                return Optional.of(candidate.normalize());
            }
            return base(module, descriptor)
                    .map(root -> root.resolve(candidate).normalize());
        } catch (InvalidPathException error) {
            return Optional.empty();
        }
    }

    /** {@code true} quando {@code raw} nao e sintaticamente um caminho. */
    public static boolean isMalformed(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            Path.of(raw.trim());
            return false;
        } catch (InvalidPathException error) {
            return true;
        }
    }

    /** Converte um caminho absoluto em relativo ao modulo/projeto, quando possivel. */
    public static String relativize(Path path, JavaModule module,
                                    JavaProjectDescriptor descriptor) {
        if (path == null) {
            return "";
        }
        Path normalized = path.toAbsolutePath().normalize();
        return base(module, descriptor)
                .filter(normalized::startsWith)
                .map(root -> root.relativize(normalized).toString().replace('\\', '/'))
                .filter(relative -> !relative.isBlank())
                .orElseGet(normalized::toString);
    }
}
