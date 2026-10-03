package dtm.ide.wizard;

import dtm.ide.api.extension.wizard.ProjectWizardCallback;
import dtm.stools.component.feedback.alert.AlertPanel;
import dtm.stools.component.feedback.badge.BadgeLabel;
import dtm.stools.component.feedback.progress.ProgressBar;
import dtm.stools.component.feedback.steps.StepsPanel;
import dtm.stools.component.form.FormField;
import dtm.stools.component.form.FormValues;
import dtm.stools.component.form.ValidationResult;
import dtm.stools.component.form.Validator;
import dtm.stools.component.inputfields.duallistfield.DualListField;
import dtm.stools.component.inputfields.osfilepicker.OsFilePicker;
import dtm.stools.component.inputfields.segmentedfield.SegmentedField;
import dtm.stools.component.inputfields.selectfield.DropdownFieldListener;
import dtm.stools.component.inputfields.tagfield.TagInputField;
import dtm.stools.component.inputfields.textfield.MaskedTextField;
import dtm.stools.component.panels.card.CardPanel;
import dtm.stools.component.panels.scroll.ScrollPanel;
import dtm.stools.configs.UiTokens;
import dtm.stools.i18n.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

final class JavaProjectWizardView extends JPanel {

    private static final List<String> JAVA_VERSIONS = List.of("25", "21", "17", "11", "8");
    private static final String DEFAULT_VERSION = "1.0.0-SNAPSHOT";
    private static final int CHIP_COLUMNS = 3;

    private static final Pattern IDENTIFIER = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern ARTIFACT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern VERSION_TEXT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*");
    private static final Pattern FOLDER_NAME = Pattern.compile("[^\\\\/:*?\"<>|]+");

    private static final String STEP_PROJECT = "project";
    private static final String STEP_COORDINATES = "coordinates";
    private static final String STEP_DEPENDENCIES = "dependencies";
    private static final String STEP_REVIEW = "review";

    private final JavaTemplate template;
    private final boolean springBoot;
    private final ProjectWizardCallback callback;
    private final SpringInitializrClient initializr = new SpringInitializrClient();

    private final MaskedTextField location = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField projectName = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField descriptionField = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField groupId = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField artifactId = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField version = WizardUi.sized(new MaskedTextField());
    private final MaskedTextField packageName = WizardUi.sized(new MaskedTextField());
    private final DropdownFieldListener<String> javaVersion =
            WizardUi.sized(new DropdownFieldListener<String>());
    private final DropdownFieldListener<SpringInitializrClient.Option> bootVersion =
            WizardUi.sized(new DropdownFieldListener<SpringInitializrClient.Option>());
    private final SegmentedField<String> packaging = WizardUi.sized(new SegmentedField<String>());
    private final TagInputField modules = new TagInputField();
    private final JCheckBox appendModuleToPackage = new JCheckBox();
    private final DualListField<SpringInitializrClient.Starter> starterSelector = new DualListField<>();

    private final List<String> stepIds = new ArrayList<>();
    private final List<String> stepTitles = new ArrayList<>();
    private final Map<String, WizardFormGrid> forms = new LinkedHashMap<>();
    private final CardLayout bodyLayout = new CardLayout();
    private final JPanel body = WizardUi.transparent(bodyLayout);
    private final StepsPanel steps = new StepsPanel();
    private final JPanel reviewContent = WizardUi.transparent(new GridBagLayout());

    private final JButton backButton;
    private final JButton nextButton;
    private final JButton cancelButton;
    private final JLabel status = new JLabel(" ");
    private final ProgressBar progress = new ProgressBar().setIndeterminate(true)
            .setBarHeight(UiTokens.scale(4)).setShowLabel(false);
    private final AlertPanel alert = new AlertPanel(AlertPanel.Severity.ERROR, "", "")
            .setClosable(false).setShowIcon(true);

    private JLabel dependencyHint;
    private boolean artifactEdited;
    private boolean packageEdited;
    private boolean busy;
    private int step;

