package dtm.ide.swingdesigner.form;

import dtm.ide.swingdesigner.runtime.SnapshotNode;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormReaderTest {

    @Test
    void componentsAreReadFromFieldsAndLocalsInExecutionOrder() {
        FormModel model = FormFixtures.tela();

        assertEquals(List.of("<init>", "initComponents"), model.buildMethods());
        assertEquals(List.of("topo", "titulo", "seletor"),
                model.children(FormReader.CONTENT_ID).stream().map(FormComponent::id).toList());
        assertEquals(List.of("rotulo", "salvar"),
                model.children("topo").stream().map(FormComponent::id).toList());
        FormComponent rotulo = model.component("rotulo").orElseThrow();
        assertEquals(FormComponent.Kind.LOCAL, rotulo.kind());
        assertEquals("javax.swing.JLabel", rotulo.className());
        assertFalse(rotulo.locked());
        assertEquals("demo.Tela$Seletor", model.component("seletor").orElseThrow().className());
    }

    @Test
    void propertiesLayoutsAndAttachCallsKeepTheirSourceSpans() {
        FormModel model = FormFixtures.tela();
        FormComponent salvar = model.component("salvar").orElseThrow();
        FormCall enabled = salvar.property("setEnabled").orElseThrow();

        assertEquals("true", enabled.argumentsSpan().text(model.source()));
        assertEquals("salvar.setEnabled(true);", enabled.statement().text(model.source()));
        assertEquals("topo.add(salvar)", salvar.attach().invocation().text(model.source()));
        assertEquals("new java.awt.FlowLayout()",
                model.component("topo").orElseThrow().layout().argumentsSpan().text(model.source()));
        assertEquals("new BorderLayout()",
                model.component(FormReader.CONTENT_ID).orElseThrow().layout().argumentText());
        assertEquals(List.of("topo", "BorderLayout.NORTH"),
                model.component("topo").orElseThrow().attach().arguments());
        assertTrue(model.root().property("setTitle").isPresent());
    }

    @Test
    void listenersAreRecognizedInEveryStyle() {
        FormModel model = FormFixtures.tela();
        FormComponent salvar = model.component("salvar").orElseThrow();

        FormListener action = salvar.listeners("addActionListener").getFirst();
        assertEquals(FormListener.Style.METHOD_REF, action.style());
        assertEquals("salvarActionPerformed", action.reference());
        FormListener mouse = salvar.listeners("addMouseListener").getFirst();
        assertEquals(FormListener.Style.ANONYMOUS, mouse.style());
        assertEquals("MouseAdapter", mouse.anonymousType());
        assertEquals("salvarMouseClicked", mouse.method("mouseClicked").orElseThrow().delegate());
        FormListener lambda = model.component("titulo").orElseThrow().listeners("addPropertyChangeListener")
                .getFirst();
        assertEquals(FormListener.Style.LAMBDA, lambda.style());
        assertEquals("repaint", lambda.reference());
    }

    @Test
    void unrecognizedCreationsAreLocked() {
        FormModel model = FormFixtures.tela();

        FormComponent campo = model.component("campo").orElseThrow();
        assertTrue(campo.locked());
        assertTrue(campo.lockReason().contains("bloco"), campo.lockReason());
        FormComponent titulo = model.component("titulo").orElseThrow();
        assertFalse(titulo.locked());
        assertNull(titulo.creationMethod());
    }

    @Test
    void snapshotNodesAreLinkedByFieldRoleAndStructure() {
        FormModel model = FormFixtures.tela();
        SnapshotNode rotulo = node("0/0/0/0", "javax.swing.JLabel", null, null);
        SnapshotNode salvar = node("0/0/0/1", "javax.swing.JButton", "salvar", null);
        SnapshotNode topo = node("0/0/0", "javax.swing.JPanel", "topo", null, rotulo, salvar);
        SnapshotNode content = node("0/0", "javax.swing.JPanel", null, "contentPane", topo);
        SnapshotNode root = node("0", "demo.Tela", null, null, content);

        FormLinks links = FormLinks.link(root, model);

        assertEquals("this", links.componentOf("0").orElseThrow());
        assertEquals(FormReader.CONTENT_ID, links.componentOf("0/0").orElseThrow());
        assertEquals("topo", links.componentOf("0/0/0").orElseThrow());
        assertEquals("rotulo", links.componentOf("0/0/0/0").orElseThrow());
        assertEquals("0/0/0/1", links.nodeOf("salvar").orElseThrow());
    }

    @Test
    void classesThatAreNotFoundProduceNoModel() {
        assertTrue(FormReader.read(FormFixtures.TELA,
                FormReader.Options.of("demo.Outra", true, FormFixtures::drawable)).isEmpty());
        assertTrue(FormReader.read("class {", FormReader.Options.of("demo.Tela", true, FormFixtures::drawable))
                .isEmpty());
    }

    private static SnapshotNode node(String id, String className, String field, String role,
                                     SnapshotNode... children) {
        return new SnapshotNode(id, className, field, null, new Rectangle(0, 0, 10, 10), true, List.of(children),
                field != null, role, -1, null, null, new Dimension());
    }
}
