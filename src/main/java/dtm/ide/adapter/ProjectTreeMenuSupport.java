package dtm.ide.adapter;

import dtm.ide.api.extension.PlatformPopupBuilder;
import dtm.ide.api.extension.menu.IdeMenuBuilder;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.build.BuildSystem;
import dtm.ide.lsp.api.JavaLanguageServer;
import dtm.ide.lsp.api.SourceGenerationSupport;
import dtm.ide.lsp.api.TypeSymbol;
import dtm.ide.project.JavaFileChangeRouter;
import dtm.ide.project.JavaModule;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.project.ProjectLayout;
import dtm.ide.spring.JavaType;
import dtm.ide.spring.jpa.JpaEntity;
import dtm.ide.ui.JavaIcons;
import dtm.ide.ui.JavaSourceActionDialogs;
import dtm.ide.ui.JavaTypeCreationPanel;
import dtm.ide.ui.RepositoryCreationPanel;
import dtm.ide.wizard.JavaFileTemplates;
import dtm.stools.component.popup.ModernInputDialog;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.swing.*;

import static dtm.ide.adapter.AdapterText.text;

@Slf4j
public final class ProjectTreeMenuSupport {

    private static final String IDE_MENU_ID_NEW = "tree.new";

    private static final int IDE_NEW_MENU_INDEX = 4;

    private static final String MENU_ID_NEW_JAVA = "java.tree.new";
    private static final String MENU_ID_BUILD_MODULE = "java.tree.buildModule";
    private static final String MENU_ID_SYNC = "java.tree.sync";
    private static final String MENU_ID_ADD_DEPENDENCY = "java.tree.addDependency";
    private static final String MENU_ID_RELOAD = "java.tree.reload";
    private static final String MENU_ID_MARK_DIRECTORY = "java.tree.markDirectory";

    private final AdapterHost host;

    public ProjectTreeMenuSupport(AdapterHost host) {
        this.host = host;
    }

