package dtm.ide.adapter;

import dtm.ide.api.extension.menu.IdeMenuBarBuilder;
import dtm.ide.api.extension.menu.IdeMenuBuilder;
import dtm.ide.api.project.editor.IdeEditorContext;
import dtm.ide.build.BuildSystem;
import dtm.ide.project.JavaProjectConventions;
import dtm.ide.project.JavaProjectDescriptor;
import dtm.ide.ui.JavaIcons;
import dtm.stools.component.menu.bar.tree.MenuNode;

import static dtm.ide.adapter.AdapterText.text;

public final class MenuContributions {

    private final AdapterHost host;

    public MenuContributions(AdapterHost host) {
        this.host = host;
    }

    public void contributeEditorMenu(IdeMenuBuilder menu, IdeEditorContext editorContext) {
        if (menu == null || editorContext == null) return;
        if (JavaProjectConventions.isBuildFile(editorContext.filePath())) {
            contributeBuildFileEditorMenu(menu);
            return;
        }
        if (!JavaProjectConventions.isJava(editorContext.filePath())) return;
        boolean enabled = host.navigationSupport().isNavigationAvailable(editorContext.filePath());
        boolean debugPaused = host.debug().isDebugPaused();
        String debugExpression = DebugSupport.selectedDebugExpression(editorContext);
        menu.separator()
                .submenu(text("menu.navigate", "Navigate"), JavaIcons.search(JavaIcons.SMALL),
                        navigate -> navigate
                        .item(text("menu.definition", "Go to Definition"), enabled,
                                event -> host.navigationSupport().onGoToDeclaration(editorContext))
                        .item(text("menu.implementation", "Go to Implementation"), enabled,
                                event -> host.navigationSupport().onGoToImplementation(editorContext))
                        .item(text("menu.usages", "Find Usages"), enabled,
                                event -> host.navigationSupport().onFindUsages(editorContext)))
                .separator()
                .item(text("generate.title", "Generate..."), enabled,
                        event -> host.sourceActions().showGenerateActions(editorContext))
                .item(text("generate.override", "Override Methods..."), enabled,
                        event -> host.sourceActions().showOverrideMethods(editorContext, false))
                .item(text("generate.implement", "Implement Methods..."), enabled,
                        event -> host.sourceActions().showOverrideMethods(editorContext, true))
                .separator()
                .item(text("debug.evaluate", "Evaluate Expression..."), debugPaused,
                        event -> host.debug().showEvaluateDialog(editorContext, 0))
                .item(text("debug.addWatch", "Add Watch"), debugPaused
                                && debugExpression != null,
                        event -> host.debug().addDebugWatch(debugExpression));
    }

    private void contributeBuildFileEditorMenu(IdeMenuBuilder menu) {
        JavaProjectDescriptor current = host.descriptor();
        if (current == null || !current.kind().hasBuildTool()) return;
        menu.separator()
                .item(text("tree.sync", "Sincronizar projeto"),
                        JavaIcons.sync(JavaIcons.SMALL), event -> host.syncProject())
                .item(text("tree.reload", "Recarregar projeto"),
                        JavaIcons.refresh(JavaIcons.SMALL), event -> host.clearCaches())
                .item(text("menu.buildTools", "Build Tools"),
                        JavaIcons.buildTool(current, JavaIcons.SMALL), event -> host.openBuildTools());
    }

