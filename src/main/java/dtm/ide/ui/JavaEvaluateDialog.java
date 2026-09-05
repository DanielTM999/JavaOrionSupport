package dtm.ide.ui;

import dtm.ide.debug.JavaDebugSnapshot;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.concurrent.CompletableFuture;

public final class JavaEvaluateDialog extends JDialog {

    @FunctionalInterface
    public interface Evaluator {
        JavaDebugSnapshot.Variable evaluate(String expression) throws Exception;
    }

    private final JTextField expression = new JTextField();
    private final JButton evaluate = new JButton("Evaluate");
    private final JButton addWatch = new JButton("Add Watch");
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private final JavaDebugValueTree result = new JavaDebugValueTree();
    private final Evaluator evaluator;
    private final java.util.function.Consumer<String> watchConsumer;

    public JavaEvaluateDialog(Window owner, String initialExpression, Evaluator evaluator,
                              JavaDebugValueTree.ChildrenProvider childrenProvider,
                              java.util.function.Consumer<String> watchConsumer) {
        super(owner, "Evaluate Expression");
        this.evaluator = evaluator;
        this.watchConsumer = watchConsumer == null ? value -> { } : watchConsumer;
        result.bindChildrenProvider(childrenProvider);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setModal(false);
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBackground(JavaDebugTheme.panel());
        root.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel top = new JPanel(new BorderLayout(8, 6));
        top.setOpaque(false);
        expression.setText(initialExpression == null ? "" : initialExpression);
        expression.setFont(JavaDebugTheme.mono().deriveFont(12f));
        expression.setBackground(JavaDebugTheme.content());
        expression.setForeground(JavaDebugTheme.text());
        expression.setCaretColor(JavaDebugTheme.text());
        expression.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JavaDebugTheme.border()),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        JLabel expressionLabel = new JLabel("Expression");
        expressionLabel.setForeground(JavaDebugTheme.text());
        expressionLabel.setFont(JavaDebugTheme.ui().deriveFont(Font.BOLD, 12f));
        top.add(expressionLabel, BorderLayout.NORTH);
        top.add(expression, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        styleButton(addWatch, false);
        styleButton(evaluate, true);
        actions.add(addWatch);
        actions.add(evaluate);
        top.add(actions, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);
        root.add(result, BorderLayout.CENTER);
        status.setForeground(JavaDebugTheme.muted());
        status.setFont(JavaDebugTheme.ui().deriveFont(11f));
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setPreferredSize(new Dimension(120, 12));
        JPanel statusLine = new JPanel(new BorderLayout(8, 0));
        statusLine.setOpaque(false);
        statusLine.add(status, BorderLayout.CENTER);
        statusLine.add(progress, BorderLayout.EAST);
        root.add(statusLine, BorderLayout.SOUTH);
        setContentPane(root);
        result.setPreferredSize(new Dimension(620, 320));
        evaluate.addActionListener(event -> evaluate());
        expression.addActionListener(event -> evaluate());
        addWatch.addActionListener(event -> {
            String value = expression.getText().trim();
            if (!value.isEmpty()) {
                watchConsumer.accept(value);
                status.setText("Added to Watches");
            }
        });
        setSize(680, 440);
        setMinimumSize(new Dimension(560, 360));
        setLocationRelativeTo(owner);
    }

    public void open() {
        setVisible(true);
        SwingUtilities.invokeLater(() -> {
            expression.requestFocusInWindow();
            expression.selectAll();
            if (!expression.getText().isBlank()) {
                evaluate();
            }
        });
    }

    private void evaluate() {
        String value = expression.getText().trim();
        if (value.isEmpty()) {
            return;
        }
        evaluate.setEnabled(false);
        addWatch.setEnabled(false);
        progress.setVisible(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        status.setText("Evaluating...");
        CompletableFuture.supplyAsync(() -> {
            try {
                return new Evaluation(evaluator.evaluate(value), null);
            } catch (Exception error) {
                return new Evaluation(null, error);
            }
        }).thenAccept(answer -> SwingUtilities.invokeLater(() -> {
            evaluate.setEnabled(true);
            addWatch.setEnabled(true);
            progress.setVisible(false);
            setCursor(Cursor.getDefaultCursor());
            if (answer.error != null) {
                result.clear();
                status.setText(answer.error.getMessage() == null ? "Evaluation failed"
                        : answer.error.getMessage());
            } else {
                result.setValue(answer.value);
                status.setText(answer.value == null || answer.value.type().isBlank()
                        ? " " : answer.value.type());
            }
        }));
    }

    private static void styleButton(JButton button, boolean accent) {
        button.setFocusable(false);
        button.setFont(JavaDebugTheme.ui().deriveFont(Font.BOLD, 12f));
        button.setForeground(accent ? JavaDebugTheme.accent() : JavaDebugTheme.text());
        button.setBackground(JavaDebugTheme.header());
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent
                        ? JavaDebugTheme.accent() : JavaDebugTheme.border()),
                BorderFactory.createEmptyBorder(5, 12, 5, 12)));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private record Evaluation(JavaDebugSnapshot.Variable value, Exception error) {
    }
}