    public void contributeProjectTreeMenu(IdeMenuBuilder menu, List<Path> selectedPaths) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null || selectedPaths == null || selectedPaths.isEmpty()) {
            return;
        }
        Path selected = selectedPaths.getFirst();
        Path directory = Files.isDirectory(selected) ? selected : selected.getParent();
        if (directory == null) {
            return;
        }

        boolean singleDirectory = selectedPaths.size() == 1 && Files.isDirectory(selected);
        contributeNewJavaFileMenu(menu, current, directory, singleDirectory);

        int position = singleDirectory ? IDE_NEW_MENU_INDEX + 1 : Integer.MAX_VALUE;
        if (singleDirectory) {
            contributeMarkDirectoryMenu(menu, position++, directory);
        }
        JavaModule module = current.moduleOf(directory).orElse(current.rootModule());
        if (module != null && current.kind().hasBuildTool()) {
            menu.at(position++)
                    .withId(MENU_ID_BUILD_MODULE)
                    .item(text("tree.buildModule", "Compilar modulo"),
                            JavaIcons.buildTool(current, JavaIcons.SMALL),
                            event -> host.runBuild(BuildSystem.BuildAction.COMPILE,
                                    text("menu.compile", "Compilar"), module));
            menu.at(position++)
                    .withId(MENU_ID_SYNC)
                    .item(text("tree.sync", "Sincronizar projeto"),
                            JavaIcons.sync(JavaIcons.SMALL), event -> host.syncProject());
            menu.at(position++)
                    .withId(MENU_ID_ADD_DEPENDENCY)
                    .item(text("tree.addDependency", "Adicionar dependencia..."),
                            JavaIcons.dependency(JavaIcons.SMALL),
                            event -> host.openDependencyManager());
        }
        menu.at(position)
                .withId(MENU_ID_RELOAD)
                .item(text("tree.reload", "Recarregar projeto"),
                        JavaIcons.refresh(JavaIcons.SMALL), event -> host.clearCaches());
    }

    private void contributeMarkDirectoryMenu(IdeMenuBuilder menu, int position, Path directory) {
        Path root = host.projectRoot();
        if (root == null) {
            return;
        }
        Path folder = JavaProjectConventions.normalize(directory);
        Path normalizedRoot = JavaProjectConventions.normalize(root);
        if (!folder.startsWith(normalizedRoot) || folder.equals(normalizedRoot)) {
            return;
        }
        ProjectLayout.Role marked = ProjectLayout.of(root).roleOf(folder);
        menu.at(position).withId(MENU_ID_MARK_DIRECTORY)
                .submenu(text("tree.markDirectory", "Marcar diretorio como"),
                        JavaIcons.folder(JavaIcons.SMALL), target -> {
                            for (ProjectLayout.Role role : ProjectLayout.Role.values()) {
                                target.item(markDirectoryLabel(role), markDirectoryIcon(role),
                                        role != marked,
                                        event -> markDirectoryAs(folder, role));
                            }
                            target.separator();
                            target.item(text("tree.markDirectory.clear",
                                            "Usar o padrao do projeto"),
                                    JavaIcons.sync(JavaIcons.SMALL), marked != null,
                                    event -> markDirectoryAs(folder, null));
                        });
    }

    private String markDirectoryLabel(ProjectLayout.Role role) {
        return switch (role) {
            case SOURCE -> text("tree.markDirectory.source", "Codigo-fonte");
            case TEST -> text("tree.markDirectory.test", "Codigo de teste");
            case RESOURCE -> text("tree.markDirectory.resource", "Recursos");
            case TEST_RESOURCE -> text("tree.markDirectory.testResource", "Recursos de teste");
            case EXCLUDED -> text("tree.markDirectory.excluded", "Excluida");
        };
    }

    private static Icon markDirectoryIcon(ProjectLayout.Role role) {
        return switch (role) {
            case SOURCE, RESOURCE -> JavaIcons.java(JavaIcons.SMALL);
            case TEST, TEST_RESOURCE -> JavaIcons.test(JavaIcons.SMALL);
            case EXCLUDED -> JavaIcons.stop(JavaIcons.SMALL);
        };
    }

    private void markDirectoryAs(Path folder, ProjectLayout.Role role) {
        Path root = host.projectRoot();
        if (root == null || folder == null) {
            return;
        }
        host.background().submit(() -> {
            try {
                ProjectLayout layout = ProjectLayout.of(root);
                layout.setRole(folder, role);
                layout.save();
                host.syncProject();
                host.reloadStructurePanel();
                host.requestProjectTreeViewRefresh();
                String label = role == null
                        ? text("tree.markDirectory.clear", "Usar o padrao do projeto")
                        : markDirectoryLabel(role);
                host.setStatusBarText("Java: " + root.relativize(folder) + " - " + label);
            } catch (Exception error) {
                log.warn("Falha ao marcar o diretorio {}", folder, error);
                host.setStatusBarText("Java: " + text("tree.markDirectory.failed",
                        "Falha ao marcar o diretorio") + " - " + AdapterFailures.rootMessage(error));
            }
        });
    }

    private void contributeNewJavaFileMenu(IdeMenuBuilder menu, JavaProjectDescriptor current,
                                           Path directory, boolean ideOffersNewMenu) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<JavaFileTemplates.Kind> kinds = new ArrayList<>();
        for (JavaFileTemplates.Kind kind : JavaFileTemplates.Kind.values()) {
            if (!kind.isSpring() || current.spring()) {
                kinds.add(kind);
            }
        }
        if (ideOffersNewMenu) {
            menu.into(IDE_MENU_ID_NEW, target -> {
                target.separator();
                addTemplateItems(target, kinds, directory);
            });
            return;
        }
        menu.withId(MENU_ID_NEW_JAVA).submenu(text("tree.new", "Novo Java"),
                JavaIcons.java(JavaIcons.SMALL), target -> addTemplateItems(target, kinds, directory));
    }

    private void addTemplateItems(IdeMenuBuilder target, List<JavaFileTemplates.Kind> kinds,
                                  Path directory) {
        if (host.descriptor() != null && host.descriptor().kind().hasBuildTool()) {
            target.item(text("new.module", "Modulo..."), JavaIcons.module(JavaIcons.SMALL), event -> createJavaModule(directory));
        }
        for (JavaFileTemplates.Kind kind : kinds) {
            target.item(kind.displayName(), iconFor(kind),
                    event -> createJavaFile(kind, directory));
        }
    }

    private void createJavaModule(Path directory) {
        JavaProjectDescriptor project = host.descriptor();
        if (project == null) return;
        String name = host.createModernInputDialogBuilder().title("Novo modulo")
                .message("Nome do modulo em " + directory + ":").show();
        if (name == null || name.isBlank()) return;
        host.background().submit(() -> {
            try {
                var plan = dtm.ide.wizard.JavaModuleScaffolder.prepare(project, directory, name.trim());
                String openParent = host.readCurrentText(plan.parentBuild());
                if (openParent != null && !Objects.equals(openParent.replace("\r\n", "\n"),
                        plan.previousParent() == null ? null : plan.previousParent().replace("\r\n", "\n")))
                    throw new IllegalStateException("Salve as alteracoes do build pai antes de criar o modulo.");
                boolean accepted = !plan.convertsPackaging() || UiThreads.onUi(() -> JavaSourceActionDialogs.confirm(
                        host.createModernComponentDialogBuilder(Boolean.class), "Converter projeto em agregador",
                        "O POM pai sera convertido para packaging pom. Seus fontes deixarao de ser compilados neste modulo. Continuar?", "Converter e criar"));
                if (!accepted) return;
                dtm.ide.wizard.JavaModuleScaffolder.create(plan);
                SwingUtilities.invokeLater(() -> {
                    IdeEditorContext editor = host.editorContextFor(plan.parentBuild());
                    if (editor != null) editor.setText(plan.updatedParent());
                    host.requestProjectTreeViewRefresh();
                    host.onBuildFileChanged(plan.parentBuild());
                    host.openAt(plan.files().keySet().iterator().next(), 0, 0);
                });
            } catch (Exception error) {
                log.warn("Falha ao criar modulo", error);
                host.setStatusBarText("Java: " + error.getMessage());
            }
        });
    }

    private static Icon iconFor(JavaFileTemplates.Kind kind) {
        if (kind.isSpring()) {
            return JavaIcons.spring(JavaIcons.SMALL);
        }
        return kind == JavaFileTemplates.Kind.TEST
                ? JavaIcons.test(JavaIcons.SMALL)
                : JavaIcons.java(JavaIcons.SMALL);
    }

    private void createJavaFile(JavaFileTemplates.Kind kind, Path directory) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null) {
            return;
        }
        if (kind == JavaFileTemplates.Kind.REPOSITORY) {
            createRepository(directory, current);
            return;
        }
        if (JavaFileTemplates.acceptsInterfaces(kind)) {
            createTypeWithHeritage(kind, directory, current);
            return;
        }
        ModernInputDialog.ModernInputDialogBuilder dialog = host.createModernInputDialogBuilder();
        if (dialog == null) {
            return;
        }
        String typed = dialog
                .title(text("dialog.newType.title", "Novo") + " " + kind.displayName())
                .message(text("dialog.newType.message", "Nome do tipo:"))
                .show();
        if (typed == null || typed.isBlank()) {
            return;
        }
        JavaModule module = current.moduleOf(directory).orElse(current.rootModule());
        Path file = directory.resolve(JavaFileTemplates.fileNameOf(typed));
        writeCreatedFile(file, JavaFileTemplates.render(kind,
                JavaFileTemplates.packageOf(directory, module), typed));
    }

    private void createTypeWithHeritage(JavaFileTemplates.Kind kind, Path directory,
                                        JavaProjectDescriptor current) {
        JavaModule module = current.moduleOf(directory).orElse(current.rootModule());
        String packageName = JavaFileTemplates.packageOf(directory, module);
        JavaTypeCreationPanel panel = new JavaTypeCreationPanel(
                JavaFileTemplates.acceptsSuperclass(kind),
                kind == JavaFileTemplates.Kind.INTERFACE
                        ? text("dialog.newType.extends", "Estende")
                        : text("dialog.newType.implements", "Implementa"),
                this::searchTypeCandidates, host.background(), choice -> {
            Path file = directory.resolve(JavaFileTemplates.fileNameOf(choice.name()));
            if (openIfExists(file)) {
                return;
            }
            String skeleton = JavaFileTemplates.render(kind, packageName, choice.name(),
                    choice.superclass(), choice.interfaces());
            boolean hasSuperclass = JavaFileTemplates.acceptsSuperclass(kind)
                    && !choice.superclass().isBlank();
            if (!hasSuperclass && choice.interfaces().isEmpty()) {
                writeCreatedFile(file, skeleton);
                return;
            }
            host.setStatusBarText(text("status.newType.generating", "Java: gerando os metodos herdados..."));
            host.background().submit(() -> {
                String generated = withInheritedMembers(file, skeleton, hasSuperclass);
                SwingUtilities.invokeLater(() -> {
                    writeCreatedFile(file, generated == null ? skeleton : generated);
                    if (generated == null) {
                        host.setStatusBarText(text("status.newType.notGenerated",
                                "Java: tipo criado sem os metodos herdados (JDT LS indisponivel)"));
                    }
                });
            });
        });
        host.showPopup(PlatformPopupBuilder.builder()
                .component(panel)
                .title(text("dialog.newType.title", "Novo") + " " + kind.displayName())
                .size(540, 250)
                .modalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                .onLoad(component -> panel.focusName())
                .build());
    }

    private List<JavaTypeCreationPanel.TypeCandidate> searchTypeCandidates(String query) {
        String term = query == null ? "" : query.trim();
        if (term.isEmpty()) {
            return List.of();
        }
        String lower = term.toLowerCase(Locale.ROOT);
        Map<String, JavaTypeCreationPanel.TypeCandidate> found = new LinkedHashMap<>();
        host.spring().index().snapshot().types().stream()
                .filter(type -> type.kind() == JavaType.Kind.CLASS
                        || type.kind() == JavaType.Kind.INTERFACE)
                .filter(type -> type.simpleName().toLowerCase(Locale.ROOT).contains(lower))
                .sorted(Comparator.comparing((JavaType type) ->
                                !type.simpleName().toLowerCase(Locale.ROOT).startsWith(lower))
                        .thenComparing(JavaType::simpleName))
                .forEach(type -> found.putIfAbsent(type.qualifiedName(),
                        new JavaTypeCreationPanel.TypeCandidate(type.qualifiedName(),
                                type.kind() == JavaType.Kind.INTERFACE)));
        JavaLanguageServer lsp = host.languageServer();
        if (lsp != null && lsp.isInteractive()) {
            for (TypeSymbol symbol : lsp.workspaceTypes(term)) {
                found.putIfAbsent(symbol.qualifiedName(), new JavaTypeCreationPanel.TypeCandidate(
                        symbol.qualifiedName(), symbol.isInterface()));
            }
        }
        return found.values().stream().limit(80).toList();
    }

    private String withInheritedMembers(Path file, String skeleton, boolean hasSuperclass) {
        JavaLanguageServer lsp = host.interactiveServerFor(file);
        SourceGenerationSupport generator = lsp == null ? null : lsp.extension(SourceGenerationSupport.class);
        if (generator == null) {
            return null;
        }
        try {
            String source = skeleton;
            if (hasSuperclass) {
                int line = JavaFileTemplates.closingBraceLine(source);
                SourceGenerationSupport.ConstructorsStatus constructors =
                        generator.constructorsStatus(file, source, line, 0);
                boolean needsConstructor = !constructors.constructors().isEmpty()
                        && constructors.constructors().stream()
                        .noneMatch(item -> item.label().endsWith("()"));
                if (needsConstructor) {
                    var edits = generator.generateConstructors(file, source, line, 0,
                            constructors.constructors(), List.of());
                    if (edits != null && !edits.isEmpty()) {
                        source = lsp.applyTextEdits(source, edits);
                    }
                }
            }
            int line = JavaFileTemplates.closingBraceLine(source);
            SourceGenerationSupport.OverrideStatus status = generator.overridableMethods(file, source, line, 0);
            if (status.type().isBlank()) {
                return null;
            }
            List<SourceGenerationSupport.SourceItem> abstracts = status.methods().stream()
                    .filter(SourceGenerationSupport.SourceItem::selected).toList();
            if (!abstracts.isEmpty()) {
                var edits = generator.generateOverridableMethods(file, source, line, 0, abstracts);
                if (edits != null && !edits.isEmpty()) {
                    source = lsp.applyTextEdits(source, edits);
                }
            }
            return source;
        } catch (RuntimeException error) {
            log.warn("Falha ao gerar os metodos herdados de {}", file, error);
            return null;
        } finally {
            lsp.closeDocument(file);
        }
    }

    private boolean openIfExists(Path file) {
        if (!Files.exists(file)) {
            return false;
        }
        host.setStatusBarText(text("status.fileExists", "Java: o arquivo ja existe") + " - "
                + file.getFileName());
        host.requestOpenFile(file);
        return true;
    }

    private void writeCreatedFile(Path file, String source) {
        if (openIfExists(file)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
            JavaFileChangeRouter router = host.fileChangeRouter();
            if (router != null) {
                router.acceptCreated(file);
            }
            host.requestProjectTreeRevealCreated(file);
            host.requestOpenFile(file);
        } catch (Exception error) {
            log.warn("Falha ao criar {}", file, error);
            host.setStatusBarText(text("status.createFailed", "Java: falha ao criar o arquivo") + " - "
                    + AdapterFailures.rootMessage(error));
        }
    }

    private void createRepository(Path directory, JavaProjectDescriptor current) {
        JavaModule module = current.moduleOf(directory).orElse(current.rootModule());
        java.util.function.Function<JpaEntity, JavaModule> moduleOf = entity -> entity.file() == null
                ? null : current.moduleOf(entity.file()).orElse(null);
        List<JpaEntity> entities = host.spring().index().snapshot().entities().stream()
                .filter(JpaEntity::persistent)
                .sorted(Comparator.comparing((JpaEntity entity) -> module != null
                                && !Objects.equals(module, moduleOf.apply(entity)))
                        .thenComparing(JpaEntity::type))
                .toList();
        RepositoryCreationPanel panel = new RepositoryCreationPanel(entities, entity -> {
            JavaModule owner = moduleOf.apply(entity);
            return owner == null || owner.equals(module) ? "" : owner.name();
        }, choice -> writeCreatedFile(directory.resolve(JavaFileTemplates.fileNameOf(choice.name())),
                JavaFileTemplates.renderRepository(JavaFileTemplates.packageOf(directory, module),
                        choice.name(), choice.entity().type(), choice.idType())));
        host.showPopup(PlatformPopupBuilder.builder()
                .component(panel)
                .title(text("dialog.repository.title", "Novo repository"))
                .size(500, 320)
                .modalityType(java.awt.Dialog.ModalityType.APPLICATION_MODAL)
                .onLoad(component -> panel.focusName())
                .build());
    }
}
