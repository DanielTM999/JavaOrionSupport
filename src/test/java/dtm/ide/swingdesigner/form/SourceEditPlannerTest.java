package dtm.ide.swingdesigner.form;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceEditPlannerTest {

    @Test
    void existingPropertiesHaveOnlyTheirArgumentsReplaced() {
        SourceEditPlanner.Result result = SourceEditPlanner.setProperty(FormFixtures.tela(), "salvar",
                "setEnabled", "false", Set.of());

        assertTrue(result.text().contains("        salvar.setEnabled(false);\n"), result.text());
        assertEquals(FormFixtures.TELA.length() + 1, result.text().length());
        assertEquals('f', result.text().charAt(result.focus()));
        FormFixtures.assertCompiles("Tela", result.text());
    }

    @Test
    void newPropertiesAreInsertedAfterTheLastStatementOfTheComponent() {
        FormModel model = FormFixtures.tela();
        String expression = JavaValueCodec.encode(com.fasterxml.jackson.databind.node.TextNode.valueOf("Ok \"x\""),
                "java.lang.String", false, name -> name);
        SourceEditPlanner.Result result = SourceEditPlanner.setProperty(model, "salvar", "setText", expression,
                Set.of());

        String text = result.text();
        assertTrue(text.contains("        topo.add(salvar);\n        salvar.setText(\"Ok \\\"x\\\"\");\n"), text);
        FormFixtures.assertCompiles("Tela", text);
    }

    @Test
    void rootPropertiesAndResetsWork() {
        SourceEditPlanner.Result root = SourceEditPlanner.setProperty(FormFixtures.tela(), FormModel.ROOT,
                "setResizable", "false", Set.of());
        assertTrue(root.text().contains("        setTitle(\"Cadastro\");\n        setResizable(false);\n"),
                root.text());
        FormFixtures.assertCompiles("Tela", root.text());

        SourceEditPlanner.Result reset = SourceEditPlanner.resetProperty(FormFixtures.tela(), "salvar",
                "setEnabled");
        assertFalse(reset.text().contains("setEnabled"));
        assertTrue(reset.text().contains("        salvar = new JButton(\"Salvar\");\n"
                + "        salvar.addActionListener"), reset.text());
        FormFixtures.assertCompiles("Tela", reset.text());
    }

    @Test
    void lockedComponentsAreRejected() {
        SourceEditPlanner.Rejected rejected = assertThrows(SourceEditPlanner.Rejected.class,
                () -> SourceEditPlanner.setProperty(FormFixtures.tela(), "campo", "setText", "\"x\"", Set.of()));
        assertTrue(rejected.getMessage().contains("Somente leitura"));
    }

    @Test
    void newComponentsBecomeFieldsAddedAtTheRequestedIndex() {
        SourceEditPlanner.Result result = SourceEditPlanner.addComponent(FormFixtures.tela(),
                new SourceEditPlanner.NewComponent("topo", "javax.swing.JCheckBox", "ativo", "\"Ativo\"",
                        List.of(Map.entry("setSelected", "true")), "add(ativo)", 1), Set.of());

        String text = result.text();
        assertTrue(text.contains("    private JCheckBox ativo;\n"), text);
        int rotulo = text.indexOf("topo.add(rotulo);");
        int ativo = text.indexOf("topo.add(ativo);");
        int salvar = text.indexOf("topo.add(salvar);");
        assertTrue(rotulo < ativo && ativo < salvar, text);
        assertTrue(text.contains("        ativo = new JCheckBox(\"Ativo\");\n        ativo.setSelected(true);\n"
                + "        topo.add(ativo);\n        topo.add(salvar);"), text);
        assertTrue(text.startsWith("ativo", result.focus()), text.substring(result.focus()));
        FormFixtures.assertCompiles("Tela", text);
    }

    @Test
    void componentsAddedToTheContentPaneUseItsReference() {
        SourceEditPlanner.Result result = SourceEditPlanner.addComponent(FormFixtures.tela(),
                new SourceEditPlanner.NewComponent(FormReader.CONTENT_ID, "javax.swing.JTable", "tabela", "",
                        List.of(), "add(tabela, BorderLayout.EAST)", -1), Set.of());

        assertTrue(result.text().contains("getContentPane().add(tabela, BorderLayout.EAST);"), result.text());
        assertTrue(result.text().contains("import javax.swing.JTable;") || result.text().contains("javax.swing.*"));
        FormFixtures.assertCompiles("Tela", result.text());
    }

    @Test
    void classesWithoutBuildCodeGetAnInitMethod() {
        SourceEditPlanner.Result result = SourceEditPlanner.addComponent(FormFixtures.vazio(),
                new SourceEditPlanner.NewComponent(FormModel.ROOT, "javax.swing.JButton", "ok", "\"Ok\"",
                        List.of(), "add(ok)", -1), Set.of());

        String text = result.text();
        assertTrue(text.contains("import javax.swing.JButton;"), text);
        assertTrue(text.contains("public Vazio() {"), text);
        assertTrue(text.contains("initComponents();"), text);
        assertTrue(text.contains("ok = new JButton(\"Ok\");"), text);
        FormFixtures.assertCompiles("Vazio", text);
    }

    @Test
    void removingAContainerRemovesItsSubtreeAndKeepsHandlers() {
        SourceEditPlanner.Result result = SourceEditPlanner.removeComponent(FormFixtures.tela(), "topo");

        String text = result.text();
        assertFalse(text.contains("topo"), text);
        assertFalse(text.contains("rotulo"), text);
        assertFalse(text.contains("salvar ="), text);
        assertTrue(text.contains("private void salvarActionPerformed"), text);
        assertTrue(result.notes().stream().anyMatch(note -> note.contains("salvarActionPerformed")),
                result.notes()::toString);
        FormFixtures.assertCompiles("Tela", text);
    }

    @Test
    void movingAComponentRewritesOnlyItsAttachStatement() {
        SourceEditPlanner.Result result = SourceEditPlanner.moveComponent(FormFixtures.tela(), "salvar",
                FormReader.CONTENT_ID, -1, "add(salvar, BorderLayout.WEST)", Set.of());

        String text = result.text();
        assertFalse(text.contains("topo.add(salvar);"), text);
        assertTrue(text.contains("getContentPane().add(salvar, BorderLayout.WEST);"), text);
        FormFixtures.assertCompiles("Tela", text);

        SourceEditPlanner.Result reorder = SourceEditPlanner.moveComponent(FormFixtures.tela(), "salvar", "topo",
                0, "add(salvar)", Set.of());
        assertTrue(reorder.text().indexOf("topo.add(salvar);") < reorder.text().indexOf("topo.add(rotulo);"),
                reorder.text());
        FormFixtures.assertCompiles("Tela", reorder.text());
    }

    @Test
    void layoutsAndConstraintsAreRewritten() {
        SourceEditPlanner.Result layout = SourceEditPlanner.setLayout(FormFixtures.tela(), "topo",
                "new GridLayout(1, 2)", Map.of(), Set.of("java.awt.GridLayout"));
        assertTrue(layout.text().contains("topo.setLayout(new GridLayout(1, 2));"), layout.text());
        assertTrue(layout.text().contains("import java.awt.GridLayout;"), layout.text());
        FormFixtures.assertCompiles("Tela", layout.text());

        SourceEditPlanner.Result constraints = SourceEditPlanner.setConstraints(FormFixtures.tela(), "titulo",
                "BorderLayout.SOUTH", Set.of());
        assertTrue(constraints.text().contains("add(titulo, BorderLayout.SOUTH);"), constraints.text());
        FormFixtures.assertCompiles("Tela", constraints.text());

        SourceEditPlanner.Result inserted = SourceEditPlanner.setLayout(FormFixtures.tela(), "seletor",
                "new BorderLayout()", Map.of(), Set.of());
        assertTrue(inserted.text().contains("seletor.setLayout(new BorderLayout());"), inserted.text());
        FormFixtures.assertCompiles("Tela", inserted.text());
    }

    @Test
    void handlersJoinAnExistingAdapter() {
        EventSpec spec = new EventSpec("addMouseListener", "java.awt.event.MouseListener", "mouseEntered", false,
                "java.awt.event.MouseAdapter", List.of(sig("mouseEntered", "java.awt.event.MouseEvent")));
        SourceEditPlanner.Result result = SourceEditPlanner.addHandler(FormFixtures.tela(), "salvar", spec,
                "salvarMouseEntered");

        String text = result.text();
        assertEquals(1, count(text, "addMouseListener"), text);
        assertTrue(text.contains("public void mouseEntered(MouseEvent e) {\n"
                + "                salvarMouseEntered(e);\n"), text);
        assertTrue(text.contains("    private void salvarMouseEntered(MouseEvent e) {\n        \n    }\n}"), text);
        assertEquals("        \n", text.substring(result.focus() - 8, result.focus() + 1));
        FormFixtures.assertCompiles("Tela", text);
    }

    @Test
    void listenersWithoutAdapterImplementEveryMethod() {
        EventSpec spec = new EventSpec("addKeyListener", "java.awt.event.KeyListener", "keyPressed", false, null,
                List.of(sig("keyTyped", "java.awt.event.KeyEvent"), sig("keyPressed", "java.awt.event.KeyEvent"),
                        sig("keyReleased", "java.awt.event.KeyEvent")));
        SourceEditPlanner.Result result = SourceEditPlanner.addHandler(FormFixtures.tela(), "topo", spec,
                "topoKeyPressed");

        String text = result.text();
        assertTrue(text.contains("topo.addKeyListener(new KeyListener() {"), text);
        assertTrue(text.contains("import java.awt.event.KeyListener;"), text);
        assertTrue(text.contains("topoKeyPressed(e);"), text);
        FormFixtures.assertCompiles("Tela", text);
    }

    @Test
    void libraryListenersMirrorTheirSignature() {
        EventSpec spec = new EventSpec("addEscolhaListener", "demo.Tela$EscolhaListener", "escolheu", true, null,
                List.of(new EventSpec.MethodSig("escolheu", List.of("java.lang.String", "int"), "boolean")));
        SourceEditPlanner.Result result = SourceEditPlanner.addHandler(FormFixtures.tela(), "seletor", spec,
                "seletorEscolheu");

        String text = result.text();
        assertTrue(text.contains("seletor.addEscolhaListener(this::seletorEscolheu);"), text);
        assertTrue(text.contains("private boolean seletorEscolheu(String arg0, int arg1) {\n"
                + "        return false;\n"), text);
        FormFixtures.assertCompiles("Tela", text);
    }

    @Test
    void handlersCanBeRemovedWithTheirMethods() {
        EventSpec spec = new EventSpec("addActionListener", "java.awt.event.ActionListener", "actionPerformed",
                true, null, List.of(sig("actionPerformed", "java.awt.event.ActionEvent")));
        SourceEditPlanner.Result result = SourceEditPlanner.removeHandler(FormFixtures.tela(), "salvar", spec, true);

        assertFalse(result.text().contains("salvarActionPerformed"), result.text());
        FormFixtures.assertCompiles("Tela", result.text().replace("import java.awt.event.ActionEvent;\n", ""));

        EventSpec mouse = new EventSpec("addMouseListener", "java.awt.event.MouseListener", "mouseClicked", false,
                "java.awt.event.MouseAdapter", List.of(sig("mouseClicked", "java.awt.event.MouseEvent")));
        SourceEditPlanner.Result removed = SourceEditPlanner.removeHandler(FormFixtures.tela(), "salvar", mouse,
                false);
        assertFalse(removed.text().contains("addMouseListener"), removed.text());
        assertTrue(removed.text().contains("private void salvarMouseClicked"), removed.text());
        FormFixtures.assertCompiles("Tela", removed.text());
    }

    @Test
    void valuesAreEncodedAsJavaExpressions() {
        com.fasterxml.jackson.databind.node.JsonNodeFactory nodes =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        java.util.function.UnaryOperator<String> simple = name -> name.substring(name.lastIndexOf('.') + 1);

        assertEquals("Color.RED", JavaValueCodec.encode(nodes.textNode("#ff0000"), "java.awt.Color", false, simple));
        assertEquals("new Color(1, 2, 3)", JavaValueCodec.encode(nodes.textNode("#010203"), "java.awt.Color", false,
                simple));
        assertEquals("SwingConstants.LEFT", JavaValueCodec.encode(nodes.textNode("javax.swing.SwingConstants.LEFT"),
                "int", false, simple));
        assertEquals("new Font(\"Dialog\", Font.BOLD, 14)", JavaValueCodec.encode(nodes.objectNode()
                .put("name", "Dialog").put("style", 1).put("size", 14), "java.awt.Font", false, simple));
        assertEquals("new Dimension(10, 20)", JavaValueCodec.encode(nodes.objectNode().put("width", 10)
                .put("height", 20), "java.awt.Dimension", false, simple));
        assertEquals("2.5f", JavaValueCodec.encode(nodes.numberNode(2.5), "float", false, simple));
        assertEquals("TabPlacement.TOP", JavaValueCodec.encode(nodes.textNode("TOP"), "demo.TabPlacement", true,
                simple));
        assertEquals("new ImageIcon(getClass().getResource(\"/img/a.png\"))",
                JavaValueCodec.encode(nodes.textNode("img/a.png"), "javax.swing.Icon", false, simple));
        assertEquals("\"linha\\nnova\"", JavaValueCodec.encode(nodes.textNode("linha\nnova"), "java.lang.String",
                false, simple));
    }

    private static EventSpec.MethodSig sig(String name, String event) {
        return new EventSpec.MethodSig(name, List.of(event), "void");
    }

    private static int count(String text, String token) {
        int count = 0;
        for (int index = text.indexOf(token); index >= 0; index = text.indexOf(token, index + 1)) {
            count++;
        }
        return count;
    }
}