    JavaProjectWizardView(JavaTemplate template, boolean springBoot, ProjectWizardCallback callback) {
        super(new BorderLayout(0, UiTokens.space(3)));
        this.template = template;
        this.springBoot = springBoot;
        this.callback = callback;
        this.backButton = WizardUi.secondary(text("action.back", "Voltar"), null);
        this.nextButton = WizardUi.primary(text("action.next", "Avancar"), null);
        this.cancelButton = WizardUi.ghost(text("action.cancel", "Cancelar"), WizardIcons.cancel(15));

        setOpaque(false);
        putClientProperty("orion.wizard.fitViewportHeight", true);
        setBorder(BorderFactory.createEmptyBorder(UiTokens.space(5), UiTokens.space(5),
                UiTokens.space(4), UiTokens.space(5)));
        buildSteps();
        applyDefaults();

        add(headerArea(), BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(footer(), BorderLayout.SOUTH);

        wireDerivedFields();
        wireNavigation();
        showStep(0);

        if (springBoot) {
            loadInitializrMetadata();
        }
    }

    private void buildSteps() {
        addStep(STEP_PROJECT, text("step.project", "Projeto"), scrollable(projectStep()));
        if (needsCoordinates()) {
            addStep(STEP_COORDINATES, text("step.coordinates", "Coordenadas"),
                    scrollable(coordinatesStep()));
        }
        if (springBoot) {
            addStep(STEP_DEPENDENCIES, text("step.dependencies", "Dependencias"), dependenciesStep());
        }
        addStep(STEP_REVIEW, text("step.review", "Revisao"), scrollable(reviewStep()));

        steps.setSteps(List.copyOf(stepTitles));
        steps.setClickable(true);
        steps.setCircleSize(UiTokens.scale(24));
    }

    private void addStep(String id, String title, JComponent content) {
        stepIds.add(id);
        stepTitles.add(title);
        body.add(content, id);
    }

    private JComponent headerArea() {
        JPanel heading = WizardUi.transparent(new BorderLayout(0, UiTokens.space(4)));
        heading.add(header(), BorderLayout.NORTH);
        heading.add(steps, BorderLayout.SOUTH);
        return heading;
    }

    private JPanel header() {
        JPanel panel = WizardUi.transparent(new BorderLayout(UiTokens.space(4), 0));
        JLabel icon = new JLabel(WizardIcons.project(template, springBoot, UiTokens.scale(40)));
        icon.setPreferredSize(new Dimension(UiTokens.scale(46), UiTokens.scale(46)));

        JLabel title = WizardUi.title(headline(), UiTokens.scale(19));
        JLabel description = new JLabel("<html>" + subtitle() + "</html>");
        description.setForeground(UiTokens.muted());
        description.setFont(UiTokens.fontSmall());

        JPanel copy = WizardUi.transparent(new BorderLayout(0, UiTokens.space(1)));
        copy.add(title, BorderLayout.NORTH);
        copy.add(description, BorderLayout.CENTER);

        panel.add(icon, BorderLayout.WEST);
        panel.add(copy, BorderLayout.CENTER);
        panel.add(buildSystemBadge(), BorderLayout.EAST);
        return panel;
    }

    private JComponent buildSystemBadge() {
        String label = template.isMaven() ? "Maven" : template.isGradle() ? "Gradle" : "Java";
        BadgeLabel badge = new BadgeLabel(label, BadgeLabel.Tone.INFO);
        badge.setStyle(BadgeLabel.Style.SOFT).setSize(BadgeLabel.Size.MD);
        JPanel holder = WizardUi.transparent(new BorderLayout());
        holder.add(badge, BorderLayout.NORTH);
        return holder;
    }

    private String headline() {
        if (springBoot) {
            if (isSpringMultiModule()) {
                return "Spring Boot multi-modulo com Maven";
            }
            return "Spring Boot com " + (template.isGradle() ? "Gradle" : "Maven");
        }
        return template.displayName();
    }

    private String subtitle() {
        return springBoot
                ? text("header.spring", "Projeto pronto para producao gerado pelo Spring Initializr.")
                : template.description();
    }

    private JComponent projectStep() {
        WizardFormGrid form = new WizardFormGrid(3);
        forms.put(STEP_PROJECT, form);

        form.addWide(new DelegatedFormField("location", text("field.location", "Pasta"),
                        locationRow(), location)
                        .setRequired(true)
                        .setHelperText(text("helper.location", "Pasta onde a pasta do projeto sera criada")))
                .add(field("name", text("field.name", "Nome do projeto"), projectName)
                        .setRequired(true)
                        .setValidator(rule(FOLDER_NAME,
                                text("error.invalidName", "Nome invalido para uma pasta."))), 2)
                .add(field("java", text("field.javaVersion", "Versao do Java"), javaVersion)
                        .setRequired(true))
                .addWide(field("package", text("field.package", "Pacote"), packageName)
                        .setRequired(true)
                        .setHelperText(needsCoordinates()
                                ? text("helper.package", "Derivado do group e do nome")
                                : text("helper.packagePlain", "Pacote base das fontes"))
                        .setValidator(rule(IDENTIFIER,
                                text("error.invalidPackage", "Use o formato com.exemplo.app."))));

        return stepCard(text("section.project", "Identidade do projeto"),
                text("section.projectHint", "Onde ele vai morar e como sera chamado"), form);
    }

    private JComponent coordinatesStep() {
        WizardFormGrid form = new WizardFormGrid(3);
        forms.put(STEP_COORDINATES, form);

        form.add(field("groupId", text("field.groupId", "Group"), groupId)
                        .setRequired(true)
                        .setValidator(rule(IDENTIFIER,
                                text("error.invalidGroup", "Use o formato com.exemplo."))))
                .add(field("artifactId", text("field.artifactId", "Artifact"), artifactId)
                        .setRequired(true)
                        .setValidator(rule(ARTIFACT,
                                text("error.invalidArtifact", "Letras, numeros, ponto e hifen."))))
                .add(field("version", text("field.version", "Version"), version)
                        .setHelperText(text("helper.version", "Opcional; padrao " + DEFAULT_VERSION))
                        .setValidator(rule(VERSION_TEXT,
                                text("error.invalidVersion", "Versao invalida."))));

        if (springBoot) {
            form.add(field("boot", text("field.bootVersion", "Spring Boot"), bootVersion)
                            .setRequired(true))
                    .add(field("packaging", text("field.packaging", "Empacotamento"), packaging))
                    .addWide(field("description", text("field.description", "Descricao"),
                            descriptionField)
                            .setHelperText(text("helper.optional", "Opcional")));
        }
        if (template == JavaTemplate.MAVEN_MULTIMODULE) {
            form.addWide(new TagFormField("modules", text("field.modules", "Modulos"), modules)
                    .setRequired(true)
                    .setHelperText(isSpringMultiModule()
                            ? text("helper.springModules",
                                    "O primeiro modulo sera o executavel Spring Boot")
                            : text("helper.modules",
                                    "Enter adiciona um modulo; ao menos um e necessario")))
                    .addWide(field("appendModuleToPackage",
                            text("field.modulePackages", "Pacotes dos modulos"),
                            appendModuleToPackage)
                            .setHelperText(text("helper.modulePackages",
                                    "Desativado: todos usam o pacote base")));
        }

        return stepCard(text("section.coordinates", "Coordenadas do artefato"),
                text("section.coordinatesHint",
                        "Como o projeto sera publicado e resolvido por outros builds"), form);
    }

    private JComponent stepCard(String title, String subtitle, JComponent content) {
        JPanel panel = WizardUi.transparent(new BorderLayout());
        panel.add(WizardUi.section(title, subtitle, content), BorderLayout.NORTH);
        return panel;
    }

    private JPanel locationRow() {
        JPanel row = WizardUi.transparent(new BorderLayout(UiTokens.space(2), 0));
        JButton browse = WizardUi.iconAction(WizardIcons.folder(16),
                text("action.browse", "Escolher pasta"));
        browse.addActionListener(event -> chooseLocation());
        row.add(location, BorderLayout.CENTER);
        row.add(browse, BorderLayout.EAST);
        row.setPreferredSize(new Dimension(UiTokens.scale(320), UiTokens.scale(WizardUi.FIELD_HEIGHT)));
        return row;
    }

    private JComponent dependenciesStep() {
        starterSelector.setEnabled(false);
        starterSelector
                .setTitles(text("list.available", "Disponiveis"), text("list.selected", "Selecionadas"))
                .setShowFilter(true).setShowCounters(true).setReorderable(false)
                .setRowHeight(UiTokens.scale(46))
                .setCellRenderer(new StarterRenderer());
        starterSelector.setPreferredSize(new Dimension(UiTokens.scale(560), UiTokens.scale(280)));

        dependencyHint = WizardUi.muted(text("status.loadingOptions",
                "Carregando catalogo do Spring Initializr..."));

        JPanel content = WizardUi.transparent(new BorderLayout(0, UiTokens.space(2)));
        content.add(dependencyHint, BorderLayout.NORTH);
        content.add(starterSelector, BorderLayout.CENTER);

        CardPanel card = WizardUi.section(text("section.dependencies", "Dependencias do Spring"),
                text("section.dependenciesHint",
                        "Passe para a direita o que o projeto deve trazer de fabrica"),
                content);
        JPanel holder = WizardUi.transparent(new BorderLayout());
        holder.add(card, BorderLayout.CENTER);
        return holder;
    }

    private JComponent reviewStep() {
        return stepCard(text("section.review", "Revisao"),
                text("section.reviewHint", "Confira antes de criar"), reviewContent);
    }

    private JComponent footer() {
        progress.setVisible(false);
        alert.setVisible(false);
        status.setForeground(UiTokens.muted());
        status.setFont(UiTokens.fontSmall());

        JPanel state = WizardUi.transparent(new BorderLayout(0, UiTokens.space(2)));
        state.add(status, BorderLayout.NORTH);
        state.add(alert, BorderLayout.CENTER);

        JPanel buttons = WizardUi.transparent(new FlowLayout(FlowLayout.RIGHT, UiTokens.space(2), 0));
        buttons.add(cancelButton);
        buttons.add(backButton);
        buttons.add(nextButton);

        JPanel bar = WizardUi.transparent(new BorderLayout(UiTokens.space(4), UiTokens.space(2)));
        bar.add(state, BorderLayout.CENTER);
        bar.add(buttons, BorderLayout.EAST);

        JPanel panel = WizardUi.transparent(new BorderLayout(0, UiTokens.space(2)));
        panel.add(progress, BorderLayout.NORTH);
        panel.add(bar, BorderLayout.CENTER);
        return panel;
    }

    private JComponent scrollable(JComponent content) {
        ScrollPanel scroll = new ScrollPanel(new ViewportWidthPanel(content));
        scroll.setHorizontalScrollEnabled(false);
        scroll.setUnitIncrement(UiTokens.scale(16));
        return scroll;
    }

    private void applyDefaults() {
        location.setPlaceholder("C:\\workspace");
        location.setText(System.getProperty("user.home", ""));
        projectName.setPlaceholder("meu-projeto");
        groupId.setPlaceholder("com.example");
        groupId.setText("com.example");
        artifactId.setPlaceholder("meu-projeto");
        version.setPlaceholder(DEFAULT_VERSION);
        version.setText(DEFAULT_VERSION);
        packageName.setPlaceholder("com.example.meuprojeto");
        descriptionField.setPlaceholder(text("placeholder.description", "Projeto gerado pela Orion"));

        javaVersion.setModel(new DefaultComboBoxModel<>(JAVA_VERSIONS.toArray(String[]::new)));
        javaVersion.setSelectedItem("21");

        packaging.addSegment("Jar", "jar").addSegment("War", "war")
                .setArc(UiTokens.radius(UiTokens.Radius.SM))
                .setPreferredHeight(UiTokens.scale(WizardUi.FIELD_HEIGHT))
                .setSelectedIndex(0, false);
        modules.setPlaceholder(text("placeholder.modules", "core, app"));
        modules.setTags(isSpringMultiModule() ? List.of("web", "core") : List.of("core", "app"));
        appendModuleToPackage.setOpaque(false);
        appendModuleToPackage.setText(text("option.appendModuleToPackage",
                "Acrescentar nome do modulo ao pacote"));
        appendModuleToPackage.setSelected(false);
    }

    private void wireDerivedFields() {
        projectName.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            String name = projectName.getText().trim();
            if (!artifactEdited) {
                artifactId.setText(name);
            }
            if (!packageEdited) {
                packageName.setText(derivedPackage(name));
            }
            refreshLocationHint();
        }));
        location.getDocument().addDocumentListener(
                new SimpleDocumentListener(this::refreshLocationHint));
        groupId.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (!packageEdited) {
                packageName.setText(derivedPackage(projectName.getText().trim()));
            }
        }));
        artifactId.getDocument().addDocumentListener(new SimpleDocumentListener(
                () -> artifactEdited |= artifactId.hasFocus()));
        packageName.getDocument().addDocumentListener(new SimpleDocumentListener(
                () -> packageEdited |= packageName.hasFocus()));
    }

    private void wireNavigation() {
        backButton.addActionListener(event -> showStep(step - 1));
        nextButton.addActionListener(event -> advance());
        cancelButton.addActionListener(event -> callback.cancel());
        steps.addEventListener(StepsPanel.STEP_SELECTED, event -> {
            Object selected = event.getValue();
            if (selected instanceof Integer target && target != step) {
                showStep(target);
            }
        });
    }

    private void showStep(int target) {
        if (busy || target < 0 || target >= stepIds.size()) {
            return;
        }
        step = target;
        alert.setVisible(false);
        bodyLayout.show(body, stepIds.get(step));
        steps.setCurrentStep(step, false);

        boolean last = step == stepIds.size() - 1;
        backButton.setVisible(step > 0);
        nextButton.setText(last
                ? text("action.create", "Criar projeto")
                : text("action.next", "Avancar"));
        nextButton.setIcon(last ? WizardIcons.create(16) : null);
        if (last) {
            refreshReview();
        }
        revalidate();
        repaint();
    }

    private void advance() {
        boolean last = step == stepIds.size() - 1;
        if (!validateUpTo(last ? stepIds.size() - 1 : step)) {
            return;
        }
        if (last) {
            create();
            return;
        }
        showStep(step + 1);
    }

    private boolean validateUpTo(int lastStep) {
        for (int index = 0; index <= lastStep && index < stepIds.size(); index++) {
            WizardFormGrid form = forms.get(stepIds.get(index));
            if (form == null) {
                continue;
            }
            List<FormField> invalid = form.invalidFields();
            if (!invalid.isEmpty()) {
                failOn(index, invalid.getFirst(), text("error.checkFields",
                        "Revise os campos destacados."));
                return false;
            }
        }
        return validateDestination();
    }

    private boolean validateDestination() {
        WizardFormGrid form = forms.get(STEP_PROJECT);
        Path parent = locationDirectory();
        if (parent == null) {
            return failField(form, "location", text("error.invalidLocation", "Pasta invalida."));
        }
        if (Files.exists(parent) && !Files.isDirectory(parent)) {
            return failField(form, "location",
                    text("error.locationNotDirectory", "O caminho escolhido nao e uma pasta."));
        }
        String name = projectFolderName();
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return failField(form, "name", text("error.invalidName", "Nome invalido para uma pasta."));
        }
        Path directory = parent.resolve(name).normalize();
        if (Files.exists(directory) && !Files.isDirectory(directory)) {
            return failField(form, "name",
                    text("error.nameTaken", "Ja existe um arquivo com esse nome na pasta."));
        }
        if (Files.isDirectory(directory) && !isEmptyDirectory(directory)) {
            return failField(form, "name",
                    text("error.directoryNotEmpty", "A pasta do projeto ja existe e nao esta vazia."));
        }
        alert.setVisible(false);
        return true;
    }

    private boolean failField(WizardFormGrid form, String fieldName, String message) {
        FormField field = form == null ? null : form.field(fieldName);
        if (field != null) {
            field.setError(message);
        }
        failOn(indexOf(STEP_PROJECT), field, message);
        return false;
    }

    private void failOn(int stepIndex, FormField field, String message) {
        if (stepIndex != step) {
            showStep(stepIndex);
        }
        showError(message);
        if (field != null) {
            field.getControl().requestFocusInWindow();
        }
    }

    private int indexOf(String stepId) {
        return stepIds.indexOf(stepId);
    }

    private void refreshReview() {
        reviewContent.removeAll();
        int line = 0;
        Path directory = targetDirectory();
        line = summaryRow(line, text("review.destination", "Destino"),
                directory == null ? "-" : directory.toString());
        line = summaryRow(line, text("review.template", "Modelo"), headline());
        if (needsCoordinates()) {
            line = summaryRow(line, text("review.coordinates", "Coordenadas"), coordinateSummary());
        }
        line = summaryRow(line, text("review.package", "Pacote"), packageName.getText().trim());
        line = summaryRow(line, text("review.java", "Java"), String.valueOf(selectedJavaVersion()));
        if (springBoot) {
            SpringInitializrClient.Option boot =
                    bootVersion.getValue(SpringInitializrClient.Option.class);
            line = summaryRow(line, text("review.boot", "Spring Boot"),
                    boot == null ? "-" : boot.name());
            line = summaryRow(line, text("review.packaging", "Empacotamento"),
                    String.valueOf(packaging.getSelectedValue()).toUpperCase(Locale.ROOT));
        }
        if (template == JavaTemplate.MAVEN_MULTIMODULE) {
            line = summaryRow(line, text("review.modules", "Modulos"),
                    String.join(", ", modules.getTags()));
            line = summaryRow(line, text("review.modulePackages", "Pacotes dos modulos"),
                    modules.getTags().stream()
                            .map(module -> module + " → " + modulePackageName(module))
                            .reduce((left, right) -> left + ", " + right).orElse("-"));
        }
        if (springBoot) {
            summaryChips(line, text("review.dependencies", "Dependencias"),
                    starterSelector.getSelected());
        }
        reviewContent.revalidate();
        reviewContent.repaint();
    }

    private String coordinateSummary() {
        return groupId.getText().trim() + " : " + artifactId.getText().trim()
                + " : " + effectiveVersion();
    }

    private int summaryRow(int line, String key, String value) {
        reviewContent.add(WizardUi.summaryKey(key), keyConstraints(line));

        GridBagConstraints content = valueConstraints(line);
        reviewContent.add(WizardUi.summaryValue("<html>"
                + (value == null || value.isBlank() ? "-" : value) + "</html>"), content);
        return line + 1;
    }

    private void summaryChips(int line, String key, List<SpringInitializrClient.Starter> selected) {
        reviewContent.add(WizardUi.summaryKey(key), keyConstraints(line));

        JPanel chips = WizardUi.transparent(new GridBagLayout());
        if (selected.isEmpty()) {
            chips.add(WizardUi.muted(text("review.noDependencies", "Nenhuma")), chipConstraints(0));
        } else {
            for (int index = 0; index < selected.size(); index++) {
                BadgeLabel chip = new BadgeLabel(selected.get(index).name(), BadgeLabel.Tone.PRIMARY);
                chip.setStyle(BadgeLabel.Style.SOFT).setSize(BadgeLabel.Size.SM);
                chips.add(chip, chipConstraints(index));
            }
        }
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = CHIP_COLUMNS;
        filler.gridy = 0;
        filler.weightx = 1;
        filler.fill = GridBagConstraints.HORIZONTAL;
        chips.add(Box.createHorizontalGlue(), filler);

        reviewContent.add(chips, valueConstraints(line));
    }

    private static GridBagConstraints chipConstraints(int index) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = index % CHIP_COLUMNS;
        constraints.gridy = index / CHIP_COLUMNS;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.insets = new Insets(0, 0, UiTokens.space(1), UiTokens.space(2));
        return constraints;
    }

    private static GridBagConstraints keyConstraints(int line) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = line;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(0, 0, UiTokens.space(2), UiTokens.space(4));
        return constraints;
    }

    private static GridBagConstraints valueConstraints(int line) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 1;
        constraints.gridy = line;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(0, 0, UiTokens.space(2), 0);
        return constraints;
    }

    private void loadInitializrMetadata() {
        setBusy(true, text("status.loadingOptions", "Carregando catalogo do Spring Initializr..."));
        Thread worker = new Thread(() -> {
            SpringInitializrClient.Metadata metadata = initializr.metadata();
            SwingUtilities.invokeLater(() -> applyMetadata(metadata));
        }, "spring-initializr-metadata");
        worker.setDaemon(true);
        worker.start();
    }

    private void applyMetadata(SpringInitializrClient.Metadata metadata) {
        setBusy(false, " ");
        if (metadata == null || metadata.isEmpty()) {
            String message = text("status.offline", "Nao foi possivel acessar o Spring Initializr.");
            dependencyHint.setText(message);
            showOfflineAlert(message);
            return;
        }
        bootVersion.setModel(new DefaultComboBoxModel<>(
                metadata.bootVersions().toArray(SpringInitializrClient.Option[]::new)));
        SpringInitializrClient.preferredBootVersion(metadata).ifPresent(preferred -> metadata.bootVersions()
                .stream().filter(option -> option.id().equals(preferred)).findFirst()
                .ifPresent(bootVersion::setSelectedItem));

        starterSelector.setAvailable(metadata.starters());
        starterSelector.setEnabled(true);
        dependencyHint.setText(metadata.starters().size() + " "
                + text("status.startersAvailable", "dependencias disponiveis"));
    }

    private void showOfflineAlert(String message) {
        JButton retry = WizardUi.secondary(text("action.retry", "Tentar novamente"), null);
        retry.addActionListener(event -> loadInitializrMetadata());
        alert.setSeverity(AlertPanel.Severity.WARNING)
                .setTitle(text("error.offlineTitle", "Sem catalogo de dependencias"))
                .setMessage(message)
                .addAction(retry)
                .restore();
        alert.setVisible(true);
        revalidate();
        repaint();
    }

    private void chooseLocation() {
        File start = null;
        try {
            File candidate = Path.of(location.getText()).toFile();
            if (candidate.exists()) {
                start = candidate;
            }
        } catch (InvalidPathException ignored) {
            // O seletor abre no diretorio padrao quando o texto ainda nao forma um caminho valido.
        }
        File selected = OsFilePicker.openDirectory(text("action.browse", "Escolher pasta"), start);
        if (selected != null) {
            location.setText(selected.getAbsolutePath());
        }
    }

    private void create() {
        Path directory = targetDirectory();
        if (directory == null) {
            showError(text("error.invalidLocation", "Pasta invalida."));
            return;
        }
        setBusy(true, text("status.creating", "Criando e configurando o projeto..."));
        Thread worker = new Thread(() -> {
            try {
                Path created = springBoot ? generateWithInitializr(directory) : generateLocally(directory);
                SwingUtilities.invokeLater(() -> callback.notifyProjectCreated(created));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false, " ");
                    showError(text("error.createFailed", "Falha ao criar:") + " "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                });
            }
        }, "java-project-wizard");
        worker.setDaemon(true);
        worker.start();
    }

    private Path generateLocally(Path directory) throws Exception {
        return JavaProjectScaffolder.create(new JavaProjectScaffolder.ProjectRequest(directory,
                template, groupId.getText().trim(), artifactId.getText().trim(), effectiveVersion(),
                descriptionField.getText().trim(), packageName.getText().trim(),
                selectedJavaVersion(), selectedModules(), appendModuleToPackage.isSelected()));
    }

    private Path generateWithInitializr(Path directory) throws Exception {
        List<String> selected = starterSelector.getSelected().stream()
                .map(SpringInitializrClient.Starter::id).toList();
        SpringInitializrClient.Option boot = bootVersion.getValue(SpringInitializrClient.Option.class);
        JavaProjectScaffolder.ProjectRequest projectRequest = isSpringMultiModule()
                ? new JavaProjectScaffolder.ProjectRequest(directory, template,
                        groupId.getText().trim(), artifactId.getText().trim(), effectiveVersion(),
                        descriptionField.getText().trim(), packageName.getText().trim(),
                        selectedJavaVersion(), selectedModules(), appendModuleToPackage.isSelected())
                : null;
        String initializrPackage = projectRequest == null || projectRequest.modules().isEmpty()
                ? packageName.getText().trim()
                : projectRequest.modulePackageName(projectRequest.modules().getFirst());
        SpringInitializrClient.GenerateRequest initializrRequest =
                new SpringInitializrClient.GenerateRequest(
                SpringInitializrClient.projectTypeOf(template.isGradle()), "java",
                boot == null ? "" : boot.id(), groupId.getText().trim(), artifactId.getText().trim(),
                effectiveVersion(), projectName.getText().trim(), descriptionField.getText().trim(),
                initializrPackage, String.valueOf(selectedJavaVersion()),
                String.valueOf(packaging.getSelectedValue()), selected);
        if (isSpringMultiModule()) {
            return SpringMultiModuleScaffolder.create(projectRequest, initializrRequest, initializr);
        }
        return initializr.generate(initializrRequest, directory);
    }

    private void setBusy(boolean running, String message) {
        this.busy = running;
        nextButton.setEnabled(!running);
        backButton.setEnabled(!running);
        progress.setVisible(running);
        alert.setVisible(false);
        status.setForeground(UiTokens.muted());
        status.setIcon(running ? WizardIcons.loading(14) : null);
        status.setText(message == null || message.isBlank() ? " " : message);
    }

    private void showError(String message) {
        status.setIcon(null);
        status.setText(" ");
        alert.setSeverity(AlertPanel.Severity.ERROR)
                .setTitle(text("error.title", "Nao foi possivel continuar"))
                .setMessage(message).restore();
        alert.setVisible(true);
        revalidate();
        repaint();
    }

    private void refreshLocationHint() {
        WizardFormGrid form = forms.get(STEP_PROJECT);
        FormField field = form == null ? null : form.field("location");
        if (field == null) {
            return;
        }
        Path directory = targetDirectory();
        field.setHelperText(directory == null
                ? text("helper.location", "Pasta onde a pasta do projeto sera criada")
                : text("helper.target", "Sera criado em") + " " + directory);
    }

    private boolean needsCoordinates() {
        return springBoot || template.needsCoordinates();
    }

    private boolean isSpringMultiModule() {
        return springBoot && template == JavaTemplate.MAVEN_MULTIMODULE;
    }

    private int selectedJavaVersion() {
        try {
            return Integer.parseInt(String.valueOf(javaVersion.getSelectedItem()));
        } catch (NumberFormatException e) {
            return 21;
        }
    }

    private static FormField field(String name, String label, JComponent control) {
        return new FormField(name, label, control).setHelperText(" ");
    }

    private List<String> selectedModules() {
        return template == JavaTemplate.MAVEN_MULTIMODULE ? modules.getTags() : List.of();
    }

    private String modulePackageName(String module) {
        String base = packageName.getText().trim();
        return appendModuleToPackage.isSelected()
                ? base + "." + JavaProjectScaffolder.modulePackageSegment(module) : base;
    }

    private String effectiveVersion() {
        String typed = version.getText().trim();
        return typed.isBlank() ? DEFAULT_VERSION : typed;
    }

    private Path locationDirectory() {
        String folder = location.getText().trim();
        if (folder.isBlank()) {
            return null;
        }
        try {
            return Path.of(folder).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private String projectFolderName() {
        String typed = projectName.getText().trim().replace('\\', '/');
        int slash = typed.lastIndexOf('/');
        return slash < 0 ? typed : typed.substring(slash + 1).trim();
    }

    private Path targetDirectory() {
        Path parent = locationDirectory();
        String name = projectFolderName();
        if (parent == null || name.isBlank()) {
            return null;
        }
        try {
            return parent.resolve(name).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private String derivedPackage(String name) {
        String group = groupId.getText().trim();
        String suffix = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return suffix.isBlank() ? group : group.isBlank() ? suffix : group + "." + suffix;
    }

    private static Validator<String> rule(Pattern pattern, String message) {
        return value -> {
            String typed = value == null ? "" : value.trim();
            if (typed.isBlank() || pattern.matcher(typed).matches()) {
                return ValidationResult.ok();
            }
            return ValidationResult.error(message);
        };
    }

    private static boolean isEmptyDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static String text(String key, String fallback) {
        return I18n.getText(JavaProjectWizardView.class, key, fallback);
    }

    private static final class TagFormField extends FormField {
        private TagFormField(String name, String labelText, TagInputField control) {
            super(name, labelText, control);
        }

        @Override
        public Object getValue() {
            return ((TagInputField) getControl()).getTags();
        }

        @Override
        public FormField setValue(Object value) {
            if (value instanceof List<?> list) {
                ((TagInputField) getControl()).setTags(list.stream().map(String::valueOf).toList());
            }
            return this;
        }
    }

    private static final class DelegatedFormField extends FormField {
        private final JComponent value;

        private DelegatedFormField(String name, String labelText, JComponent wrapper, JComponent value) {
            super(name, labelText, wrapper);
            this.value = value;
            if (value instanceof javax.swing.text.JTextComponent input) {
                input.getDocument().addDocumentListener(new SimpleDocumentListener(this::clearError));
            }
        }

        @Override
        public Object getValue() {
            return FormValues.read(value);
        }

        @Override
        public FormField setValue(Object newValue) {
            FormValues.write(value, newValue);
            return this;
        }
    }

    private static final class ViewportWidthPanel extends JPanel implements Scrollable {
        private ViewportWidthPanel(JComponent content) {
            super(new BorderLayout());
            setOpaque(false);
            add(content, BorderLayout.NORTH);
        }

        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return UiTokens.scale(16);
        }

        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return visible.height;
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class StarterRenderer extends DefaultListCellRenderer {

        private static final int MAX_DESCRIPTION = 70;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            if (!(value instanceof SpringInitializrClient.Starter starter)) {
                return label;
            }
            String muted = hex(UiTokens.muted());
            label.setText("<html><b>" + starter.name() + "</b> &nbsp; <font color='" + muted + "'>"
                    + starter.group() + "</font><br><font color='" + muted + "'>"
                    + shorten(starter.description()) + "</font></html>");
            label.setIcon(WizardIcons.dependency(16));
            label.setIconTextGap(UiTokens.space(2));
            label.setFont(UiTokens.fontSmall());
            label.setBorder(BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(2),
                    UiTokens.space(1), UiTokens.space(2)));
            label.setToolTipText(starter.description());
            return label;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            preferred.width = Math.min(preferred.width, UiTokens.scale(260));
            return preferred;
        }

        private static String shorten(String description) {
            if (description == null || description.isBlank()) {
                return "";
            }
            String text = description.trim();
            return text.length() <= MAX_DESCRIPTION
                    ? text
                    : text.substring(0, MAX_DESCRIPTION - 1).trim() + "...";
        }

        private static String hex(Color color) {
            return String.format("#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
        }
    }

    private record SimpleDocumentListener(Runnable action) implements DocumentListener {
        @Override public void insertUpdate(DocumentEvent event) {
            action.run();
        }

        @Override public void removeUpdate(DocumentEvent event) {
            action.run();
        }

        @Override public void changedUpdate(DocumentEvent event) {
            action.run();
        }
    }
}