    public void contributeMenuBar(IdeMenuBarBuilder menu) {
        menu.submenu("javaBuildMenu", text("menu.build", "Build"), build -> build
                .item("javaCompile", text("menu.compile", "Compilar"),
                        event -> host.runBuild(BuildSystem.BuildAction.COMPILE,
                                text("menu.compile", "Compilar"), null))
                .item("javaRebuild", text("menu.rebuild", "Recompilar tudo"),
                        event -> host.runBuild(BuildSystem.BuildAction.REBUILD,
                                text("menu.rebuild", "Recompilar tudo"), null))
                .item("javaClean", text("menu.clean", "Limpar"),
                        event -> host.runBuild(BuildSystem.BuildAction.CLEAN,
                                text("menu.clean", "Limpar"), null))
                .separator()
                .item("javaTest", text("menu.test", "Testar"),
                        event -> host.runBuild(BuildSystem.BuildAction.TEST,
                                text("menu.test", "Testar"), null))
                .item("javaPackage", text("menu.package", "Empacotar"),
                        event -> host.runBuild(BuildSystem.BuildAction.PACKAGE,
                                text("menu.package", "Empacotar"), null))
                .item("javaInstall", text("menu.install", "Instalar no repositorio local"),
                        event -> host.runBuild(BuildSystem.BuildAction.INSTALL,
                                text("menu.install", "Instalar no repositorio local"), null))
                .separator()
                .add(MenuNode.item("javaSyncProject", text("menu.sync", "Sincronizar projeto"))
                        .icon(JavaIcons.sync(JavaIcons.SMALL))
                        .tooltip(text("menu.sync.tip",
                                "Reler o pom ou o build.gradle e atualizar o classpath"))
                        .onClick(event -> host.syncProject()))
                .add(MenuNode.item("javaSyncWithDisk",
                                text("menu.syncDisk", "Ressincronizar com o disco"))
                        .icon(JavaIcons.refresh(JavaIcons.SMALL))
                        .tooltip(text("menu.syncDisk.tip",
                                "Reler as mudancas feitas fora do editor e reanalisar os arquivos abertos"))
                        .onClick(event -> host.problemsSupport().syncWithDisk()))
                .add(MenuNode.item("javaReanalyzeDiagnostics",
                                text("menu.reanalyzeDiagnostics", "Limpar e rediagnosticar"))
                        .icon(JavaIcons.refresh(JavaIcons.SMALL))
                        .tooltip(text("menu.reanalyzeDiagnostics.tip",
                                "Descartar os diagnosticos atuais e reiniciar a analise Java"))
                        .onClick(event -> host.problemsSupport().reanalyzeDiagnostics()))
                .add(MenuNode.item("javaRestartLanguageServer",
                                text("menu.restartLsp", "Reiniciar Java Language Server"))
                        .icon(JavaIcons.refresh(JavaIcons.SMALL))
                        .tooltip(text("menu.restartLsp.tip",
                                "Encerrar o JDT LS e inicia-lo novamente, mesmo apos falhas repetidas"))
                        .onClick(event -> host.restartLanguageServer()))
                .add(MenuNode.item("javaProjectStructure",
                                text("menu.projectStructure", "Estrutura do projeto..."))
                        .icon(JavaIcons.module(JavaIcons.SMALL))
                        .shortcut("control alt shift S")
                        .tooltip(text("menu.projectStructure.tip",
                                "SDK, nivel de linguagem, pastas de codigo, modulos e bibliotecas"))
                        .onClick(event -> host.openProjectStructure()))
                .add(MenuNode.item("javaBuildJdkManager", text("menu.jdkManager", "Gerenciar JDKs"))
                        .icon(JavaIcons.java(JavaIcons.SMALL))
                        .tooltip(text("menu.jdkManager.tip",
                                "Ver as JDKs instaladas, baixar novas e escolher a do projeto"))
                        .onClick(event -> host.openJdkManager())));

        menu.into("code")
                .add(MenuNode.item("javaGenerate", text("generate.title", "Generate..."))
                        .shortcut("alt INSERT")
                        .onClick(event -> host.sourceActions().showGenerateActions(host.activeJavaEditor())))
                .add(MenuNode.item("javaOverrideMethods",
                                text("generate.override", "Override Methods..."))
                        .shortcut("control INSERT")
                        .onClick(event -> host.sourceActions().showOverrideMethods(host.activeJavaEditor(), false)))
                .add(MenuNode.item("javaImplementMethods",
                                text("generate.implement", "Implement Methods..."))
                        .shortcut("control I")
                        .onClick(event -> host.sourceActions().showOverrideMethods(host.activeJavaEditor(), true)))
                .add(MenuNode.item("javaEvaluateExpression",
                                text("debug.evaluate", "Evaluate Expression..."))
                        .shortcut("alt F8")
                        .onClick(event -> host.debug().showEvaluateDialog(host.activeJavaEditor(), 0)))
                .add(MenuNode.separator());

        menu.into("window")
                .add(MenuNode.item(CenterTabIds.JDK_TAB_ID, text("menu.jdkManager", "Gerenciar JDKs"))
                        .icon(JavaIcons.java(JavaIcons.SMALL))
                        .tooltip(text("menu.jdkManager.tip",
                                "Ver as JDKs instaladas, baixar novas e escolher a do projeto"))
                        .onClick(event -> host.openJdkManager()))
                .add(MenuNode.item(CenterTabIds.STRUCTURE_TAB_ID,
                                text("menu.projectStructure", "Estrutura do projeto..."))
                        .icon(JavaIcons.module(JavaIcons.SMALL))
                        .tooltip(text("menu.projectStructure.tip",
                                "SDK, nivel de linguagem, pastas de codigo, modulos e bibliotecas"))
                        .onClick(event -> host.openProjectStructure()))
                .add(MenuNode.item(CenterTabIds.DEPENDENCIES_TAB_ID,
                                text("menu.dependencies", "Gerenciar dependencias"))
                        .icon(JavaIcons.dependency(JavaIcons.SMALL))
                        .tooltip(text("menu.dependencies.tip",
                                "Buscar no Maven Central, adicionar, atualizar e remover dependencias"))
                        .onClick(event -> host.openDependencyManager()))
                .add(MenuNode.item("javaProblems", text("menu.problems", "Problemas"))
                        .icon(JavaIcons.error(JavaIcons.SMALL))
                        .tooltip(text("menu.problems.tip",
                                "Erros e avisos do Java, Maven e Gradle"))
                        .onClick(event -> host.problemsSupport().openProblemsPanel()))
                .add(MenuNode.item("javaTestExplorer", text("menu.tests", "Testes"))
                        .icon(JavaIcons.test(JavaIcons.SMALL))
                        .tooltip(text("menu.tests.tip",
                                "Ver e executar os testes JUnit do projeto"))
                        .onClick(event -> host.openTestExplorer()))
                .add(MenuNode.item("javaTodo", text("menu.todo", "TODO"))
                        .icon(JavaIcons.todo(JavaIcons.SMALL))
                        .tooltip(text("menu.todo.tip",
                                "TODO, FIXME e demais marcadores do projeto"))
                        .onClick(event -> host.todoSupport().openPanel()))
                .add(MenuNode.item("javaBuildTools", text("menu.buildTools", "Build Tools"))
                        .icon(JavaIcons.buildTool(host.descriptor(), JavaIcons.SMALL))
                        .tooltip(text("menu.buildTools.tip", "Projetos, tasks e dependencias Maven/Gradle"))
                        .onClick(event -> host.openBuildTools()))
                .add(MenuNode.item("javaSpringExplorer", text("menu.spring", "Spring"))
                        .icon(JavaIcons.spring(JavaIcons.SMALL))
                        .tooltip(text("menu.spring.tip",
                                "Beans, endpoints e o estado da aplicacao em execucao"))
                        .onClick(event -> host.openSpringExplorer()));
    }
}
