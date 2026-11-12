package com.nullfuscator.obf.gui;

import com.nullfuscator.obf.core.BuildInfo;
import com.nullfuscator.obf.core.Main;
import com.nullfuscator.obf.core.ObfMapping;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class NullfuscatorFrame extends JFrame {

    private static final String PREF_PRESET = "preset";
    private static final String PREF_PROFILE = "profile";
    private static final String PREF_EXEMPTIONS = "exemptions";
    private static final String PREF_LIBS = "libraries";
    private static final String PREF_KEEP_PUBLIC = "keep_public";
    private static final String PREF_KEEP_VIRTUAL = "keep_virtual";
    private static final String PREF_KEEP_SIGNATURES = "keep_signatures";
    private static final String PREF_EXEMPT_MIXINS = "exempt_mixins";
    private static final String PREF_ALLOW_INCOMPLETE = "allow_incomplete";
    private static final String PREF_SEED = "seed";
    private static final String PREF_SAVE_MAPPING = "save_mapping";

    private final Preferences prefs = Preferences.userNodeForPackage(NullfuscatorFrame.class);

    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JComboBox<String> presetCombo = new JComboBox<>(new String[]{"full", "strong", "balanced", "light"});
    private final JComboBox<String> profileCombo = new JComboBox<>(new String[]{
            "Обычное Java приложение / Библиотека",
            "Fabric / Quilt мод (Minecraft)",
            "Spigot / Paper плагин",
            "Пользовательская конфигурация"
    });

    private final JTextField seedField = new JTextField();
    private final JTextField customConfigField = new JTextField();
    private final JCheckBox saveMappingCheck = new JCheckBox("Сохранять ProGuard mapping (.map)", false);

    private final JTree jarTree = new JTree(new DefaultMutableTreeNode("Перетащи JAR или выбери файл"));
    private final JLabel jarTreeInfoLabel = new JLabel("Файлы архива не загружены");

    private final JTextArea exemptionsArea = new JTextArea();
    private final JCheckBox exemptMixinsCheck = new JCheckBox("Исключать все миксины (*/mixin/*)", false);
    private final JCheckBox keepPublicMethodsCheck = new JCheckBox("Не переименовывать публичные методы", false);
    private final JCheckBox keepVirtualMethodsCheck = new JCheckBox("Не переименовывать виртуальные методы", false);
    private final JCheckBox keepSignaturesCheck = new JCheckBox("Сохранять сигнатуры дженериков (для Gson/Jackson)", false);
    private final JCheckBox allowIncompleteClasspathCheck = new JCheckBox("Разрешить неполный класспас (Allow Incomplete Classpath)", true);
    private final JCheckBox allowMultiReleaseCheck = new JCheckBox("Разрешить переименование Multi-Release (allowMultiReleaseRename)", true);
    private final JTextArea hotPathsArea = new JTextArea();

    private final JCheckBox budgetsEnabledCheck = new JCheckBox("Включить контроль бюджетов роста (Budgets Policy)", true);
    private final JSpinner maxJarGrowthPercentSpinner = new JSpinner(new SpinnerNumberModel(120, -1, 1000, 5));
    private final JSpinner jarGrowthBytesSpinner = new JSpinner(new SpinnerNumberModel(65536, 0, 100_000_000, 1024));
    private final JSpinner maxClassGrowthPercentSpinner = new JSpinner(new SpinnerNumberModel(10, -1, 1000, 1));
    private final JSpinner classGrowthAllowanceSpinner = new JSpinner(new SpinnerNumberModel(4, 0, 10000, 1));
    private final JSpinner maxMethodGrowthPercentSpinner = new JSpinner(new SpinnerNumberModel(35, -1, 1000, 5));
    private final JSpinner methodGrowthAllowanceSpinner = new JSpinner(new SpinnerNumberModel(64, 0, 10000, 8));
    private final JSpinner maxInstructionGrowthPercentSpinner = new JSpinner(new SpinnerNumberModel(150, -1, 1000, 10));
    private final JSpinner instructionGrowthAllowanceSpinner = new JSpinner(new SpinnerNumberModel(1024, 0, 100000, 64));

    private final JCheckBox coverageEnabledCheck = new JCheckBox("Требовать минимальное покрытие защиты (Coverage)", false);
    private final JSpinner coverageMinPercentSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JTextField coverageRequiredField = new JTextField();

    private final JCheckBox antiAiCheck = new JCheckBox("Защита от анализа ИИ (Anti-AI)", true);
    private final JSpinner antiAiLevelSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 3, 1));
    private final JSpinner antiAiConstPercentSpinner = new JSpinner(new SpinnerNumberModel(85, 0, 100, 5));
    private final JSpinner antiAiDecoySpinner = new JSpinner(new SpinnerNumberModel(8, 0, 32, 2));
    private final JSpinner antiAiBranchPercentSpinner = new JSpinner(new SpinnerNumberModel(30, 0, 100, 5));

    private final JCheckBox antiDecompilerCheck = new JCheckBox("Защита от декомпиляторов (Anti-Decompiler)", true);
    private final JSpinner antiDecompilerLevelSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 3, 1));

    private final JCheckBox antiDeobfCheck = new JCheckBox("Защита от деобфускаторов (Anti-Deobf AST)", true);
    private final JSpinner antiDeobfLevelSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 3, 1));
    private final JCheckBox antiDeobfBadSigCheck = new JCheckBox("Испорченные сигнатуры методов/полей (badSignatures)", true);
    private final JCheckBox antiDeobfBadClassSigCheck = new JCheckBox("Испорченные сигнатуры классов (badRealClassSignatures)", false);
    private final JCheckBox antiDeobfPoisonAnnoCheck = new JCheckBox("Призрачные аннотации (poisonAnnotations)", true);
    private final JCheckBox antiDeobfPoisonInnerCheck = new JCheckBox("Повреждение внутренних классов (poisonInnerClasses)", true);
    private final JCheckBox antiDeobfTrapRealCheck = new JCheckBox("Ловушки в методах (trapRealMethods)", true);
    private final JCheckBox antiDeobfHoneypotsCheck = new JCheckBox("Фальшивые обработчики (honeypotEvaluators)", true);

    private final JCheckBox antiDebugCheck = new JCheckBox("Обнаружение отладчиков (Anti-Debug)", true);
    private final JSpinner antiDebugCheckPercentSpinner = new JSpinner(new SpinnerNumberModel(50, 0, 100, 5));
    private final JSpinner antiDebugDetectorsSpinner = new JSpinner(new SpinnerNumberModel(6, 1, 64, 1));

    private final JCheckBox fileCrasherCheck = new JCheckBox("Мусорные атрибуты для краша утилит (File Crasher)", true);
    private final JCheckBox runtimeIntegrityCheck = new JCheckBox("Контроль целостности архива (Runtime Integrity)", false);
    private final JTextField runtimeIntegrityIncludeField = new JTextField();

    private final JCheckBox flowFlattenCheck = new JCheckBox("Сглаживание потока (Control Flow Flattening)", true);
    private final JSpinner flowFlattenPercentSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 5));
    private final JCheckBox flowFlattenAllowRefLocalsCheck = new JCheckBox("Разрешить ссылочные локальные (allowReferenceLocals)", false);

    private final JCheckBox safeControlFlowCheck = new JCheckBox("Непрозрачные предикаты (Safe Control Flow)", false);
    private final JSpinner safeControlFlowLevelSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 3, 1));

    private final JCheckBox mangledJumpCheck = new JCheckBox("Ложные переходы GOTO (Mangled Jump)", false);
    private final JCheckBox flowSwitchCheck = new JCheckBox("Предикаты switch (Flow Switch)", true);
    private final JCheckBox integerReturnCheck = new JCheckBox("Шифрование возврата целых чисел (Integer Return)", true);

    private final JCheckBox exceptionReturnCheck = new JCheckBox("Возврат через исключения (Exception Return)", true);
    private final JSpinner exceptionReturnPercentSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 5));

    private final JCheckBox stringEncCheck = new JCheckBox("Шифрование строк (String Encryption)", true);
    private final JComboBox<String> stringEncTypeCombo = new JComboBox<>(new String[]{"POLYMORPHIC", "STANDARD"});

    private final JCheckBox numberEncCheck = new JCheckBox("Шифрование чисел (Number Math)", true);
    private final JSpinner numberEncLayersSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 5, 1));

    private final JCheckBox refHidingCheck = new JCheckBox("Скрытие вызовов invokedynamic (Reference Hiding)", true);
    private final JSpinner refHidingPercentSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 5));
    private final JSpinner refHidingBootstrapPoolSpinner = new JSpinner(new SpinnerNumberModel(8, 0, 64, 1));

    private final JCheckBox fieldIndirectionCheck = new JCheckBox("Косвенный доступ к полям (Field Indirection)", true);
    private final JSpinner fieldIndirectionPercentSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 5));

    private final JCheckBox fieldPackingCheck = new JCheckBox("Упаковка полей в векторы состояния (Field Packing)", true);
    private final JSpinner fieldPackingMinFieldsSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 64, 1));

    private final JCheckBox crossClassDispersionCheck = new JCheckBox("Распыление операций по классам (Cross-Class Dispersion)", true);
    private final JSpinner dispersionPercentSpinner = new JSpinner(new SpinnerNumberModel(75, 0, 100, 5));
    private final JSpinner dispersionSinksSpinner = new JSpinner(new SpinnerNumberModel(24, 1, 2048, 2));
    private final JSpinner dispersionVariantsSpinner = new JSpinner(new SpinnerNumberModel(2, 1, 16, 1));
    private final JSpinner dispersionMaxHelpersSpinner = new JSpinner(new SpinnerNumberModel(2048, 0, 8192, 128));
    private final JSpinner dispersionMaxOpsSpinner = new JSpinner(new SpinnerNumberModel(8192, 0, 100000, 256));

    private final JCheckBox semanticCoreCheck = new JCheckBox("Семантическое кодирование арифметики (Semantic Core)", true);
    private final JSpinner semanticCorePercentSpinner = new JSpinner(new SpinnerNumberModel(45, 0, 100, 5));
    private final JSpinner semanticCoreMinOpsSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 32, 1));
    private final JSpinner semanticCoreMaxMethodsSpinner = new JSpinner(new SpinnerNumberModel(64, 1, 512, 8));

    private final JCheckBox semanticFabricCheck = new JCheckBox("Распределение логики по фрагментам (Semantic Fabric)", true);
    private final JSpinner semanticFabricPercentSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 5));
    private final JSpinner semanticFabricMinOpsSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 32, 1));
    private final JSpinner semanticFabricMaxMethodsSpinner = new JSpinner(new SpinnerNumberModel(96, 1, 512, 8));
    private final JSpinner semanticFabricCarriersSpinner = new JSpinner(new SpinnerNumberModel(12, 3, 32, 1));

    private final JCheckBox methodRelocationCheck = new JCheckBox("Перенос методов между классами (Method Relocation)", true);
    private final JSpinner methodRelocPercentSpinner = new JSpinner(new SpinnerNumberModel(85, 0, 100, 5));
    private final JSpinner methodRelocMaxMethodsSpinner = new JSpinner(new SpinnerNumberModel(128, 1, 512, 8));
    private final JSpinner methodRelocCarriersSpinner = new JSpinner(new SpinnerNumberModel(16, 3, 48, 1));
    private final JSpinner methodRelocMinInsnsSpinner = new JSpinner(new SpinnerNumberModel(20, 8, 256, 4));

    private final JCheckBox methodExtractionCheck = new JCheckBox("Извлечение статических методов (Method Extraction)", true);
    private final JSpinner methodExtractPercentSpinner = new JSpinner(new SpinnerNumberModel(100, 0, 100, 5));
    private final JSpinner methodExtractMaxMethodsSpinner = new JSpinner(new SpinnerNumberModel(160, 1, 512, 8));
    private final JSpinner methodExtractCarriersSpinner = new JSpinner(new SpinnerNumberModel(16, 3, 48, 1));
    private final JSpinner methodExtractMinInsnsSpinner = new JSpinner(new SpinnerNumberModel(8, 4, 128, 2));

    private final JCheckBox renameClassesCheck = new JCheckBox("Переименование классов (Class Renamer)", true);
    private final JTextField classPrefixField = new JTextField("a/");
    private final JSpinner classDepthSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 32, 1));
    private final JCheckBox renameMainCheck = new JCheckBox("Переименовывать Main класс (renameMain)", true);

    private final JCheckBox renameMethodsCheck = new JCheckBox("Переименование методов (Method Renamer)", true);
    private final JCheckBox renameMethodsPublicCheck = new JCheckBox("Переименовывать публичные методы", false);
    private final JCheckBox renameMethodsVirtualCheck = new JCheckBox("Переименовывать виртуальные методы", false);
    private final JSpinner methodDepthSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 32, 1));

    private final JCheckBox renameFieldsCheck = new JCheckBox("Переименование полей (Field Renamer)", true);
    private final JSpinner fieldDepthSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 32, 1));

    private final JTextField namingAlphabetField = new JTextField("I, l1, lI, 1l, ll, Il");

    private final JCheckBox recordMetadataCheck = new JCheckBox("Скрытие метаданных Records (Record Metadata)", true);
    private final JCheckBox recordEraseIdentityCheck = new JCheckBox("Стирать идентичность Records (eraseIdentity)", true);
    private final JSpinner recordDepthSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 32, 1));

    private final JCheckBox resourceRenamerCheck = new JCheckBox("Переименование ресурсов (Resource Renamer)", false);
    private final JSpinner resourceDepthSpinner = new JSpinner(new SpinnerNumberModel(8, 1, 32, 1));
    private final JTextField resourceExcludeNsField = new JTextField("minecraft");
    private final JTextField resourceExcludeDirsField = new JTextField("fonts, icons, shaders");

    private final JCheckBox sourceStripCheck = new JCheckBox("Очистка отладочной информации (Source Strip)", true);
    private final JCheckBox stripLineNumbersCheck = new JCheckBox("Удалять номера строк (stripLineNumbers)", true);
    private final JCheckBox stripLocalVarsCheck = new JCheckBox("Удалять имена локальных переменных (stripLocalVars)", true);
    private final JCheckBox stripSignaturesCheck = new JCheckBox("Удалять сигнатуры дженериков (stripSignatures)", false);

    private final JCheckBox releaseHardeningCheck = new JCheckBox("Очистка метаданных сборщика (Release Hardening)", true);
    private final JCheckBox stripMavenCheck = new JCheckBox("Удалять META-INF/maven (stripMavenMetadata)", true);
    private final JCheckBox stripFabricDescCheck = new JCheckBox("Очищать описание в fabric.mod.json", true);

    private final JCheckBox annotationSanitizerCheck = new JCheckBox("Очистка специфичных аннотаций (Annotation Sanitizer)", false);
    private final JTextField annotationSanitizerDescriptorsField = new JTextField();

    private final DefaultListModel<String> libsModel = new DefaultListModel<>();
    private final JList<String> libsList = new JList<>(libsModel);
    private final JTextArea logArea = new JTextArea();
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel statusLabel = new JLabel("Готов к работе");
    private final JButton obfuscateBtn = new JButton("Запустить обфускацию");

    private final JTextField retraceMapField = new JTextField();
    private final JTextArea retraceInArea = new JTextArea();
    private final JTextArea retraceOutArea = new JTextArea();

    private final JTextField infoMapField = new JTextField();
    private final JTextArea infoOutArea = new JTextArea();
    private final List<Runnable> toggleUpdaters = new ArrayList<>();

    public NullfuscatorFrame() {
        setTitle("Nullfuscator " + BuildInfo.VERSION + " — Standalone Obfuscator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1180, 880);
        setMinimumSize(new Dimension(1020, 720));
        setLocationRelativeTo(null);

        setupCheckboxSynchronizations();

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(safeFont(tabbedPane.getFont(), Font.BOLD, 13.5f));
        tabbedPane.addTab("Обфускатор", createObfuscatorPanel());
        tabbedPane.addTab("Инспектор JAR", createJarInspectorPanel());
        tabbedPane.addTab("Исключения (Exemptions)", createExemptionsPanel());
        tabbedPane.addTab("Трансформеры", createTransformersPanel());
        tabbedPane.addTab("Лимиты и бюджеты", createBudgetsPanel());
        tabbedPane.addTab("Демаппинг (Retrace)", createRetracePanel());
        tabbedPane.addTab("Инфо о маппинге", createMappingInfoPanel());

        setContentPane(tabbedPane);
        setupAppIcon();
        setupGlobalDropTarget();
        loadPreferences();

        presetCombo.addActionListener(e -> {
            applyPresetDefaults((String) presetCombo.getSelectedItem());
            applyProfileDefaults();
        });
        profileCombo.addActionListener(e -> applyProfileDefaults());
    }

    private void setupCheckboxSynchronizations() {
        renameMethodsPublicCheck.addActionListener(e -> keepPublicMethodsCheck.setSelected(!renameMethodsPublicCheck.isSelected()));
        keepPublicMethodsCheck.addActionListener(e -> renameMethodsPublicCheck.setSelected(!keepPublicMethodsCheck.isSelected()));

        renameMethodsVirtualCheck.addActionListener(e -> keepVirtualMethodsCheck.setSelected(!renameMethodsVirtualCheck.isSelected()));
        keepVirtualMethodsCheck.addActionListener(e -> renameMethodsVirtualCheck.setSelected(!keepVirtualMethodsCheck.isSelected()));

        stripSignaturesCheck.addActionListener(e -> keepSignaturesCheck.setSelected(!stripSignaturesCheck.isSelected()));
        keepSignaturesCheck.addActionListener(e -> stripSignaturesCheck.setSelected(!keepSignaturesCheck.isSelected()));
    }

    private void setupAppIcon() {
        try {
            InputStream stream = getClass().getResourceAsStream("/icon.png");
            if (stream == null) {
                stream = getClass().getResourceAsStream("/assets/icon.png");
            }
            if (stream != null) {
                Image base = ImageIO.read(stream);
                if (base != null) {
                    List<Image> icons = new ArrayList<>();
                    int[] sizes = {16, 24, 32, 48, 64, 128, 256};
                    for (int s : sizes) {
                        icons.add(base.getScaledInstance(s, s, Image.SCALE_SMOOTH));
                    }
                    setIconImages(icons);

                    if (Taskbar.isTaskbarSupported()) {
                        Taskbar taskbar = Taskbar.getTaskbar();
                        if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                            taskbar.setIconImage(base);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private JPanel createObfuscatorPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));

        JPanel configPanel = new JPanel();
        configPanel.setLayout(new BoxLayout(configPanel, BoxLayout.Y_AXIS));

        JPanel filesCard = createSectionPanel("Файлы");
        filesCard.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        filesCard.add(new JLabel("Входной JAR:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        inputField.setToolTipText("Перетащи JAR файл сюда или нажми 'Обзор'");
        filesCard.add(inputField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        JButton browseInputBtn = new JButton("Обзор...");
        browseInputBtn.addActionListener(e -> chooseInputJar());
        filesCard.add(browseInputBtn, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        filesCard.add(new JLabel("Выходной JAR:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        filesCard.add(outputField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        JPanel outBtnGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton browseOutputBtn = new JButton("Обзор...");
        browseOutputBtn.addActionListener(e -> chooseOutputJar());
        JButton revealOutputBtn = new JButton("Папка");
        revealOutputBtn.setToolTipText("Показать расположение выходного файла в проводнике");
        revealOutputBtn.addActionListener(e -> {
            String p = outputField.getText().trim();
            if (!p.isEmpty()) {
                revealInExplorer(new File(p));
            }
        });
        outBtnGroup.add(browseOutputBtn);
        outBtnGroup.add(revealOutputBtn);
        filesCard.add(outBtnGroup, gbc);

        configPanel.add(filesCard);
        configPanel.add(Box.createVerticalStrut(8));

        JPanel settingsCard = createSectionPanel("Профиль и параметры защиты");
        settingsCard.setLayout(new GridBagLayout());
        GridBagConstraints sgbc = new GridBagConstraints();
        sgbc.insets = new Insets(4, 4, 4, 4);
        sgbc.fill = GridBagConstraints.HORIZONTAL;

        sgbc.gridx = 0;
        sgbc.gridy = 0;
        sgbc.weightx = 0.0;
        settingsCard.add(new JLabel("Тип проекта:"), sgbc);

        sgbc.gridx = 1;
        sgbc.weightx = 0.45;
        settingsCard.add(profileCombo, sgbc);

        sgbc.gridx = 2;
        sgbc.weightx = 0.0;
        settingsCard.add(new JLabel("Пресет:"), sgbc);

        sgbc.gridx = 3;
        sgbc.weightx = 0.25;
        settingsCard.add(presetCombo, sgbc);

        sgbc.gridx = 4;
        sgbc.weightx = 0.0;
        settingsCard.add(new JLabel("Сид:"), sgbc);

        sgbc.gridx = 5;
        sgbc.weightx = 0.3;
        JPanel seedBox = new JPanel(new BorderLayout(2, 0));
        seedField.setToolTipText("Опциональный фиксированный сид для reproducible build");
        seedBox.add(seedField, BorderLayout.CENTER);
        JPanel seedTools = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JButton randSeedBtn = new JButton("Rand");
        randSeedBtn.setToolTipText("Сгенерировать случайный сид");
        randSeedBtn.addActionListener(e -> seedField.setText(String.valueOf(Math.abs(new SecureRandom().nextLong()))));
        JButton clearSeedBtn = new JButton("X");
        clearSeedBtn.setToolTipText("Очистить сид");
        clearSeedBtn.addActionListener(e -> seedField.setText(""));
        seedTools.add(randSeedBtn);
        seedTools.add(clearSeedBtn);
        seedBox.add(seedTools, BorderLayout.EAST);
        settingsCard.add(seedBox, sgbc);

        sgbc.gridx = 0;
        sgbc.gridy = 1;
        sgbc.weightx = 0.0;
        settingsCard.add(new JLabel("Кастомный .hocon:"), sgbc);

        sgbc.gridx = 1;
        sgbc.gridwidth = 3;
        sgbc.weightx = 0.7;
        customConfigField.setToolTipText("Опционально: путь к готовому HOCON файлу (переопределит настройки)");
        settingsCard.add(customConfigField, sgbc);

        sgbc.gridx = 4;
        sgbc.gridwidth = 2;
        sgbc.weightx = 0.0;
        JPanel cfgBtnGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton browseCfgBtn = new JButton("Обзор...");
        browseCfgBtn.addActionListener(e -> chooseConfigFile());
        JButton clearCfgBtn = new JButton("Сбросить");
        clearCfgBtn.addActionListener(e -> customConfigField.setText(""));
        JButton previewCfgBtn = new JButton("HOCON");
        previewCfgBtn.setToolTipText("Предпросмотр сгенерированного HOCON конфига");
        previewCfgBtn.addActionListener(e -> showHoconPreview());
        cfgBtnGroup.add(browseCfgBtn);
        cfgBtnGroup.add(clearCfgBtn);
        cfgBtnGroup.add(previewCfgBtn);
        settingsCard.add(cfgBtnGroup, sgbc);

        configPanel.add(settingsCard);
        configPanel.add(Box.createVerticalStrut(8));

        JPanel libsCard = createSectionPanel("Внешние библиотеки и зависимости (-l)");
        libsCard.setLayout(new BorderLayout(6, 6));

        libsList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane libsScroll = new JScrollPane(libsList);
        libsScroll.setPreferredSize(new Dimension(100, 105));
        libsCard.add(libsScroll, BorderLayout.CENTER);

        JPanel libsBtnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton scanBtn = new JButton("Найти в...");
        scanBtn.setToolTipText("Поиск библиотек в Gradle, Maven или выбранной папке");
        JPopupMenu scanMenu = new JPopupMenu();
        JMenuItem gradleItem = new JMenuItem("В .gradle");
        gradleItem.addActionListener(e -> runGradleScan());
        JMenuItem mavenItem = new JMenuItem("В .maven");
        mavenItem.addActionListener(e -> runMavenScan());
        JMenuItem customItem = new JMenuItem("Свой выбор...");
        customItem.addActionListener(e -> runCustomFolderScan());
        scanMenu.add(gradleItem);
        scanMenu.add(mavenItem);
        scanMenu.add(customItem);
        scanBtn.addActionListener(e -> scanMenu.show(scanBtn, 0, scanBtn.getHeight()));

        JButton addLibBtn = new JButton("+ Добавить JAR...");
        addLibBtn.addActionListener(e -> addLibraryFile());

        JButton removeLibBtn = new JButton("- Удалить");
        removeLibBtn.addActionListener(e -> removeSelectedLibs());

        JButton clearLibsBtn = new JButton("Очистить все");
        clearLibsBtn.addActionListener(e -> libsModel.clear());

        libsBtnBar.add(scanBtn);
        libsBtnBar.add(addLibBtn);
        libsBtnBar.add(removeLibBtn);
        libsBtnBar.add(clearLibsBtn);
        libsBtnBar.add(saveMappingCheck);
        libsCard.add(libsBtnBar, BorderLayout.SOUTH);

        configPanel.add(libsCard);

        root.add(configPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout(6, 6));
        centerPanel.setBorder(createTitledBorder("Лог выполнения"));

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(24, 24, 27));
        logArea.setForeground(new Color(228, 228, 231));
        logArea.setCaretColor(Color.WHITE);
        JScrollPane logScroll = new JScrollPane(logArea);
        centerPanel.add(logScroll, BorderLayout.CENTER);

        JPanel logActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton clearLogBtn = new JButton("Очистить лог");
        clearLogBtn.addActionListener(e -> logArea.setText(""));
        logActions.add(clearLogBtn);
        centerPanel.add(logActions, BorderLayout.SOUTH);

        root.add(centerPanel, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        bottomPanel.setBorder(new EmptyBorder(6, 0, 0, 0));

        progressBar.setStringPainted(true);
        progressBar.setString("Готов к работе");
        progressBar.setPreferredSize(new Dimension(200, 24));

        obfuscateBtn.setFont(safeFont(obfuscateBtn.getFont(), Font.BOLD, 14f));
        obfuscateBtn.setPreferredSize(new Dimension(240, 42));
        obfuscateBtn.setBackground(new Color(37, 99, 235));
        obfuscateBtn.setForeground(Color.WHITE);
        obfuscateBtn.setFocusPainted(false);
        obfuscateBtn.addActionListener(e -> startObfuscation());

        JPanel statusWrapper = new JPanel(new BorderLayout(4, 4));
        statusWrapper.add(statusLabel, BorderLayout.NORTH);
        statusWrapper.add(progressBar, BorderLayout.SOUTH);

        bottomPanel.add(statusWrapper, BorderLayout.CENTER);
        bottomPanel.add(obfuscateBtn, BorderLayout.EAST);

        root.add(bottomPanel, BorderLayout.SOUTH);

        inputField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { checkInput(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { checkInput(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { checkInput(); }
            private void checkInput() {
                String text = inputField.getText().trim();
                if (!text.isEmpty()) {
                    File f = new File(text);
                    if (f.isFile() && text.toLowerCase().endsWith(".jar")) {
                        if (outputField.getText().trim().isEmpty() || outputField.getText().endsWith("-obf.jar")) {
                            outputField.setText(computeDefaultOutput(f));
                        }
                        detectTargetType(f);
                        refreshJarTree(f);
                    }
                }
            }
        });

        return root;
    }

    private JPanel createJarInspectorPanel() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));

        JPanel topBar = new JPanel(new BorderLayout(8, 4));
        jarTreeInfoLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        topBar.add(jarTreeInfoLabel, BorderLayout.CENTER);

        JButton refreshBtn = new JButton("Обновить JAR");
        refreshBtn.addActionListener(e -> {
            String p = inputField.getText().trim();
            if (!p.isEmpty()) {
                refreshJarTree(new File(p));
            }
        });
        topBar.add(refreshBtn, BorderLayout.EAST);
        root.add(topBar, BorderLayout.NORTH);

        jarTree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        jarTree.setCellRenderer(new JarTreeCellRenderer());
        JScrollPane treeScroll = new JScrollPane(jarTree);
        root.add(treeScroll, BorderLayout.CENTER);

        jarTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                handleTreePopup(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                handleTreePopup(e);
            }

            private void handleTreePopup(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                    int row = jarTree.getClosestRowForLocation(e.getX(), e.getY());
                    if (row >= 0) {
                        jarTree.setSelectionRow(row);
                        TreePath path = jarTree.getPathForRow(row);
                        if (path != null) {
                            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                            showJarTreeContextMenu(node, e.getX(), e.getY());
                        }
                    }
                }
            }
        });

        JLabel footer = new JLabel("<html><font color='#a1a1aa'>Подсказка: кликни правой кнопкой мыши по классу или пакету, чтобы добавить или убрать исключение.</font></html>");
        root.add(footer, BorderLayout.SOUTH);

        return root;
    }

    private void showJarTreeContextMenu(DefaultMutableTreeNode node, int x, int y) {
        if (node == null || node == jarTree.getModel().getRoot()) {
            return;
        }

        JPopupMenu menu = new JPopupMenu();
        boolean exempt = isNodeExempted(node);

        if (exempt) {
            JMenuItem removeExemptItem = new JMenuItem("Убрать из исключений");
            removeExemptItem.setFont(safeFont(removeExemptItem.getFont(), Font.BOLD, 12f));
            removeExemptItem.addActionListener(e -> removeNodeFromExemptions(node));
            menu.add(removeExemptItem);
        } else {
            JMenuItem addExemptItem = new JMenuItem("Добавить в исключения");
            addExemptItem.setFont(safeFont(addExemptItem.getFont(), Font.BOLD, 12f));
            addExemptItem.addActionListener(e -> addSelectedTreeNodesToExemptions(false));
            menu.add(addExemptItem);

            String pkgLabel = node.isLeaf() ? "Исключить весь пакет (*)" : "Исключить всю папку (*)";
            JMenuItem addPkgItem = new JMenuItem(pkgLabel);
            addPkgItem.addActionListener(e -> addSelectedTreeNodesToExemptions(true));
            menu.add(addPkgItem);
        }

        menu.addSeparator();

        JMenuItem copyItem = new JMenuItem("Скопировать полное имя");
        copyItem.addActionListener(e -> copySelectedTreeNodeName());
        menu.add(copyItem);

        menu.show(jarTree, x, y);
    }

    private void refreshJarTree(File jar) {
        if (jar == null || !jar.isFile()) {
            jarTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Файл не выбран")));
            jarTreeInfoLabel.setText("Файлы архива не загружены");
            return;
        }

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(jar.getName());
        int totalFiles = 0;
        int totalClasses = 0;

        try (ZipFile z = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = z.entries();
            List<ZipEntry> entryList = new ArrayList<>();
            while (entries.hasMoreElements()) {
                entryList.add(entries.nextElement());
            }
            entryList.sort(Comparator.comparing(ZipEntry::getName));

            Map<String, DefaultMutableTreeNode> dirNodes = new HashMap<>();

            for (ZipEntry entry : entryList) {
                String rawName = entry.getName();
                boolean isDir = entry.isDirectory() || rawName.endsWith("/");
                String name = rawName;
                if (name.endsWith("/")) {
                    name = name.substring(0, name.length() - 1);
                }
                if (name.isEmpty()) {
                    continue;
                }

                if (!isDir) {
                    totalFiles++;
                    if (name.endsWith(".class")) {
                        totalClasses++;
                    }
                }

                String[] parts = name.split("/");
                DefaultMutableTreeNode current = root;
                String currentPath = "";

                for (int i = 0; i < parts.length; i++) {
                    String part = parts[i];
                    currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
                    boolean partIsDir = isDir || (i < parts.length - 1);

                    if (partIsDir) {
                        DefaultMutableTreeNode dirNode = dirNodes.get(currentPath);
                        if (dirNode == null) {
                            dirNode = new DefaultMutableTreeNode(part);
                            dirNodes.put(currentPath, dirNode);
                            current.add(dirNode);
                        }
                        current = dirNode;
                    } else {
                        boolean exists = false;
                        for (int c = 0; c < current.getChildCount(); c++) {
                            if (current.getChildAt(c).toString().equals(part)) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            current.add(new DefaultMutableTreeNode(part));
                        }
                    }
                }
            }

            jarTree.setModel(new DefaultTreeModel(root));
            jarTreeInfoLabel.setText("В архиве: " + totalFiles + " файлов (" + totalClasses + " классов). JAR: " + jar.getName());
            for (int i = 0; i < Math.min(jarTree.getRowCount(), 30); i++) {
                jarTree.expandRow(i);
            }
        } catch (Exception e) {
            jarTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Ошибка чтения: " + e.getMessage())));
            jarTreeInfoLabel.setText("Ошибка чтения JAR: " + e.getMessage());
        }
    }

    private boolean isNodeExempted(DefaultMutableTreeNode node) {
        if (node == null || node == jarTree.getModel().getRoot()) {
            return false;
        }

        List<String> segs = getNodeSegments(node);
        if (segs.isEmpty()) {
            return false;
        }

        String slashPath = String.join("/", segs);

        if (exemptMixinsCheck.isSelected()) {
            String lower = slashPath.toLowerCase();
            if (lower.contains("/mixin/") || lower.contains("/mixins/") || lower.startsWith("mixin/") || lower.startsWith("mixins/")) {
                return true;
            }
        }

        String raw = exemptionsArea.getText();
        if (raw == null || raw.isBlank()) {
            return false;
        }

        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (matchesExemptionPattern(node, trimmed)) {
                return true;
            }
        }

        return false;
    }

    private boolean matchesExemptionPattern(DefaultMutableTreeNode node, String pattern) {
        if (node == null || pattern == null || pattern.isEmpty()) {
            return false;
        }

        List<String> segs = getNodeSegments(node);
        if (segs.isEmpty()) {
            return false;
        }

        String slashPath = String.join("/", segs);
        boolean isClass = node.isLeaf() && segs.get(segs.size() - 1).endsWith(".class");
        String classSlash = isClass ? slashPath.substring(0, slashPath.length() - 6) : "";
        String classDot = isClass ? classSlash.replace('/', '.') : "";
        String dirSlash = slashPath;
        String dirDot = slashPath.replace('/', '.');

        if (pattern.startsWith("\"") && pattern.endsWith("\"") && pattern.length() >= 2) {
            pattern = pattern.substring(1, pattern.length() - 1);
        }

        if (pattern.startsWith("class{") && pattern.endsWith("}")) {
            String rx = pattern.substring(6, pattern.length() - 1);
            try {
                Pattern p = Pattern.compile(rx);
                if (isClass) {
                    return p.matcher(classSlash).find() || p.matcher(slashPath).find();
                } else {
                    return p.matcher(slashPath + "/").find() || p.matcher(slashPath).find();
                }
            } catch (Exception ignored) {
                return false;
            }
        }

        String pSlash = pattern.replace('.', '/').trim();
        String pDot = pattern.replace('/', '.').trim();

        if (pSlash.endsWith("/*") || pDot.endsWith(".*")) {
            String prefixSlash = pSlash.substring(0, pSlash.length() - 1);
            String prefixDot = pDot.substring(0, pDot.length() - 1);

            if (isClass) {
                if (classSlash.startsWith(prefixSlash) || (classDot + ".").startsWith(prefixDot)) {
                    return true;
                }
            } else if (node.isLeaf()) {
                if (slashPath.startsWith(prefixSlash)) {
                    return true;
                }
            } else {
                if ((slashPath + "/").startsWith(prefixSlash) || (dirDot + ".").startsWith(prefixDot) || prefixSlash.startsWith(slashPath + "/")) {
                    return true;
                }
            }
        }

        if (isClass) {
            if (classDot.equalsIgnoreCase(pattern) || classSlash.equalsIgnoreCase(pSlash) || slashPath.equalsIgnoreCase(pattern)) {
                return true;
            }
            if (pSlash.endsWith("/") && classSlash.startsWith(pSlash)) {
                return true;
            }
            if (classSlash.startsWith(pSlash + "/")) {
                return true;
            }
        } else if (node.isLeaf()) {
            if (slashPath.equalsIgnoreCase(pattern) || slashPath.equalsIgnoreCase(pSlash)) {
                return true;
            }
            if (pSlash.endsWith("/") && slashPath.startsWith(pSlash)) {
                return true;
            }
            if (slashPath.startsWith(pSlash + "/")) {
                return true;
            }
        } else {
            if (dirDot.equalsIgnoreCase(pDot) || dirSlash.equalsIgnoreCase(pSlash)) {
                return true;
            }
            if (pSlash.equals(dirSlash + "/*") || pDot.equals(dirDot + ".*") || pSlash.equals(dirSlash + "/")) {
                return true;
            }
            if ((dirSlash + "/").startsWith(pSlash.endsWith("/") ? pSlash : pSlash + "/")) {
                return true;
            }
        }

        return false;
    }

    private boolean isExactMatch(DefaultMutableTreeNode node, String pattern) {
        if (pattern.startsWith("\"") && pattern.endsWith("\"") && pattern.length() >= 2) {
            pattern = pattern.substring(1, pattern.length() - 1);
        }
        List<String> segs = getNodeSegments(node);
        if (segs.isEmpty()) {
            return false;
        }
        String slashPath = String.join("/", segs);
        boolean isClass = node.isLeaf() && segs.get(segs.size() - 1).endsWith(".class");
        if (isClass) {
            String classSlash = slashPath.substring(0, slashPath.length() - 6);
            String classDot = classSlash.replace('/', '.');
            return classDot.equalsIgnoreCase(pattern) || classSlash.equalsIgnoreCase(pattern);
        }
        if (node.isLeaf()) {
            return slashPath.equalsIgnoreCase(pattern);
        }
        String dirSlash = slashPath;
        String dirDot = slashPath.replace('/', '.');
        return dirDot.equalsIgnoreCase(pattern) || dirSlash.equalsIgnoreCase(pattern)
                || (dirDot + ".*").equalsIgnoreCase(pattern) || (dirSlash + "/*").equalsIgnoreCase(pattern);
    }

    private void removeNodeFromExemptions(DefaultMutableTreeNode node) {
        if (node == null || node == jarTree.getModel().getRoot()) {
            return;
        }

        String current = exemptionsArea.getText();
        if (current == null || current.isBlank()) {
            return;
        }

        String[] lines = current.split("\n");
        List<String> remaining = new ArrayList<>();
        String removed = null;

        boolean hasExact = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (isExactMatch(node, trimmed)) {
                hasExact = true;
                break;
            }
        }

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                remaining.add(line);
                continue;
            }

            boolean shouldRemove;
            if (hasExact) {
                shouldRemove = isExactMatch(node, trimmed);
            } else {
                shouldRemove = matchesExemptionPattern(node, trimmed);
            }

            if (shouldRemove) {
                removed = trimmed;
            } else {
                remaining.add(line);
            }
        }

        if (removed != null) {
            exemptionsArea.setText(String.join("\n", remaining));
            log("[-] Удалено из исключений: " + removed);
            jarTree.repaint();
        }
    }

    private void addSelectedTreeNodesToExemptions(boolean entirePackage) {
        TreePath path = jarTree.getSelectionPath();
        if (path == null) {
            return;
        }

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node == jarTree.getModel().getRoot()) {
            return;
        }

        String target;
        List<String> segs = getNodeSegments(node);
        if (segs.isEmpty()) {
            return;
        }

        boolean isClass = node.isLeaf() && segs.get(segs.size() - 1).endsWith(".class");

        if (entirePackage) {
            if (node.isLeaf()) {
                if (segs.size() > 1) {
                    if (isClass) {
                        List<String> pkgSegs = new ArrayList<>(segs.subList(0, segs.size() - 1));
                        target = String.join(".", pkgSegs) + ".*";
                    } else {
                        List<String> dirSegs = new ArrayList<>(segs.subList(0, segs.size() - 1));
                        target = String.join("/", dirSegs) + "/*";
                    }
                } else {
                    target = isClass ? ".*" : "/*";
                }
            } else {
                String full = getNodeFullPath(node);
                if (full.contains("/")) {
                    target = full.endsWith("/*") ? full : full + "/*";
                } else {
                    target = full.endsWith(".*") ? full : full + ".*";
                }
            }
        } else {
            target = getNodeFullPath(node);
        }

        if (target.isEmpty()) {
            return;
        }

        String current = exemptionsArea.getText().trim();
        if (!containsExemption(current, target)) {
            if (!current.isEmpty()) {
                current += "\n";
            }
            current += target;
            exemptionsArea.setText(current);
            log("[+] Добавлено в исключения: " + target);
            jarTree.repaint();
        }
    }

    private boolean containsExemption(String text, String target) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String line : text.split("\n")) {
            if (line.trim().equalsIgnoreCase(target.trim())) {
                return true;
            }
        }
        return false;
    }

    private void copySelectedTreeNodeName() {
        TreePath path = jarTree.getSelectionPath();
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            String full = getNodeFullPath(node);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(full), null);
        }
    }

    private List<String> getNodeSegments(DefaultMutableTreeNode node) {
        TreeNode[] path = node.getPath();
        List<String> segs = new ArrayList<>();
        for (int i = 1; i < path.length; i++) {
            segs.add(path[i].toString());
        }
        return segs;
    }

    private String getNodeSlashPath(DefaultMutableTreeNode node) {
        List<String> segs = getNodeSegments(node);
        return String.join("/", segs);
    }

    private String getNodeDotPath(DefaultMutableTreeNode node) {
        List<String> segs = new ArrayList<>(getNodeSegments(node));
        if (segs.isEmpty()) {
            return "";
        }
        String last = segs.get(segs.size() - 1);
        if (last.endsWith(".class")) {
            segs.set(segs.size() - 1, last.substring(0, last.length() - 6));
        }
        return String.join(".", segs);
    }

    private String getNodeFullPath(DefaultMutableTreeNode node) {
        List<String> segs = getNodeSegments(node);
        if (segs.isEmpty()) {
            return "";
        }
        String last = segs.get(segs.size() - 1);
        if (last.endsWith(".class")) {
            return getNodeDotPath(node);
        }
        if (node.isLeaf()) {
            return getNodeSlashPath(node);
        }
        String top = segs.get(0);
        if (top.equals("assets") || top.equals("data") || top.equals("META-INF")) {
            return getNodeSlashPath(node);
        }
        return getNodeDotPath(node);
    }

    private final class JarTreeCellRenderer extends DefaultTreeCellRenderer {
        private boolean isCurrentExempt;
        private final Color defaultSelBg = getBackgroundSelectionColor();
        private final Color defaultNonSelBg = getBackgroundNonSelectionColor();
        private final Color defaultSelFg = getTextSelectionColor();
        private final Color defaultNonSelFg = getTextNonSelectionColor();

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            isCurrentExempt = false;
            if (value instanceof DefaultMutableTreeNode node && node != tree.getModel().getRoot()) {
                isCurrentExempt = isNodeExempted(node);
            }

            if (isCurrentExempt) {
                Color bg = sel ? new Color(155, 34, 34) : new Color(92, 22, 22);
                setBackgroundSelectionColor(bg);
                setBackgroundNonSelectionColor(bg);
                setTextSelectionColor(Color.WHITE);
                setTextNonSelectionColor(new Color(254, 202, 202));
                setForeground(sel ? Color.WHITE : new Color(254, 202, 202));
                setOpaque(true);
                setBackground(bg);
            } else {
                setBackgroundSelectionColor(defaultSelBg);
                setBackgroundNonSelectionColor(defaultNonSelBg);
                setTextSelectionColor(defaultSelFg);
                setTextNonSelectionColor(defaultNonSelFg);
                setOpaque(false);
                if (defaultNonSelBg != null) {
                    setBackground(sel ? defaultSelBg : defaultNonSelBg);
                }
                if (defaultNonSelFg != null) {
                    setForeground(sel ? defaultSelFg : defaultNonSelFg);
                }
            }

            return this;
        }

        @Override
        public void paint(Graphics g) {
            if (isCurrentExempt) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = selected ? new Color(155, 34, 34) : new Color(92, 22, 22);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 4, 4);
                g2.setColor(new Color(239, 68, 68, 160));
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 4, 4);
                g2.dispose();
            }
            super.paint(g);
        }
    }

    private JCheckBox makeMasterCheck(JCheckBox cb) {
        cb.setFont(safeFont(cb.getFont(), Font.BOLD, 13.5f));
        cb.setForeground(new Color(250, 250, 250));
        cb.setAlignmentX(Component.LEFT_ALIGNMENT);
        cb.setMaximumSize(new Dimension(Integer.MAX_VALUE, cb.getPreferredSize().height));
        return cb;
    }

    private JComponent createSeparator() {
        JSeparator s = new JSeparator();
        s.setForeground(new Color(55, 58, 66));
        s.setBackground(new Color(36, 38, 44));
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 2));
        s.setAlignmentX(Component.LEFT_ALIGNMENT);
        return s;
    }

    private void styleSpinner(JSpinner spinner, int width) {
        spinner.setPreferredSize(new Dimension(width, 30));
        spinner.setMinimumSize(new Dimension(width, 30));
        spinner.setFont(safeFont(null, Font.BOLD, 14f));
        JComponent editor = spinner.getEditor();
        if (editor instanceof JSpinner.DefaultEditor de) {
            JTextField tf = de.getTextField();
            tf.setFont(safeFont(null, Font.BOLD, 14f));
            tf.setHorizontalAlignment(JTextField.CENTER);
            tf.setForeground(new Color(250, 250, 250));
            tf.setCaretColor(Color.WHITE);
        }
    }

    private JPanel createFieldRow(String label, JComponent comp) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setFont(safeFont(null, Font.PLAIN, 13.5f));
        lbl.setPreferredSize(new Dimension(340, 28));
        row.add(lbl, BorderLayout.WEST);

        comp.setFont(safeFont(null, Font.PLAIN, 13f));
        comp.setPreferredSize(new Dimension(comp.getPreferredSize().width, 30));
        row.add(comp, BorderLayout.CENTER);
        return row;
    }

    private JPanel createNumberRow(String label, JSpinner spinner, String unit) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setFont(safeFont(null, Font.PLAIN, 13.5f));
        row.add(lbl, BorderLayout.CENTER);

        styleSpinner(spinner, 110);

        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightBox.setOpaque(false);
        rightBox.add(spinner);
        if (unit != null && !unit.isEmpty()) {
            JLabel unitLabel = new JLabel(unit);
            unitLabel.setFont(safeFont(null, Font.BOLD, 13f));
            unitLabel.setForeground(new Color(161, 161, 170));
            unitLabel.setPreferredSize(new Dimension(50, 26));
            rightBox.add(unitLabel);
        }

        row.add(rightBox, BorderLayout.EAST);
        return row;
    }

    private JPanel createPercentRow(String label, JSpinner spinner, int min, int max) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setFont(safeFont(null, Font.PLAIN, 13.5f));
        lbl.setPreferredSize(new Dimension(340, 28));
        row.add(lbl, BorderLayout.WEST);

        int curVal = ((Number) spinner.getValue()).intValue();
        curVal = Math.max(min, Math.min(max, curVal));
        JSlider slider = new JSlider(min, max, curVal);
        slider.setOpaque(false);
        slider.setFocusable(false);

        styleSpinner(spinner, 70);

        JLabel unitLabel = new JLabel("%");
        unitLabel.setFont(safeFont(null, Font.BOLD, 14f));
        unitLabel.setForeground(new Color(56, 189, 248));

        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightBox.setOpaque(false);
        rightBox.setPreferredSize(new Dimension(105, 30));
        rightBox.add(spinner);
        rightBox.add(unitLabel);

        final boolean[] updating = {false};

        slider.addChangeListener(e -> {
            if (!updating[0]) {
                updating[0] = true;
                spinner.setValue(slider.getValue());
                updating[0] = false;
            }
        });

        spinner.addChangeListener(e -> {
            if (!updating[0]) {
                updating[0] = true;
                int val = ((Number) spinner.getValue()).intValue();
                slider.setValue(Math.max(min, Math.min(max, val)));
                updating[0] = false;
            }
        });

        row.add(slider, BorderLayout.CENTER);
        row.add(rightBox, BorderLayout.EAST);
        return row;
    }

    private JPanel createLevelRow(String label, JSpinner spinner, int min, int max, String unit) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel lbl = new JLabel(label);
        lbl.setFont(safeFont(null, Font.PLAIN, 13.5f));
        lbl.setPreferredSize(new Dimension(340, 28));
        row.add(lbl, BorderLayout.WEST);

        int curVal = ((Number) spinner.getValue()).intValue();
        curVal = Math.max(min, Math.min(max, curVal));
        JSlider slider = new JSlider(min, max, curVal);
        slider.setOpaque(false);
        slider.setFocusable(false);
        slider.setMajorTickSpacing(1);
        slider.setSnapToTicks(true);
        slider.setPaintTicks(true);

        styleSpinner(spinner, 65);

        JLabel unitLabel = new JLabel(unit);
        unitLabel.setFont(safeFont(null, Font.BOLD, 13f));
        unitLabel.setForeground(new Color(161, 161, 170));

        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightBox.setOpaque(false);
        rightBox.setPreferredSize(new Dimension(135, 30));
        rightBox.add(spinner);
        rightBox.add(unitLabel);

        final boolean[] updating = {false};

        slider.addChangeListener(e -> {
            if (!updating[0]) {
                updating[0] = true;
                spinner.setValue(slider.getValue());
                updating[0] = false;
            }
        });

        spinner.addChangeListener(e -> {
            if (!updating[0]) {
                updating[0] = true;
                int val = ((Number) spinner.getValue()).intValue();
                slider.setValue(Math.max(min, Math.min(max, val)));
                updating[0] = false;
            }
        });

        row.add(slider, BorderLayout.CENTER);
        row.add(rightBox, BorderLayout.EAST);
        return row;
    }

    private void setHierarchyEnabled(Component comp, boolean enabled) {
        comp.setEnabled(enabled);
        if (comp instanceof Container cont) {
            for (Component child : cont.getComponents()) {
                setHierarchyEnabled(child, enabled);
            }
        }
    }

    private void bindToggle(JCheckBox master, JComponent... children) {
        Runnable update = () -> {
            boolean enabled = master.isSelected();
            for (JComponent c : children) {
                if (c != null) {
                    setHierarchyEnabled(c, enabled);
                }
            }
        };
        master.addActionListener(e -> update.run());
        toggleUpdaters.add(update);
        update.run();
    }

    private void updateAllToggles() {
        for (Runnable r : toggleUpdaters) {
            r.run();
        }
    }

    private static class ScrollableBoxPanel extends JPanel implements Scrollable {
        ScrollableBoxPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(new EmptyBorder(8, 8, 8, 8));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 64;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private JPanel createTabContent() {
        return new ScrollableBoxPanel();
    }

    private JScrollPane wrapInScroll(JPanel content) {
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JPanel createExemptionsPanel() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        JPanel rulesPanel = createSectionPanel("Быстрые правила исключений");
        rulesPanel.setLayout(new GridLayout(3, 2, 8, 8));
        rulesPanel.add(exemptMixinsCheck);
        rulesPanel.add(keepSignaturesCheck);
        rulesPanel.add(keepPublicMethodsCheck);
        rulesPanel.add(keepVirtualMethodsCheck);
        rulesPanel.add(allowIncompleteClasspathCheck);
        rulesPanel.add(allowMultiReleaseCheck);
        root.add(rulesPanel, BorderLayout.NORTH);

        JTabbedPane exTabs = new JTabbedPane();
        exTabs.setFont(safeFont(exTabs.getFont(), Font.BOLD, 13f));

        JPanel customPanel = new JPanel(new BorderLayout(6, 6));
        customPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        exemptionsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        exemptionsArea.setToolTipText("Примеры:\nru.myproject.gui\ncom.example.mod.security.*\ncom.example.api.PublicClass");
        customPanel.add(new JScrollPane(exemptionsArea), BorderLayout.CENTER);
        JLabel hintLabel = new JLabel("<html><font color='#a1a1aa'>Укажи пакеты или классы без обфускации (defaults.exempt). Например: <code>com.mycompany.api.*</code> или <code>class{^ru/project/}</code></font></html>");
        customPanel.add(hintLabel, BorderLayout.SOUTH);
        exTabs.addTab("Исключения (defaults.exempt)", customPanel);

        JPanel hotPanel = new JPanel(new BorderLayout(6, 6));
        hotPanel.setBorder(new EmptyBorder(8, 8, 8, 8));
        hotPathsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        hotPathsArea.setToolTipText("Паттерны методов или аннотаций, где обфускация должна работать без тяжелых замедлений (hotPaths.exclude)");
        hotPanel.add(new JScrollPane(hotPathsArea), BorderLayout.CENTER);
        JLabel hotHintLabel = new JLabel("<html><font color='#a1a1aa'>Горячие пути, исключаемые из тяжелых трансформаторов (hotPaths.exclude). Например: <code>annotation{(^|/)Hot(Path)?$}</code> или <code>annotation{org/spongepowered/asm/mixin/}</code></font></html>");
        hotPanel.add(hotHintLabel, BorderLayout.SOUTH);
        exTabs.addTab("Горячие пути (hotPaths.exclude)", hotPanel);

        exemptMixinsCheck.addActionListener(e -> jarTree.repaint());
        exemptionsArea.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { jarTree.repaint(); }
            public void removeUpdate(DocumentEvent e) { jarTree.repaint(); }
            public void changedUpdate(DocumentEvent e) { jarTree.repaint(); }
        });

        root.add(exTabs, BorderLayout.CENTER);
        return root;
    }

    private JPanel createBudgetsPanel() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        JPanel content = createTabContent();

        JPanel jarCard = createSectionPanel("Бюджет роста размера JAR (Jar Growth Budget)");
        jarCard.setLayout(new BoxLayout(jarCard, BoxLayout.Y_AXIS));
        jarCard.add(makeMasterCheck(budgetsEnabledCheck));
        jarCard.add(Box.createVerticalStrut(4));
        jarCard.add(createSeparator());
        jarCard.add(Box.createVerticalStrut(6));
        JPanel maxJarRow = createNumberRow("Максимальный допустимый рост JAR (%): (-1 для отключения)", maxJarGrowthPercentSpinner, "%");
        JPanel jarBytesRow = createNumberRow("Запас роста размера JAR в байтах:", jarGrowthBytesSpinner, "байт");
        jarCard.add(maxJarRow);
        jarCard.add(Box.createVerticalStrut(4));
        jarCard.add(jarBytesRow);
        content.add(jarCard);
        content.add(Box.createVerticalStrut(12));

        JPanel structCard = createSectionPanel("Бюджеты структуры байткода (Classes / Methods / Instructions)");
        structCard.setLayout(new BoxLayout(structCard, BoxLayout.Y_AXIS));
        JPanel maxClsRow = createNumberRow("Максимальный рост числа классов (%): (-1 для отключения)", maxClassGrowthPercentSpinner, "%");
        JPanel clsAllowRow = createNumberRow("Допустимый запас роста классов:", classGrowthAllowanceSpinner, "шт.");
        JPanel maxMtdRow = createNumberRow("Максимальный рост числа методов (%): (-1 для отключения)", maxMethodGrowthPercentSpinner, "%");
        JPanel mtdAllowRow = createNumberRow("Допустимый запас роста методов:", methodGrowthAllowanceSpinner, "шт.");
        JPanel maxInsnRow = createNumberRow("Максимальный рост инструкций байткода (%): (-1 для отключения)", maxInstructionGrowthPercentSpinner, "%");
        JPanel insnAllowRow = createNumberRow("Допустимый запас инструкций:", instructionGrowthAllowanceSpinner, "шт.");
        structCard.add(maxClsRow);
        structCard.add(Box.createVerticalStrut(4));
        structCard.add(clsAllowRow);
        structCard.add(Box.createVerticalStrut(6));
        structCard.add(createSeparator());
        structCard.add(Box.createVerticalStrut(6));
        structCard.add(maxMtdRow);
        structCard.add(Box.createVerticalStrut(4));
        structCard.add(mtdAllowRow);
        structCard.add(Box.createVerticalStrut(6));
        structCard.add(createSeparator());
        structCard.add(Box.createVerticalStrut(6));
        structCard.add(maxInsnRow);
        structCard.add(Box.createVerticalStrut(4));
        structCard.add(insnAllowRow);
        bindToggle(budgetsEnabledCheck, maxJarRow, jarBytesRow, maxClsRow, clsAllowRow, maxMtdRow, mtdAllowRow, maxInsnRow, insnAllowRow);
        content.add(structCard);
        content.add(Box.createVerticalStrut(12));

        JPanel covCard = createSectionPanel("Политика минимального покрытия (Coverage Policy)");
        covCard.setLayout(new BoxLayout(covCard, BoxLayout.Y_AXIS));
        covCard.add(makeMasterCheck(coverageEnabledCheck));
        covCard.add(Box.createVerticalStrut(4));
        covCard.add(createSeparator());
        covCard.add(Box.createVerticalStrut(6));
        JPanel covPctRow = createPercentRow("Минимальный процент модификации:", coverageMinPercentSpinner, 1, 100);
        JPanel covReqRow = createFieldRow("Обязательные селекторы (coverage.required):", coverageRequiredField);
        covCard.add(covPctRow);
        covCard.add(Box.createVerticalStrut(4));
        covCard.add(covReqRow);
        bindToggle(coverageEnabledCheck, covPctRow, covReqRow);
        content.add(covCard);
        content.add(Box.createVerticalStrut(12));

        JPanel infoCard = createSectionPanel("Справка по бюджетам");
        infoCard.setLayout(new BorderLayout(6, 6));
        JTextArea infoText = new JTextArea();
        infoText.setEditable(false);
        infoText.setFont(safeFont(null, Font.PLAIN, 12.5f));
        infoText.setBackground(root.getBackground());
        infoText.setText(
                "- Бюджеты роста гарантируют, что итоговый размер JAR или объем байткода не превысит лимиты.\n" +
                "- Значение -1 в процентах отключает относительную проверку (без ограничений на рост).\n" +
                "- Политика Coverage позволяет требовать, чтобы обфускация затронула не менее N% целевых методов."
        );
        infoCard.add(infoText, BorderLayout.CENTER);
        content.add(infoCard);

        root.add(wrapInScroll(content), BorderLayout.CENTER);
        return root;
    }

    private JPanel createTransformersPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBorder(new EmptyBorder(0, 0, 6, 0));
        JButton syncBtn = new JButton("Применить настройки пресета ко всем трансформерам");
        syncBtn.setToolTipText("Сбрасывает переключатели и параметры трансформеров к значениям выбранного пресета");
        syncBtn.setFont(safeFont(syncBtn.getFont(), Font.PLAIN, 12.5f));
        syncBtn.addActionListener(e -> applyPresetDefaults((String) presetCombo.getSelectedItem()));
        JLabel topHint = new JLabel("Тонкая настройка каждого трансформера Nullfuscator:");
        topHint.setFont(safeFont(null, Font.BOLD, 13f));
        topBar.add(topHint, BorderLayout.WEST);
        topBar.add(syncBtn, BorderLayout.EAST);
        root.add(topBar, BorderLayout.NORTH);

        JTabbedPane subTabs = new JTabbedPane();
        subTabs.setFont(safeFont(subTabs.getFont(), Font.BOLD, 13f));
        subTabs.addTab("Анализ и защита", createAnalysisAndSecurityTab());
        subTabs.addTab("Поток выполнения", createControlFlowTab());
        subTabs.addTab("Шифрование и ссылки", createEncryptionTab());
        subTabs.addTab("Распыление и семантика", createDispersionTab());
        subTabs.addTab("Имена и метаданные", createRenamingTab());

        root.add(subTabs, BorderLayout.CENTER);
        return root;
    }

    private JComponent createAnalysisAndSecurityTab() {
        JPanel content = createTabContent();

        JPanel aiCard = createSectionPanel("Защита от искусственного интеллекта (Anti-AI)");
        aiCard.setLayout(new BoxLayout(aiCard, BoxLayout.Y_AXIS));
        aiCard.add(makeMasterCheck(antiAiCheck));
        aiCard.add(Box.createVerticalStrut(4));
        aiCard.add(createSeparator());
        aiCard.add(Box.createVerticalStrut(6));
        JPanel aiLvlRow = createLevelRow("Уровень защиты (level 1-3):", antiAiLevelSpinner, 1, 3, "ур.");
        JPanel aiConstRow = createPercentRow("Процент кодирования констант:", antiAiConstPercentSpinner, 0, 100);
        JPanel aiDecoyRow = createNumberRow("Количество ложных методов-приманок:", antiAiDecoySpinner, "шт.");
        JPanel aiBranchRow = createPercentRow("Ложные ветвления (decoy branches):", antiAiBranchPercentSpinner, 0, 100);
        aiCard.add(aiLvlRow);
        aiCard.add(Box.createVerticalStrut(4));
        aiCard.add(aiConstRow);
        aiCard.add(Box.createVerticalStrut(4));
        aiCard.add(aiDecoyRow);
        aiCard.add(Box.createVerticalStrut(4));
        aiCard.add(aiBranchRow);
        bindToggle(antiAiCheck, aiLvlRow, aiConstRow, aiDecoyRow, aiBranchRow);
        content.add(aiCard);
        content.add(Box.createVerticalStrut(12));

        JPanel decompCard = createSectionPanel("Защита от декомпиляторов (Anti-Decompiler)");
        decompCard.setLayout(new BoxLayout(decompCard, BoxLayout.Y_AXIS));
        decompCard.add(makeMasterCheck(antiDecompilerCheck));
        decompCard.add(Box.createVerticalStrut(4));
        decompCard.add(createSeparator());
        decompCard.add(Box.createVerticalStrut(6));
        JPanel decompLvlRow = createLevelRow("Уровень атак на декомпиляторы (level 1-3):", antiDecompilerLevelSpinner, 1, 3, "ур.");
        decompCard.add(decompLvlRow);
        bindToggle(antiDecompilerCheck, decompLvlRow);
        content.add(decompCard);
        content.add(Box.createVerticalStrut(12));

        JPanel deobfCard = createSectionPanel("Защита от AST деобфускаторов (Anti-Deobf)");
        deobfCard.setLayout(new BoxLayout(deobfCard, BoxLayout.Y_AXIS));
        deobfCard.add(makeMasterCheck(antiDeobfCheck));
        deobfCard.add(Box.createVerticalStrut(4));
        deobfCard.add(createSeparator());
        deobfCard.add(Box.createVerticalStrut(6));
        JPanel deobfLvlRow = createLevelRow("Уровень разрушения AST (level 1-3):", antiDeobfLevelSpinner, 1, 3, "ур.");
        deobfCard.add(deobfLvlRow);
        deobfCard.add(Box.createVerticalStrut(6));
        deobfCard.add(createSeparator());
        deobfCard.add(Box.createVerticalStrut(6));
        JPanel deobfGrid = new JPanel(new GridLayout(3, 2, 8, 6));
        deobfGrid.setOpaque(false);
        deobfGrid.add(antiDeobfBadSigCheck);
        deobfGrid.add(antiDeobfBadClassSigCheck);
        deobfGrid.add(antiDeobfPoisonAnnoCheck);
        deobfGrid.add(antiDeobfPoisonInnerCheck);
        deobfGrid.add(antiDeobfTrapRealCheck);
        deobfGrid.add(antiDeobfHoneypotsCheck);
        deobfCard.add(deobfGrid);
        bindToggle(antiDeobfCheck, deobfLvlRow, deobfGrid);
        content.add(deobfCard);
        content.add(Box.createVerticalStrut(12));

        JPanel debugCard = createSectionPanel("Обнаружение отладчиков (Anti-Debug)");
        debugCard.setLayout(new BoxLayout(debugCard, BoxLayout.Y_AXIS));
        debugCard.add(makeMasterCheck(antiDebugCheck));
        debugCard.add(Box.createVerticalStrut(4));
        debugCard.add(createSeparator());
        debugCard.add(Box.createVerticalStrut(6));
        JPanel debugPctRow = createPercentRow("Процент защищаемых методов:", antiDebugCheckPercentSpinner, 0, 100);
        JPanel debugDetRow = createNumberRow("Количество детекторов отладки:", antiDebugDetectorsSpinner, "шт.");
        debugCard.add(debugPctRow);
        debugCard.add(Box.createVerticalStrut(4));
        debugCard.add(debugDetRow);
        bindToggle(antiDebugCheck, debugPctRow, debugDetRow);
        content.add(debugCard);
        content.add(Box.createVerticalStrut(12));

        JPanel crasherCard = createSectionPanel("Целостность и краш инструментов (Hardening & Crasher)");
        crasherCard.setLayout(new BoxLayout(crasherCard, BoxLayout.Y_AXIS));
        crasherCard.add(makeMasterCheck(fileCrasherCheck));
        crasherCard.add(Box.createVerticalStrut(6));
        crasherCard.add(createSeparator());
        crasherCard.add(Box.createVerticalStrut(6));
        crasherCard.add(makeMasterCheck(runtimeIntegrityCheck));
        crasherCard.add(Box.createVerticalStrut(4));
        JPanel integrityRow = createFieldRow("Классы для контроля целостности (include):", runtimeIntegrityIncludeField);
        crasherCard.add(integrityRow);
        bindToggle(runtimeIntegrityCheck, integrityRow);
        content.add(crasherCard);

        return wrapInScroll(content);
    }

    private JComponent createControlFlowTab() {
        JPanel content = createTabContent();

        JPanel flatCard = createSectionPanel("Сглаживание потока (Control Flow Flattening)");
        flatCard.setLayout(new BoxLayout(flatCard, BoxLayout.Y_AXIS));
        flatCard.add(makeMasterCheck(flowFlattenCheck));
        flatCard.add(Box.createVerticalStrut(4));
        flatCard.add(createSeparator());
        flatCard.add(Box.createVerticalStrut(6));
        JPanel flatPctRow = createPercentRow("Интенсивность сглаживания:", flowFlattenPercentSpinner, 0, 100);
        flatCard.add(flatPctRow);
        flatCard.add(Box.createVerticalStrut(4));
        flatCard.add(flowFlattenAllowRefLocalsCheck);
        bindToggle(flowFlattenCheck, flatPctRow, flowFlattenAllowRefLocalsCheck);
        content.add(flatCard);
        content.add(Box.createVerticalStrut(12));

        JPanel safeCard = createSectionPanel("Непрозрачные предикаты (Safe Control Flow)");
        safeCard.setLayout(new BoxLayout(safeCard, BoxLayout.Y_AXIS));
        safeCard.add(makeMasterCheck(safeControlFlowCheck));
        safeCard.add(Box.createVerticalStrut(4));
        safeCard.add(createSeparator());
        safeCard.add(Box.createVerticalStrut(6));
        JPanel safeLvlRow = createLevelRow("Уровень сложности предикатов (level 1-3):", safeControlFlowLevelSpinner, 1, 3, "ур.");
        safeCard.add(safeLvlRow);
        bindToggle(safeControlFlowCheck, safeLvlRow);
        content.add(safeCard);
        content.add(Box.createVerticalStrut(12));

        JPanel branchCard = createSectionPanel("Мутация переходов и ветвлений");
        branchCard.setLayout(new BoxLayout(branchCard, BoxLayout.Y_AXIS));
        branchCard.add(makeMasterCheck(mangledJumpCheck));
        branchCard.add(Box.createVerticalStrut(4));
        branchCard.add(createSeparator());
        branchCard.add(Box.createVerticalStrut(6));
        branchCard.add(makeMasterCheck(flowSwitchCheck));
        content.add(branchCard);
        content.add(Box.createVerticalStrut(12));

        JPanel retCard = createSectionPanel("Шифрование возврата значений");
        retCard.setLayout(new BoxLayout(retCard, BoxLayout.Y_AXIS));
        retCard.add(makeMasterCheck(integerReturnCheck));
        retCard.add(Box.createVerticalStrut(6));
        retCard.add(createSeparator());
        retCard.add(Box.createVerticalStrut(6));
        retCard.add(makeMasterCheck(exceptionReturnCheck));
        retCard.add(Box.createVerticalStrut(4));
        JPanel exRetRow = createPercentRow("Процент методов с Exception Return:", exceptionReturnPercentSpinner, 0, 100);
        retCard.add(exRetRow);
        bindToggle(exceptionReturnCheck, exRetRow);
        content.add(retCard);

        return wrapInScroll(content);
    }

    private JComponent createEncryptionTab() {
        JPanel content = createTabContent();

        JPanel strCard = createSectionPanel("Шифрование строк (String Encryption)");
        strCard.setLayout(new BoxLayout(strCard, BoxLayout.Y_AXIS));
        strCard.add(makeMasterCheck(stringEncCheck));
        strCard.add(Box.createVerticalStrut(4));
        strCard.add(createSeparator());
        strCard.add(Box.createVerticalStrut(6));
        JPanel strRow = createFieldRow("Тип шифрования строк:", stringEncTypeCombo);
        strCard.add(strRow);
        bindToggle(stringEncCheck, strRow);
        content.add(strCard);
        content.add(Box.createVerticalStrut(12));

        JPanel numCard = createSectionPanel("Шифрование констант и чисел (Number Encryption)");
        numCard.setLayout(new BoxLayout(numCard, BoxLayout.Y_AXIS));
        numCard.add(makeMasterCheck(numberEncCheck));
        numCard.add(Box.createVerticalStrut(4));
        numCard.add(createSeparator());
        numCard.add(Box.createVerticalStrut(6));
        JPanel numLayersRow = createLevelRow("Количество слоев математики (layers 1-5):", numberEncLayersSpinner, 1, 5, "слоев");
        numCard.add(numLayersRow);
        bindToggle(numberEncCheck, numLayersRow);
        content.add(numCard);
        content.add(Box.createVerticalStrut(12));

        JPanel indyCard = createSectionPanel("Скрытие вызовов через invokedynamic (Reference Hiding)");
        indyCard.setLayout(new BoxLayout(indyCard, BoxLayout.Y_AXIS));
        indyCard.add(makeMasterCheck(refHidingCheck));
        indyCard.add(Box.createVerticalStrut(4));
        indyCard.add(createSeparator());
        indyCard.add(Box.createVerticalStrut(6));
        JPanel refPctRow = createPercentRow("Процент скрываемых вызовов:", refHidingPercentSpinner, 0, 100);
        JPanel refPoolRow = createNumberRow("Размер пула bootstrap методов:", refHidingBootstrapPoolSpinner, "шт.");
        indyCard.add(refPctRow);
        indyCard.add(Box.createVerticalStrut(4));
        indyCard.add(refPoolRow);
        bindToggle(refHidingCheck, refPctRow, refPoolRow);
        content.add(indyCard);
        content.add(Box.createVerticalStrut(12));

        JPanel indirCard = createSectionPanel("Косвенный доступ к полям (Field Indirection)");
        indirCard.setLayout(new BoxLayout(indirCard, BoxLayout.Y_AXIS));
        indirCard.add(makeMasterCheck(fieldIndirectionCheck));
        indirCard.add(Box.createVerticalStrut(4));
        indirCard.add(createSeparator());
        indirCard.add(Box.createVerticalStrut(6));
        JPanel indirRow = createPercentRow("Процент косвенных доступов:", fieldIndirectionPercentSpinner, 0, 100);
        indirCard.add(indirRow);
        bindToggle(fieldIndirectionCheck, indirRow);
        content.add(indirCard);
        content.add(Box.createVerticalStrut(12));

        JPanel packCard = createSectionPanel("Упаковка полей (Field Packing)");
        packCard.setLayout(new BoxLayout(packCard, BoxLayout.Y_AXIS));
        packCard.add(makeMasterCheck(fieldPackingCheck));
        packCard.add(Box.createVerticalStrut(4));
        packCard.add(createSeparator());
        packCard.add(Box.createVerticalStrut(6));
        JPanel packRow = createNumberRow("Мин. число полей для упаковки:", fieldPackingMinFieldsSpinner, "полей");
        packCard.add(packRow);
        bindToggle(fieldPackingCheck, packRow);
        content.add(packCard);

        return wrapInScroll(content);
    }

    private JComponent createDispersionTab() {
        JPanel content = createTabContent();

        JPanel dispCard = createSectionPanel("Межклассовое распыление (Cross-Class Dispersion)");
        dispCard.setLayout(new BoxLayout(dispCard, BoxLayout.Y_AXIS));
        dispCard.add(makeMasterCheck(crossClassDispersionCheck));
        dispCard.add(Box.createVerticalStrut(4));
        dispCard.add(createSeparator());
        dispCard.add(Box.createVerticalStrut(6));
        JPanel dispPctRow = createPercentRow("Процент распыляемых операций:", dispersionPercentSpinner, 0, 100);
        JPanel dispSinksRow = createNumberRow("Количество классов-приемников (sinks):", dispersionSinksSpinner, "классов");
        JPanel dispVarRow = createNumberRow("Вариантов на опкод (variantsPerOpcode):", dispersionVariantsSpinner, "вар.");
        JPanel dispHelpRow = createNumberRow("Максимум хелперов (maxHelpers):", dispersionMaxHelpersSpinner, "шт.");
        JPanel dispOpsRow = createNumberRow("Максимум операций (maxOperations):", dispersionMaxOpsSpinner, "шт.");
        dispCard.add(dispPctRow);
        dispCard.add(Box.createVerticalStrut(4));
        dispCard.add(dispSinksRow);
        dispCard.add(Box.createVerticalStrut(4));
        dispCard.add(dispVarRow);
        dispCard.add(Box.createVerticalStrut(4));
        dispCard.add(dispHelpRow);
        dispCard.add(Box.createVerticalStrut(4));
        dispCard.add(dispOpsRow);
        bindToggle(crossClassDispersionCheck, dispPctRow, dispSinksRow, dispVarRow, dispHelpRow, dispOpsRow);
        content.add(dispCard);
        content.add(Box.createVerticalStrut(12));

        JPanel semCoreCard = createSectionPanel("Семантическое кодирование выражений (Semantic Core)");
        semCoreCard.setLayout(new BoxLayout(semCoreCard, BoxLayout.Y_AXIS));
        semCoreCard.add(makeMasterCheck(semanticCoreCheck));
        semCoreCard.add(Box.createVerticalStrut(4));
        semCoreCard.add(createSeparator());
        semCoreCard.add(Box.createVerticalStrut(6));
        JPanel semCorePctRow = createPercentRow("Процент покрытия:", semanticCorePercentSpinner, 0, 100);
        JPanel semCoreOpsRow = createNumberRow("Мин. операций в блоке (minOperations):", semanticCoreMinOpsSpinner, "оп.");
        JPanel semCoreMtdRow = createNumberRow("Максимум методов (maxMethods):", semanticCoreMaxMethodsSpinner, "шт.");
        semCoreCard.add(semCorePctRow);
        semCoreCard.add(Box.createVerticalStrut(4));
        semCoreCard.add(semCoreOpsRow);
        semCoreCard.add(Box.createVerticalStrut(4));
        semCoreCard.add(semCoreMtdRow);
        bindToggle(semanticCoreCheck, semCorePctRow, semCoreOpsRow, semCoreMtdRow);
        content.add(semCoreCard);
        content.add(Box.createVerticalStrut(12));

        JPanel semFabCard = createSectionPanel("Семантическая ткань (Semantic Fabric)");
        semFabCard.setLayout(new BoxLayout(semFabCard, BoxLayout.Y_AXIS));
        semFabCard.add(makeMasterCheck(semanticFabricCheck));
        semFabCard.add(Box.createVerticalStrut(4));
        semFabCard.add(createSeparator());
        semFabCard.add(Box.createVerticalStrut(6));
        JPanel semFabPctRow = createPercentRow("Процент покрытия:", semanticFabricPercentSpinner, 0, 100);
        JPanel semFabOpsRow = createNumberRow("Мин. арифметических операций:", semanticFabricMinOpsSpinner, "оп.");
        JPanel semFabMtdRow = createNumberRow("Максимум методов (maxMethods):", semanticFabricMaxMethodsSpinner, "шт.");
        JPanel semFabCarRow = createNumberRow("Классов-носителей (carriers):", semanticFabricCarriersSpinner, "классов");
        semFabCard.add(semFabPctRow);
        semFabCard.add(Box.createVerticalStrut(4));
        semFabCard.add(semFabOpsRow);
        semFabCard.add(Box.createVerticalStrut(4));
        semFabCard.add(semFabMtdRow);
        semFabCard.add(Box.createVerticalStrut(4));
        semFabCarRow.setOpaque(false);
        semFabCard.add(semFabCarRow);
        bindToggle(semanticFabricCheck, semFabPctRow, semFabOpsRow, semFabMtdRow, semFabCarRow);
        content.add(semFabCard);
        content.add(Box.createVerticalStrut(12));

        JPanel relocCard = createSectionPanel("Перемещение методов между классами (Method Relocation)");
        relocCard.setLayout(new BoxLayout(relocCard, BoxLayout.Y_AXIS));
        relocCard.add(makeMasterCheck(methodRelocationCheck));
        relocCard.add(Box.createVerticalStrut(4));
        relocCard.add(createSeparator());
        relocCard.add(Box.createVerticalStrut(6));
        JPanel relocPctRow = createPercentRow("Процент перемещаемых методов:", methodRelocPercentSpinner, 0, 100);
        JPanel relocMtdRow = createNumberRow("Максимум методов (maxMethods):", methodRelocMaxMethodsSpinner, "шт.");
        JPanel relocCarRow = createNumberRow("Классов-носителей (carriers):", methodRelocCarriersSpinner, "классов");
        JPanel relocInsnRow = createNumberRow("Мин. инструкций для переноса:", methodRelocMinInsnsSpinner, "инстр.");
        relocCard.add(relocPctRow);
        relocCard.add(Box.createVerticalStrut(4));
        relocCard.add(relocMtdRow);
        relocCard.add(Box.createVerticalStrut(4));
        relocCard.add(relocCarRow);
        relocCard.add(Box.createVerticalStrut(4));
        relocCard.add(relocInsnRow);
        bindToggle(methodRelocationCheck, relocPctRow, relocMtdRow, relocCarRow, relocInsnRow);
        content.add(relocCard);
        content.add(Box.createVerticalStrut(12));

        JPanel extractCard = createSectionPanel("Извлечение статических методов (Method Extraction)");
        extractCard.setLayout(new BoxLayout(extractCard, BoxLayout.Y_AXIS));
        extractCard.add(makeMasterCheck(methodExtractionCheck));
        extractCard.add(Box.createVerticalStrut(4));
        extractCard.add(createSeparator());
        extractCard.add(Box.createVerticalStrut(6));
        JPanel extractPctRow = createPercentRow("Процент извлекаемых методов:", methodExtractPercentSpinner, 0, 100);
        JPanel extractMtdRow = createNumberRow("Максимум методов (maxMethods):", methodExtractMaxMethodsSpinner, "шт.");
        JPanel extractCarRow = createNumberRow("Классов-носителей (carriers):", methodExtractCarriersSpinner, "классов");
        JPanel extractInsnRow = createNumberRow("Мин. инструкций для извлечения:", methodExtractMinInsnsSpinner, "инстр.");
        extractCard.add(extractPctRow);
        extractCard.add(Box.createVerticalStrut(4));
        extractCard.add(extractMtdRow);
        extractCard.add(Box.createVerticalStrut(4));
        extractCard.add(extractCarRow);
        extractCard.add(Box.createVerticalStrut(4));
        extractCard.add(extractInsnRow);
        bindToggle(methodExtractionCheck, extractPctRow, extractMtdRow, extractCarRow, extractInsnRow);
        content.add(extractCard);

        return wrapInScroll(content);
    }

    private JComponent createRenamingTab() {
        JPanel content = createTabContent();

        JPanel clsCard = createSectionPanel("Переименование классов (Class Renamer)");
        clsCard.setLayout(new BoxLayout(clsCard, BoxLayout.Y_AXIS));
        clsCard.add(makeMasterCheck(renameClassesCheck));
        clsCard.add(Box.createVerticalStrut(4));
        clsCard.add(createSeparator());
        clsCard.add(Box.createVerticalStrut(6));
        clsCard.add(renameMainCheck);
        clsCard.add(Box.createVerticalStrut(4));
        JPanel clsPrefixRow = createFieldRow("Префикс пакета (prefix):", classPrefixField);
        JPanel clsDepthRow = createNumberRow("Длина имен классов (depth):", classDepthSpinner, "симв.");
        clsCard.add(clsPrefixRow);
        clsCard.add(Box.createVerticalStrut(4));
        clsCard.add(clsDepthRow);
        bindToggle(renameClassesCheck, renameMainCheck, clsPrefixRow, clsDepthRow);
        content.add(clsCard);
        content.add(Box.createVerticalStrut(12));

        JPanel mtdCard = createSectionPanel("Переименование методов (Method Renamer)");
        mtdCard.setLayout(new BoxLayout(mtdCard, BoxLayout.Y_AXIS));
        mtdCard.add(makeMasterCheck(renameMethodsCheck));
        mtdCard.add(Box.createVerticalStrut(4));
        mtdCard.add(createSeparator());
        mtdCard.add(Box.createVerticalStrut(6));
        JPanel mtdRow = new JPanel(new GridLayout(1, 2, 8, 4));
        mtdRow.setOpaque(false);
        mtdRow.add(renameMethodsPublicCheck);
        mtdRow.add(renameMethodsVirtualCheck);
        mtdCard.add(mtdRow);
        mtdCard.add(Box.createVerticalStrut(4));
        JPanel mtdDepthRow = createNumberRow("Длина имен методов (depth):", methodDepthSpinner, "симв.");
        mtdCard.add(mtdDepthRow);
        bindToggle(renameMethodsCheck, mtdRow, mtdDepthRow);
        content.add(mtdCard);
        content.add(Box.createVerticalStrut(12));

        JPanel fldCard = createSectionPanel("Переименование полей (Field Renamer)");
        fldCard.setLayout(new BoxLayout(fldCard, BoxLayout.Y_AXIS));
        fldCard.add(makeMasterCheck(renameFieldsCheck));
        fldCard.add(Box.createVerticalStrut(4));
        fldCard.add(createSeparator());
        fldCard.add(Box.createVerticalStrut(6));
        JPanel fldDepthRow = createNumberRow("Длина имен полей (depth):", fieldDepthSpinner, "симв.");
        fldCard.add(fldDepthRow);
        bindToggle(renameFieldsCheck, fldDepthRow);
        content.add(fldCard);
        content.add(Box.createVerticalStrut(12));

        JPanel alphaCard = createSectionPanel("Алфавит переименования (Naming Alphabet)");
        alphaCard.setLayout(new BoxLayout(alphaCard, BoxLayout.Y_AXIS));
        alphaCard.add(createFieldRow("Символы имен (через запятую):", namingAlphabetField));
        content.add(alphaCard);
        content.add(Box.createVerticalStrut(12));

        JPanel recCard = createSectionPanel("Метаданные Records (Record Metadata)");
        recCard.setLayout(new BoxLayout(recCard, BoxLayout.Y_AXIS));
        recCard.add(makeMasterCheck(recordMetadataCheck));
        recCard.add(Box.createVerticalStrut(4));
        recCard.add(createSeparator());
        recCard.add(Box.createVerticalStrut(6));
        recCard.add(recordEraseIdentityCheck);
        recCard.add(Box.createVerticalStrut(4));
        JPanel recDepthRow = createNumberRow("Длина имен компонентов record (depth):", recordDepthSpinner, "симв.");
        recCard.add(recDepthRow);
        bindToggle(recordMetadataCheck, recordEraseIdentityCheck, recDepthRow);
        content.add(recCard);
        content.add(Box.createVerticalStrut(12));

        JPanel resCard = createSectionPanel("Переименование ресурсов (Resource Renamer)");
        resCard.setLayout(new BoxLayout(resCard, BoxLayout.Y_AXIS));
        resCard.add(makeMasterCheck(resourceRenamerCheck));
        resCard.add(Box.createVerticalStrut(4));
        resCard.add(createSeparator());
        resCard.add(Box.createVerticalStrut(6));
        JPanel resDepthRow = createNumberRow("Длина имен ресурсов (depth):", resourceDepthSpinner, "симв.");
        JPanel resNsRow = createFieldRow("Исключенные namespaces:", resourceExcludeNsField);
        JPanel resDirsRow = createFieldRow("Исключенные директории:", resourceExcludeDirsField);
        resCard.add(resDepthRow);
        resCard.add(Box.createVerticalStrut(4));
        resCard.add(resNsRow);
        resCard.add(Box.createVerticalStrut(4));
        resCard.add(resDirsRow);
        bindToggle(resourceRenamerCheck, resDepthRow, resNsRow, resDirsRow);
        content.add(resCard);
        content.add(Box.createVerticalStrut(12));

        JPanel stripCard = createSectionPanel("Очистка отладочной информации (Source Strip)");
        stripCard.setLayout(new BoxLayout(stripCard, BoxLayout.Y_AXIS));
        stripCard.add(makeMasterCheck(sourceStripCheck));
        stripCard.add(Box.createVerticalStrut(4));
        stripCard.add(createSeparator());
        stripCard.add(Box.createVerticalStrut(6));
        JPanel stripGrid = new JPanel(new GridLayout(2, 2, 8, 4));
        stripGrid.setOpaque(false);
        stripGrid.add(stripLineNumbersCheck);
        stripGrid.add(stripLocalVarsCheck);
        stripGrid.add(stripSignaturesCheck);
        stripCard.add(stripGrid);
        bindToggle(sourceStripCheck, stripGrid);
        content.add(stripCard);
        content.add(Box.createVerticalStrut(12));

        JPanel hardCard = createSectionPanel("Очистка метаданных сборщика (Release Hardening)");
        hardCard.setLayout(new BoxLayout(hardCard, BoxLayout.Y_AXIS));
        hardCard.add(makeMasterCheck(releaseHardeningCheck));
        hardCard.add(Box.createVerticalStrut(4));
        hardCard.add(createSeparator());
        hardCard.add(Box.createVerticalStrut(6));
        JPanel hardGrid = new JPanel(new GridLayout(1, 2, 8, 4));
        hardGrid.setOpaque(false);
        hardGrid.add(stripMavenCheck);
        hardGrid.add(stripFabricDescCheck);
        hardCard.add(hardGrid);
        bindToggle(releaseHardeningCheck, hardGrid);
        content.add(hardCard);
        content.add(Box.createVerticalStrut(12));

        JPanel annoCard = createSectionPanel("Удаление аннотаций (Annotation Sanitizer)");
        annoCard.setLayout(new BoxLayout(annoCard, BoxLayout.Y_AXIS));
        annoCard.add(makeMasterCheck(annotationSanitizerCheck));
        annoCard.add(Box.createVerticalStrut(4));
        annoCard.add(createSeparator());
        annoCard.add(Box.createVerticalStrut(6));
        JPanel annoRow = createFieldRow("Дескрипторы аннотаций:", annotationSanitizerDescriptorsField);
        annoCard.add(annoRow);
        bindToggle(annotationSanitizerCheck, annoRow);
        content.add(annoCard);
        content.add(Box.createVerticalStrut(12));

        JPanel mrCard = createSectionPanel("Совместимость с Multi-Release JAR");
        mrCard.setLayout(new BoxLayout(mrCard, BoxLayout.Y_AXIS));
        mrCard.add(makeMasterCheck(allowMultiReleaseCheck));
        content.add(mrCard);

        return wrapInScroll(content);
    }

    private JPanel createRetracePanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.setBorder(createTitledBorder("Файл ProGuard маппинга"));
        top.add(retraceMapField, BorderLayout.CENTER);
        JButton browseBtn = new JButton("Обзор...");
        browseBtn.addActionListener(e -> chooseRetraceMap());
        top.add(browseBtn, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        JPanel split = new JPanel(new GridLayout(1, 2, 8, 8));

        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.setBorder(createTitledBorder("Обфусцированный стектрейс"));
        retraceInArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        left.add(new JScrollPane(retraceInArea), BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout(4, 4));
        right.setBorder(createTitledBorder("Восстановленный результат"));
        retraceOutArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        retraceOutArea.setEditable(false);
        right.add(new JScrollPane(retraceOutArea), BorderLayout.CENTER);

        split.add(left);
        split.add(right);
        root.add(split, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton retraceBtn = new JButton("Восстановить стектрейс");
        retraceBtn.setFont(safeFont(retraceBtn.getFont(), Font.BOLD, 13f));
        retraceBtn.addActionListener(e -> runRetrace());

        JButton clearBtn = new JButton("Очистить");
        clearBtn.addActionListener(e -> {
            retraceInArea.setText("");
            retraceOutArea.setText("");
        });

        bottom.add(clearBtn);
        bottom.add(retraceBtn);
        root.add(bottom, BorderLayout.SOUTH);

        return root;
    }

    private JPanel createMappingInfoPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.setBorder(createTitledBorder("Файл ProGuard маппинга"));
        top.add(infoMapField, BorderLayout.CENTER);
        JButton browseBtn = new JButton("Обзор...");
        browseBtn.addActionListener(e -> chooseInfoMap());
        top.add(browseBtn, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.setBorder(createTitledBorder("Информация о сборке и маппинге"));
        infoOutArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        infoOutArea.setEditable(false);
        center.add(new JScrollPane(infoOutArea), BorderLayout.CENTER);
        root.add(center, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        JButton inspectBtn = new JButton("Анализ маппинга");
        inspectBtn.setFont(safeFont(inspectBtn.getFont(), Font.BOLD, 13f));
        inspectBtn.addActionListener(e -> runMappingInfo());
        bottom.add(inspectBtn);
        root.add(bottom, BorderLayout.SOUTH);

        return root;
    }

    private void chooseInputJar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JAR файлы (*.jar)", "jar"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            handleSelectedInputJar(chooser.getSelectedFile());
        }
    }

    private void chooseOutputJar() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JAR файлы (*.jar)", "jar"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            String path = f.getAbsolutePath();
            if (!path.toLowerCase().endsWith(".jar")) {
                path += ".jar";
            }
            outputField.setText(path);
        }
    }

    private void chooseConfigFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("HOCON конфигурации (*.hocon)", "hocon"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            customConfigField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseRetraceMap() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("ProGuard маппинг (*.map)", "map"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            retraceMapField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseInfoMap() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("ProGuard маппинг (*.map)", "map"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            infoMapField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void addLibraryFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("JAR файлы (*.jar)", "jar"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File file : chooser.getSelectedFiles()) {
                String path = file.getAbsolutePath();
                if (!libsModel.contains(path)) {
                    libsModel.addElement(path);
                }
            }
        }
    }

    private void removeSelectedLibs() {
        int[] indices = libsList.getSelectedIndices();
        for (int i = indices.length - 1; i >= 0; i--) {
            libsModel.remove(indices[i]);
        }
    }

    private void handleSelectedInputJar(File jar) {
        inputField.setText(jar.getAbsolutePath());
        outputField.setText(computeDefaultOutput(jar));
        detectTargetType(jar);
        refreshJarTree(jar);
    }

    private void detectTargetType(File jar) {
        if (!jar.isFile()) {
            return;
        }

        try (ZipFile z = new ZipFile(jar)) {
            if (z.getEntry("fabric.mod.json") != null) {
                profileCombo.setSelectedIndex(1);
                log("[*] Обнаружен fabric.mod.json: активирован профиль 'Fabric / Quilt мод'");
                if (libsModel.isEmpty()) {
                    runGradleScan();
                }
            } else if (z.getEntry("plugin.yml") != null) {
                profileCombo.setSelectedIndex(2);
                log("[*] Обнаружен plugin.yml: активирован профиль 'Spigot / Paper плагин'");
            }
        } catch (Exception ignored) {
        }
    }

    private void applyPresetDefaults(String preset) {
        if (preset == null) return;
        switch (preset.toLowerCase()) {
            case "light" -> {
                sourceStripCheck.setSelected(true);
                stripLineNumbersCheck.setSelected(false);
                stripLocalVarsCheck.setSelected(true);
                stripSignaturesCheck.setSelected(false);
                keepSignaturesCheck.setSelected(true);

                renameClassesCheck.setSelected(true);
                renameMainCheck.setSelected(false);
                classPrefixField.setText("a/");
                classDepthSpinner.setValue(5);

                renameMethodsCheck.setSelected(true);
                renameMethodsPublicCheck.setSelected(false);
                keepPublicMethodsCheck.setSelected(true);
                renameMethodsVirtualCheck.setSelected(false);
                keepVirtualMethodsCheck.setSelected(true);
                methodDepthSpinner.setValue(5);

                renameFieldsCheck.setSelected(true);
                fieldDepthSpinner.setValue(5);

                stringEncCheck.setSelected(true);
                stringEncTypeCombo.setSelectedItem("STANDARD");

                resourceRenamerCheck.setSelected(false);
                resourceDepthSpinner.setValue(5);

                releaseHardeningCheck.setSelected(true);
                stripMavenCheck.setSelected(true);
                stripFabricDescCheck.setSelected(true);

                numberEncCheck.setSelected(false);
                numberEncLayersSpinner.setValue(1);

                safeControlFlowCheck.setSelected(false);
                safeControlFlowLevelSpinner.setValue(1);

                flowFlattenCheck.setSelected(false);
                flowFlattenPercentSpinner.setValue(10);
                flowFlattenAllowRefLocalsCheck.setSelected(false);

                mangledJumpCheck.setSelected(false);
                flowSwitchCheck.setSelected(false);
                integerReturnCheck.setSelected(false);
                exceptionReturnCheck.setSelected(false);
                exceptionReturnPercentSpinner.setValue(10);

                antiAiCheck.setSelected(false);
                antiAiLevelSpinner.setValue(1);
                antiAiConstPercentSpinner.setValue(20);
                antiAiDecoySpinner.setValue(2);
                antiAiBranchPercentSpinner.setValue(15);

                antiDecompilerCheck.setSelected(false);
                antiDecompilerLevelSpinner.setValue(1);

                antiDeobfCheck.setSelected(false);
                antiDeobfLevelSpinner.setValue(1);
                antiDeobfBadSigCheck.setSelected(false);
                antiDeobfBadClassSigCheck.setSelected(false);
                antiDeobfPoisonAnnoCheck.setSelected(false);
                antiDeobfPoisonInnerCheck.setSelected(false);
                antiDeobfTrapRealCheck.setSelected(false);
                antiDeobfHoneypotsCheck.setSelected(false);

                antiDebugCheck.setSelected(false);
                antiDebugCheckPercentSpinner.setValue(10);
                antiDebugDetectorsSpinner.setValue(2);

                fileCrasherCheck.setSelected(false);
                runtimeIntegrityCheck.setSelected(false);

                refHidingCheck.setSelected(false);
                refHidingPercentSpinner.setValue(10);
                refHidingBootstrapPoolSpinner.setValue(4);

                fieldIndirectionCheck.setSelected(false);
                fieldIndirectionPercentSpinner.setValue(10);

                fieldPackingCheck.setSelected(false);
                fieldPackingMinFieldsSpinner.setValue(1);

                crossClassDispersionCheck.setSelected(false);
                dispersionPercentSpinner.setValue(10);

                semanticCoreCheck.setSelected(false);
                semanticCorePercentSpinner.setValue(10);

                semanticFabricCheck.setSelected(false);
                semanticFabricPercentSpinner.setValue(10);

                methodRelocationCheck.setSelected(false);
                methodRelocPercentSpinner.setValue(10);

                methodExtractionCheck.setSelected(false);
                methodExtractPercentSpinner.setValue(10);

                recordMetadataCheck.setSelected(false);
                recordEraseIdentityCheck.setSelected(false);
                recordDepthSpinner.setValue(5);

                budgetsEnabledCheck.setSelected(true);
                maxJarGrowthPercentSpinner.setValue(35);
                jarGrowthBytesSpinner.setValue(32768);
                maxClassGrowthPercentSpinner.setValue(0);
                classGrowthAllowanceSpinner.setValue(1);
                maxMethodGrowthPercentSpinner.setValue(5);
                methodGrowthAllowanceSpinner.setValue(8);
                maxInstructionGrowthPercentSpinner.setValue(30);
                instructionGrowthAllowanceSpinner.setValue(128);

                coverageEnabledCheck.setSelected(false);
            }
            case "balanced" -> {
                sourceStripCheck.setSelected(true);
                stripLineNumbersCheck.setSelected(true);
                stripLocalVarsCheck.setSelected(true);
                stripSignaturesCheck.setSelected(false);
                keepSignaturesCheck.setSelected(true);

                renameClassesCheck.setSelected(true);
                renameMainCheck.setSelected(false);
                classPrefixField.setText("a/");
                classDepthSpinner.setValue(6);

                renameMethodsCheck.setSelected(true);
                renameMethodsPublicCheck.setSelected(false);
                keepPublicMethodsCheck.setSelected(true);
                renameMethodsVirtualCheck.setSelected(false);
                keepVirtualMethodsCheck.setSelected(true);
                methodDepthSpinner.setValue(6);

                renameFieldsCheck.setSelected(true);
                fieldDepthSpinner.setValue(6);

                stringEncCheck.setSelected(true);
                stringEncTypeCombo.setSelectedItem("POLYMORPHIC");

                resourceRenamerCheck.setSelected(false);
                resourceDepthSpinner.setValue(6);

                releaseHardeningCheck.setSelected(true);
                stripMavenCheck.setSelected(true);
                stripFabricDescCheck.setSelected(true);

                numberEncCheck.setSelected(true);
                numberEncLayersSpinner.setValue(2);

                safeControlFlowCheck.setSelected(true);
                safeControlFlowLevelSpinner.setValue(1);

                crossClassDispersionCheck.setSelected(true);
                dispersionPercentSpinner.setValue(20);
                dispersionSinksSpinner.setValue(4);
                dispersionVariantsSpinner.setValue(1);
                dispersionMaxHelpersSpinner.setValue(96);
                dispersionMaxOpsSpinner.setValue(512);

                refHidingCheck.setSelected(true);
                refHidingPercentSpinner.setValue(15);
                refHidingBootstrapPoolSpinner.setValue(4);

                flowFlattenCheck.setSelected(false);
                flowFlattenPercentSpinner.setValue(25);
                flowFlattenAllowRefLocalsCheck.setSelected(false);

                mangledJumpCheck.setSelected(false);
                flowSwitchCheck.setSelected(false);
                integerReturnCheck.setSelected(false);
                exceptionReturnCheck.setSelected(false);
                exceptionReturnPercentSpinner.setValue(25);

                antiAiCheck.setSelected(false);
                antiAiLevelSpinner.setValue(1);
                antiAiConstPercentSpinner.setValue(35);
                antiAiDecoySpinner.setValue(3);
                antiAiBranchPercentSpinner.setValue(30);

                antiDecompilerCheck.setSelected(false);
                antiDecompilerLevelSpinner.setValue(1);

                antiDeobfCheck.setSelected(false);
                antiDeobfLevelSpinner.setValue(1);
                antiDeobfBadSigCheck.setSelected(false);
                antiDeobfBadClassSigCheck.setSelected(false);
                antiDeobfPoisonAnnoCheck.setSelected(false);
                antiDeobfPoisonInnerCheck.setSelected(false);
                antiDeobfTrapRealCheck.setSelected(false);
                antiDeobfHoneypotsCheck.setSelected(false);

                antiDebugCheck.setSelected(false);
                antiDebugCheckPercentSpinner.setValue(10);
                antiDebugDetectorsSpinner.setValue(3);

                fileCrasherCheck.setSelected(false);
                runtimeIntegrityCheck.setSelected(false);

                fieldIndirectionCheck.setSelected(false);
                fieldIndirectionPercentSpinner.setValue(20);

                fieldPackingCheck.setSelected(false);
                fieldPackingMinFieldsSpinner.setValue(1);

                semanticCoreCheck.setSelected(false);
                semanticCorePercentSpinner.setValue(25);

                semanticFabricCheck.setSelected(false);
                semanticFabricPercentSpinner.setValue(25);

                methodRelocationCheck.setSelected(false);
                methodRelocPercentSpinner.setValue(25);

                methodExtractionCheck.setSelected(false);
                methodExtractPercentSpinner.setValue(25);

                recordMetadataCheck.setSelected(false);
                recordEraseIdentityCheck.setSelected(false);
                recordDepthSpinner.setValue(6);

                budgetsEnabledCheck.setSelected(true);
                maxJarGrowthPercentSpinner.setValue(120);
                jarGrowthBytesSpinner.setValue(65536);
                maxClassGrowthPercentSpinner.setValue(10);
                classGrowthAllowanceSpinner.setValue(4);
                maxMethodGrowthPercentSpinner.setValue(35);
                methodGrowthAllowanceSpinner.setValue(64);
                maxInstructionGrowthPercentSpinner.setValue(150);
                instructionGrowthAllowanceSpinner.setValue(1024);

                coverageEnabledCheck.setSelected(false);
            }
            case "strong" -> {
                sourceStripCheck.setSelected(true);
                stripLineNumbersCheck.setSelected(true);
                stripLocalVarsCheck.setSelected(true);
                stripSignaturesCheck.setSelected(true);
                keepSignaturesCheck.setSelected(false);

                renameClassesCheck.setSelected(true);
                renameMainCheck.setSelected(true);
                classPrefixField.setText("a/");
                classDepthSpinner.setValue(8);

                renameMethodsCheck.setSelected(true);
                renameMethodsPublicCheck.setSelected(true);
                keepPublicMethodsCheck.setSelected(false);
                renameMethodsVirtualCheck.setSelected(true);
                keepVirtualMethodsCheck.setSelected(false);
                methodDepthSpinner.setValue(8);

                renameFieldsCheck.setSelected(true);
                fieldDepthSpinner.setValue(8);

                stringEncCheck.setSelected(true);
                stringEncTypeCombo.setSelectedItem("POLYMORPHIC");

                resourceRenamerCheck.setSelected(false);
                resourceDepthSpinner.setValue(8);

                releaseHardeningCheck.setSelected(true);
                stripMavenCheck.setSelected(true);
                stripFabricDescCheck.setSelected(true);

                numberEncCheck.setSelected(true);
                numberEncLayersSpinner.setValue(3);

                safeControlFlowCheck.setSelected(false);
                safeControlFlowLevelSpinner.setValue(2);

                flowFlattenCheck.setSelected(true);
                flowFlattenPercentSpinner.setValue(25);
                flowFlattenAllowRefLocalsCheck.setSelected(false);

                mangledJumpCheck.setSelected(false);
                flowSwitchCheck.setSelected(false);
                integerReturnCheck.setSelected(false);

                exceptionReturnCheck.setSelected(true);
                exceptionReturnPercentSpinner.setValue(25);

                antiAiCheck.setSelected(true);
                antiAiLevelSpinner.setValue(2);
                antiAiConstPercentSpinner.setValue(35);
                antiAiDecoySpinner.setValue(3);
                antiAiBranchPercentSpinner.setValue(30);

                antiDecompilerCheck.setSelected(true);
                antiDecompilerLevelSpinner.setValue(3);

                antiDeobfCheck.setSelected(true);
                antiDeobfLevelSpinner.setValue(3);
                antiDeobfBadSigCheck.setSelected(false);
                antiDeobfBadClassSigCheck.setSelected(false);
                antiDeobfPoisonAnnoCheck.setSelected(true);
                antiDeobfPoisonInnerCheck.setSelected(true);
                antiDeobfTrapRealCheck.setSelected(true);
                antiDeobfHoneypotsCheck.setSelected(true);

                antiDebugCheck.setSelected(true);
                antiDebugCheckPercentSpinner.setValue(10);
                antiDebugDetectorsSpinner.setValue(3);

                fileCrasherCheck.setSelected(true);
                runtimeIntegrityCheck.setSelected(false);

                refHidingCheck.setSelected(true);
                refHidingPercentSpinner.setValue(35);
                refHidingBootstrapPoolSpinner.setValue(8);

                fieldIndirectionCheck.setSelected(true);
                fieldIndirectionPercentSpinner.setValue(30);

                fieldPackingCheck.setSelected(true);
                fieldPackingMinFieldsSpinner.setValue(1);

                crossClassDispersionCheck.setSelected(true);
                dispersionPercentSpinner.setValue(30);
                dispersionSinksSpinner.setValue(8);
                dispersionVariantsSpinner.setValue(1);
                dispersionMaxHelpersSpinner.setValue(256);
                dispersionMaxOpsSpinner.setValue(2048);

                semanticCoreCheck.setSelected(true);
                semanticCorePercentSpinner.setValue(30);
                semanticCoreMinOpsSpinner.setValue(3);
                semanticCoreMaxMethodsSpinner.setValue(32);

                semanticFabricCheck.setSelected(true);
                semanticFabricPercentSpinner.setValue(30);
                semanticFabricMinOpsSpinner.setValue(3);
                semanticFabricMaxMethodsSpinner.setValue(48);
                semanticFabricCarriersSpinner.setValue(6);

                methodRelocationCheck.setSelected(true);
                methodRelocPercentSpinner.setValue(30);
                methodRelocMaxMethodsSpinner.setValue(48);
                methodRelocCarriersSpinner.setValue(8);
                methodRelocMinInsnsSpinner.setValue(20);

                methodExtractionCheck.setSelected(true);
                methodExtractPercentSpinner.setValue(30);
                methodExtractMaxMethodsSpinner.setValue(64);
                methodExtractCarriersSpinner.setValue(8);
                methodExtractMinInsnsSpinner.setValue(8);

                recordMetadataCheck.setSelected(true);
                recordEraseIdentityCheck.setSelected(true);
                recordDepthSpinner.setValue(8);

                budgetsEnabledCheck.setSelected(true);
                maxJarGrowthPercentSpinner.setValue(400);
                jarGrowthBytesSpinner.setValue(131072);
                maxClassGrowthPercentSpinner.setValue(20);
                classGrowthAllowanceSpinner.setValue(48);
                maxMethodGrowthPercentSpinner.setValue(150);
                methodGrowthAllowanceSpinner.setValue(1024);
                maxInstructionGrowthPercentSpinner.setValue(500);
                instructionGrowthAllowanceSpinner.setValue(30000);

                coverageEnabledCheck.setSelected(false);
            }
            case "full" -> {
                sourceStripCheck.setSelected(true);
                stripLineNumbersCheck.setSelected(true);
                stripLocalVarsCheck.setSelected(true);
                stripSignaturesCheck.setSelected(true);
                keepSignaturesCheck.setSelected(false);

                renameClassesCheck.setSelected(true);
                renameMainCheck.setSelected(true);
                classPrefixField.setText("a/");
                classDepthSpinner.setValue(8);

                renameMethodsCheck.setSelected(true);
                renameMethodsPublicCheck.setSelected(true);
                keepPublicMethodsCheck.setSelected(false);
                renameMethodsVirtualCheck.setSelected(true);
                keepVirtualMethodsCheck.setSelected(false);
                methodDepthSpinner.setValue(8);

                renameFieldsCheck.setSelected(true);
                fieldDepthSpinner.setValue(8);

                stringEncCheck.setSelected(true);
                stringEncTypeCombo.setSelectedItem("POLYMORPHIC");

                resourceRenamerCheck.setSelected(false);
                resourceDepthSpinner.setValue(8);

                releaseHardeningCheck.setSelected(true);
                stripMavenCheck.setSelected(true);
                stripFabricDescCheck.setSelected(true);

                numberEncCheck.setSelected(true);
                numberEncLayersSpinner.setValue(5);

                safeControlFlowCheck.setSelected(false);
                safeControlFlowLevelSpinner.setValue(3);

                flowFlattenCheck.setSelected(true);
                flowFlattenPercentSpinner.setValue(100);
                flowFlattenAllowRefLocalsCheck.setSelected(false);

                mangledJumpCheck.setSelected(false);
                flowSwitchCheck.setSelected(true);
                integerReturnCheck.setSelected(true);

                exceptionReturnCheck.setSelected(true);
                exceptionReturnPercentSpinner.setValue(100);

                antiAiCheck.setSelected(true);
                antiAiLevelSpinner.setValue(3);
                antiAiConstPercentSpinner.setValue(85);
                antiAiDecoySpinner.setValue(8);
                antiAiBranchPercentSpinner.setValue(45);

                antiDecompilerCheck.setSelected(true);
                antiDecompilerLevelSpinner.setValue(3);

                antiDeobfCheck.setSelected(true);
                antiDeobfLevelSpinner.setValue(3);
                antiDeobfBadSigCheck.setSelected(false);
                antiDeobfBadClassSigCheck.setSelected(false);
                antiDeobfPoisonAnnoCheck.setSelected(true);
                antiDeobfPoisonInnerCheck.setSelected(true);
                antiDeobfTrapRealCheck.setSelected(true);
                antiDeobfHoneypotsCheck.setSelected(true);

                antiDebugCheck.setSelected(true);
                antiDebugCheckPercentSpinner.setValue(50);
                antiDebugDetectorsSpinner.setValue(6);

                fileCrasherCheck.setSelected(true);
                runtimeIntegrityCheck.setSelected(false);

                refHidingCheck.setSelected(true);
                refHidingPercentSpinner.setValue(100);
                refHidingBootstrapPoolSpinner.setValue(8);

                fieldIndirectionCheck.setSelected(true);
                fieldIndirectionPercentSpinner.setValue(100);

                fieldPackingCheck.setSelected(true);
                fieldPackingMinFieldsSpinner.setValue(1);

                crossClassDispersionCheck.setSelected(true);
                dispersionPercentSpinner.setValue(75);
                dispersionSinksSpinner.setValue(24);
                dispersionVariantsSpinner.setValue(2);
                dispersionMaxHelpersSpinner.setValue(2048);
                dispersionMaxOpsSpinner.setValue(8192);

                semanticCoreCheck.setSelected(true);
                semanticCorePercentSpinner.setValue(45);
                semanticCoreMinOpsSpinner.setValue(3);
                semanticCoreMaxMethodsSpinner.setValue(64);

                semanticFabricCheck.setSelected(true);
                semanticFabricPercentSpinner.setValue(100);
                semanticFabricMinOpsSpinner.setValue(3);
                semanticFabricMaxMethodsSpinner.setValue(96);
                semanticFabricCarriersSpinner.setValue(12);

                methodRelocationCheck.setSelected(true);
                methodRelocPercentSpinner.setValue(85);
                methodRelocMaxMethodsSpinner.setValue(128);
                methodRelocCarriersSpinner.setValue(16);
                methodRelocMinInsnsSpinner.setValue(20);

                methodExtractionCheck.setSelected(true);
                methodExtractPercentSpinner.setValue(100);
                methodExtractMaxMethodsSpinner.setValue(160);
                methodExtractCarriersSpinner.setValue(16);
                methodExtractMinInsnsSpinner.setValue(8);

                recordMetadataCheck.setSelected(true);
                recordEraseIdentityCheck.setSelected(true);
                recordDepthSpinner.setValue(8);

                budgetsEnabledCheck.setSelected(false);
                maxJarGrowthPercentSpinner.setValue(-1);
                jarGrowthBytesSpinner.setValue(131072);
                maxClassGrowthPercentSpinner.setValue(20);
                classGrowthAllowanceSpinner.setValue(48);
                maxMethodGrowthPercentSpinner.setValue(150);
                methodGrowthAllowanceSpinner.setValue(1024);
                maxInstructionGrowthPercentSpinner.setValue(500);
                instructionGrowthAllowanceSpinner.setValue(30000);

                coverageEnabledCheck.setSelected(false);
            }
        }
        updateAllToggles();
    }

    private void applyProfileDefaults() {
        int index = profileCombo.getSelectedIndex();
        switch (index) {
            case 0 -> {
                resourceRenamerCheck.setSelected(false);
                allowIncompleteClasspathCheck.setSelected(true);
                keepSignaturesCheck.setSelected(false);
                exemptMixinsCheck.setSelected(false);
                keepPublicMethodsCheck.setSelected(false);
            }
            case 1 -> {
                resourceRenamerCheck.setSelected(false);
                allowIncompleteClasspathCheck.setSelected(true);
                keepSignaturesCheck.setSelected(true);
                exemptMixinsCheck.setSelected(true);
                keepPublicMethodsCheck.setSelected(false);
            }
            case 2 -> {
                resourceRenamerCheck.setSelected(false);
                allowIncompleteClasspathCheck.setSelected(true);
                keepSignaturesCheck.setSelected(true);
                exemptMixinsCheck.setSelected(false);
                keepPublicMethodsCheck.setSelected(true);
            }
            default -> {}
        }
    }

    private void applyFoundLibraries(List<File> found, String sourceName) {
        int added = 0;
        for (File f : found) {
            String path = f.getAbsolutePath();
            if (!libsModel.contains(path)) {
                libsModel.addElement(path);
                added++;
            }
        }
        if (added > 0) {
            log("[+] Добавлено " + added + " библиотек из " + sourceName);
        } else if (!found.isEmpty()) {
            log("[*] Все найденные библиотеки уже есть в списке (" + found.size() + ")");
        } else {
            log("[!] В " + sourceName + " не найдено библиотек");
        }
    }

    private void runGradleScan() {
        log("[*] Поиск библиотек в ~/.gradle/caches...");
        applyFoundLibraries(GradleScanner.scanGradleLibraries(), "Gradle cache");
    }

    private void runMavenScan() {
        log("[*] Поиск библиотек в ~/.m2/repository...");
        applyFoundLibraries(GradleScanner.scanMavenLibraries(), "Maven repository");
    }

    private void runCustomFolderScan() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Выберите папку или JAR-файлы с библиотеками");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("JAR файлы или папки", "jar"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File[] selected = chooser.getSelectedFiles();
            if (selected == null || selected.length == 0) {
                File single = chooser.getSelectedFile();
                if (single != null) {
                    selected = new File[]{single};
                }
            }
            if (selected != null && selected.length > 0) {
                List<File> found = new ArrayList<>();
                for (File file : selected) {
                    found.addAll(GradleScanner.scanDirectory(file));
                }
                applyFoundLibraries(found, "выбранных файлов/папок");
            }
        }
    }

    private void showHoconPreview() {
        String preset = (String) presetCombo.getSelectedItem();
        String hocon = buildHoconConfig(preset);

        JDialog dialog = new JDialog(this, "Предпросмотр HOCON конфигурации (" + preset + ")", true);
        dialog.setSize(680, 540);
        dialog.setLocationRelativeTo(this);

        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(new EmptyBorder(10, 10, 10, 10));

        JTextArea area = new JTextArea(hocon);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setEditable(false);
        p.add(new JScrollPane(area), BorderLayout.CENTER);

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton copyBtn = new JButton("Скопировать в буфер");
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(hocon), null);
            JOptionPane.showMessageDialog(dialog, "Конфигурация скопирована в буфер обмена!");
        });

        JButton saveBtn = new JButton("Сохранить как...");
        saveBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("nullfuscator-" + preset + ".hocon"));
            if (chooser.showSaveDialog(dialog) == JFileChooser.APPROVE_OPTION) {
                try {
                    Files.writeString(chooser.getSelectedFile().toPath(), hocon, StandardCharsets.UTF_8);
                    JOptionPane.showMessageDialog(dialog, "Конфиг успешно сохранён!");
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(dialog, "Ошибка сохранения: " + ex.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        JButton closeBtn = new JButton("Закрыть");
        closeBtn.addActionListener(e -> dialog.dispose());

        bar.add(copyBtn);
        bar.add(saveBtn);
        bar.add(closeBtn);
        p.add(bar, BorderLayout.SOUTH);

        dialog.setContentPane(p);
        dialog.setVisible(true);
    }

    private static List<String> parseCommaOrLineSeparated(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.isBlank()) return list;
        for (String part : raw.split("[,\\n\\r]+")) {
            String s = part.trim();
            if (!s.isEmpty() && !s.startsWith("#")) {
                list.add(s);
            }
        }
        return list;
    }

    private static String joinQuotedHocon(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            sb.append("\"").append(escapeHocon(list.get(i))).append("\"");
            if (i < list.size() - 1) sb.append(", ");
        }
        return sb.toString();
    }

    private static String escapeHocon(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildHoconConfig(String basePreset) {
        StringBuilder sb = new StringBuilder();
        sb.append("baseConfig = \"").append(basePreset).append(".hocon\"\n\n");

        sb.append("compatibility {\n");
        sb.append("  allowMultiReleaseRename: ").append(allowMultiReleaseCheck.isSelected()).append("\n");
        sb.append("  allowIncompleteClasspath: ").append(allowIncompleteClasspathCheck.isSelected()).append("\n");
        sb.append("}\n\n");

        if (budgetsEnabledCheck.isSelected()) {
            sb.append("budgets {\n");
            sb.append("  maxJarGrowthPercent: ").append(maxJarGrowthPercentSpinner.getValue()).append("\n");
            sb.append("  jarGrowthAllowanceBytes: ").append(jarGrowthBytesSpinner.getValue()).append("\n");
            sb.append("  maxClassGrowthPercent: ").append(maxClassGrowthPercentSpinner.getValue()).append("\n");
            sb.append("  classGrowthAllowance: ").append(classGrowthAllowanceSpinner.getValue()).append("\n");
            sb.append("  maxMethodGrowthPercent: ").append(maxMethodGrowthPercentSpinner.getValue()).append("\n");
            sb.append("  methodGrowthAllowance: ").append(methodGrowthAllowanceSpinner.getValue()).append("\n");
            sb.append("  maxInstructionGrowthPercent: ").append(maxInstructionGrowthPercentSpinner.getValue()).append("\n");
            sb.append("  instructionGrowthAllowance: ").append(instructionGrowthAllowanceSpinner.getValue()).append("\n");
            sb.append("}\n\n");
        } else {
            sb.append("budgets {\n");
            sb.append("  maxJarGrowthPercent: -1\n");
            sb.append("  maxClassGrowthPercent: -1\n");
            sb.append("  maxMethodGrowthPercent: -1\n");
            sb.append("  maxInstructionGrowthPercent: -1\n");
            sb.append("}\n\n");
        }

        if (coverageEnabledCheck.isSelected()) {
            sb.append("coverage {\n");
            sb.append("  minPercent: ").append(coverageMinPercentSpinner.getValue()).append("\n");
            List<String> req = parseCommaOrLineSeparated(coverageRequiredField.getText());
            if (!req.isEmpty()) {
                sb.append("  required: [ ").append(joinQuotedHocon(req)).append(" ]\n");
            }
            sb.append("}\n\n");
        }

        List<String> hotPaths = parseCommaOrLineSeparated(hotPathsArea.getText());
        if (!hotPaths.isEmpty()) {
            sb.append("hotPaths.exclude = [\n");
            for (int i = 0; i < hotPaths.size(); i++) {
                sb.append("  \"").append(escapeHocon(hotPaths.get(i))).append("\"");
                if (i < hotPaths.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("]\n\n");
        }

        List<String> alphabet = parseCommaOrLineSeparated(namingAlphabetField.getText());
        if (!alphabet.isEmpty()) {
            sb.append("defaults.naming {\n");
            sb.append("  chars: [ ").append(joinQuotedHocon(alphabet)).append(" ]\n");
            sb.append("}\n\n");
        }

        sb.append("sourceStrip {\n");
        sb.append("  enabled: ").append(sourceStripCheck.isSelected()).append("\n");
        sb.append("  stripLineNumbers: ").append(stripLineNumbersCheck.isSelected()).append("\n");
        sb.append("  stripLocalVars: ").append(stripLocalVarsCheck.isSelected()).append("\n");
        sb.append("  stripSignatures: ").append(stripSignaturesCheck.isSelected()).append("\n");
        sb.append("}\n\n");

        sb.append("releaseHardening {\n");
        sb.append("  enabled: ").append(releaseHardeningCheck.isSelected()).append("\n");
        sb.append("  stripMavenMetadata: ").append(stripMavenCheck.isSelected()).append("\n");
        sb.append("  stripFabricDescription: ").append(stripFabricDescCheck.isSelected()).append("\n");
        sb.append("}\n\n");

        sb.append("stringEncryption {\n");
        sb.append("  enabled: ").append(stringEncCheck.isSelected()).append("\n");
        sb.append("  type: ").append(stringEncTypeCombo.getSelectedItem()).append("\n");
        sb.append("}\n\n");

        sb.append("numberEncryption {\n");
        sb.append("  enabled: ").append(numberEncCheck.isSelected()).append("\n");
        sb.append("  layers: ").append(numberEncLayersSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("safeControlFlow {\n");
        sb.append("  enabled: ").append(safeControlFlowCheck.isSelected()).append("\n");
        sb.append("  level: ").append(safeControlFlowLevelSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("flatten {\n");
        sb.append("  enabled: ").append(flowFlattenCheck.isSelected()).append("\n");
        sb.append("  percent: ").append(flowFlattenPercentSpinner.getValue()).append("\n");
        sb.append("  allowReferenceLocals: ").append(flowFlattenAllowRefLocalsCheck.isSelected()).append("\n");
        sb.append("}\n\n");

        sb.append("mangledJump.enabled = ").append(mangledJumpCheck.isSelected()).append("\n");
        sb.append("flowSwitch.enabled = ").append(flowSwitchCheck.isSelected()).append("\n");
        sb.append("integerReturn.enabled = ").append(integerReturnCheck.isSelected()).append("\n");

        sb.append("exceptionReturn {\n");
        sb.append("  enabled: ").append(exceptionReturnCheck.isSelected()).append("\n");
        sb.append("  percent: ").append(exceptionReturnPercentSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("antiAI {\n");
        sb.append("  enabled: ").append(antiAiCheck.isSelected()).append("\n");
        sb.append("  level: ").append(antiAiLevelSpinner.getValue()).append("\n");
        sb.append("  decoyMethods: ").append(antiAiDecoySpinner.getValue()).append("\n");
        sb.append("  constantEncodingPercent: ").append(antiAiConstPercentSpinner.getValue()).append("\n");
        sb.append("  decoyBranchPercent: ").append(antiAiBranchPercentSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("antiDecompiler {\n");
        sb.append("  enabled: ").append(antiDecompilerCheck.isSelected()).append("\n");
        sb.append("  level: ").append(antiDecompilerLevelSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("antiDeobf {\n");
        sb.append("  enabled: ").append(antiDeobfCheck.isSelected()).append("\n");
        sb.append("  level: ").append(antiDeobfLevelSpinner.getValue()).append("\n");
        sb.append("  badSignatures: ").append(antiDeobfBadSigCheck.isSelected()).append("\n");
        sb.append("  badRealClassSignatures: ").append(antiDeobfBadClassSigCheck.isSelected()).append("\n");
        sb.append("  poisonAnnotations: ").append(antiDeobfPoisonAnnoCheck.isSelected()).append("\n");
        sb.append("  poisonInnerClasses: ").append(antiDeobfPoisonInnerCheck.isSelected()).append("\n");
        sb.append("  trapRealMethods: ").append(antiDeobfTrapRealCheck.isSelected()).append("\n");
        sb.append("  honeypotEvaluators: ").append(antiDeobfHoneypotsCheck.isSelected()).append("\n");
        sb.append("}\n\n");

        sb.append("antiDebug {\n");
        sb.append("  enabled: ").append(antiDebugCheck.isSelected()).append("\n");
        sb.append("  checkPercent: ").append(antiDebugCheckPercentSpinner.getValue()).append("\n");
        sb.append("  detectors: ").append(antiDebugDetectorsSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("fileCrasher.enabled = ").append(fileCrasherCheck.isSelected()).append("\n");

        if (runtimeIntegrityCheck.isSelected()) {
            sb.append("runtimeIntegrity {\n");
            sb.append("  enabled: true\n");
            List<String> inc = parseCommaOrLineSeparated(runtimeIntegrityIncludeField.getText());
            if (!inc.isEmpty()) {
                sb.append("  include: [ ").append(joinQuotedHocon(inc)).append(" ]\n");
            }
            sb.append("}\n\n");
        } else {
            sb.append("runtimeIntegrity.enabled = false\n\n");
        }

        sb.append("classRenamer {\n");
        sb.append("  enabled: ").append(renameClassesCheck.isSelected()).append("\n");
        sb.append("  renameMain: ").append(renameMainCheck.isSelected()).append("\n");
        sb.append("  prefix: \"").append(escapeHocon(classPrefixField.getText().trim())).append("\"\n");
        sb.append("  depth: ").append(classDepthSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("methodRenamer {\n");
        sb.append("  enabled: ").append(renameMethodsCheck.isSelected()).append("\n");
        sb.append("  renamePublic: ").append(renameMethodsPublicCheck.isSelected()).append("\n");
        sb.append("  renameVirtual: ").append(renameMethodsVirtualCheck.isSelected()).append("\n");
        sb.append("  depth: ").append(methodDepthSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("fieldRenamer {\n");
        sb.append("  enabled: ").append(renameFieldsCheck.isSelected()).append("\n");
        sb.append("  depth: ").append(fieldDepthSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("recordMetadata {\n");
        sb.append("  enabled: ").append(recordMetadataCheck.isSelected()).append("\n");
        sb.append("  eraseIdentity: ").append(recordEraseIdentityCheck.isSelected()).append("\n");
        sb.append("  depth: ").append(recordDepthSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("resourceRenamer {\n");
        sb.append("  enabled: ").append(resourceRenamerCheck.isSelected()).append("\n");
        sb.append("  depth: ").append(resourceDepthSpinner.getValue()).append("\n");
        List<String> resNs = parseCommaOrLineSeparated(resourceExcludeNsField.getText());
        if (!resNs.isEmpty()) {
            sb.append("  excludeNamespaces: [ ").append(joinQuotedHocon(resNs)).append(" ]\n");
        }
        List<String> resDirs = parseCommaOrLineSeparated(resourceExcludeDirsField.getText());
        if (!resDirs.isEmpty()) {
            sb.append("  excludeDirectories: [ ").append(joinQuotedHocon(resDirs)).append(" ]\n");
        }
        sb.append("}\n\n");

        sb.append("fieldPacking {\n");
        sb.append("  enabled: ").append(fieldPackingCheck.isSelected()).append("\n");
        sb.append("  minFields: ").append(fieldPackingMinFieldsSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("fieldIndirection {\n");
        sb.append("  enabled: ").append(fieldIndirectionCheck.isSelected()).append("\n");
        sb.append("  percent: ").append(fieldIndirectionPercentSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("referenceHiding {\n");
        sb.append("  enabled: ").append(refHidingCheck.isSelected()).append("\n");
        sb.append("  hidePercent: ").append(refHidingPercentSpinner.getValue()).append("\n");
        sb.append("  bootstrapPoolSize: ").append(refHidingBootstrapPoolSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("crossClassDispersion {\n");
        sb.append("  enabled: ").append(crossClassDispersionCheck.isSelected()).append("\n");
        sb.append("  dispersalPercent: ").append(dispersionPercentSpinner.getValue()).append("\n");
        sb.append("  sinks: ").append(dispersionSinksSpinner.getValue()).append("\n");
        sb.append("  variantsPerOpcode: ").append(dispersionVariantsSpinner.getValue()).append("\n");
        sb.append("  maxHelpers: ").append(dispersionMaxHelpersSpinner.getValue()).append("\n");
        sb.append("  maxOperations: ").append(dispersionMaxOpsSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("semanticCore {\n");
        sb.append("  enabled: ").append(semanticCoreCheck.isSelected()).append("\n");
        sb.append("  percent: ").append(semanticCorePercentSpinner.getValue()).append("\n");
        sb.append("  minOperations: ").append(semanticCoreMinOpsSpinner.getValue()).append("\n");
        sb.append("  maxMethods: ").append(semanticCoreMaxMethodsSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("semanticFabric {\n");
        sb.append("  enabled: ").append(semanticFabricCheck.isSelected()).append("\n");
        sb.append("  percent: ").append(semanticFabricPercentSpinner.getValue()).append("\n");
        sb.append("  minArithmeticOps: ").append(semanticFabricMinOpsSpinner.getValue()).append("\n");
        sb.append("  maxMethods: ").append(semanticFabricMaxMethodsSpinner.getValue()).append("\n");
        sb.append("  carriers: ").append(semanticFabricCarriersSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("methodRelocation {\n");
        sb.append("  enabled: ").append(methodRelocationCheck.isSelected()).append("\n");
        sb.append("  percent: ").append(methodRelocPercentSpinner.getValue()).append("\n");
        sb.append("  maxMethods: ").append(methodRelocMaxMethodsSpinner.getValue()).append("\n");
        sb.append("  carriers: ").append(methodRelocCarriersSpinner.getValue()).append("\n");
        sb.append("  minInstructions: ").append(methodRelocMinInsnsSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        sb.append("methodExtraction {\n");
        sb.append("  enabled: ").append(methodExtractionCheck.isSelected()).append("\n");
        sb.append("  percent: ").append(methodExtractPercentSpinner.getValue()).append("\n");
        sb.append("  maxMethods: ").append(methodExtractMaxMethodsSpinner.getValue()).append("\n");
        sb.append("  carriers: ").append(methodExtractCarriersSpinner.getValue()).append("\n");
        sb.append("  minInstructions: ").append(methodExtractMinInsnsSpinner.getValue()).append("\n");
        sb.append("}\n\n");

        if (annotationSanitizerCheck.isSelected()) {
            sb.append("annotationSanitizer {\n");
            sb.append("  enabled: true\n");
            List<String> descs = parseCommaOrLineSeparated(annotationSanitizerDescriptorsField.getText());
            if (!descs.isEmpty()) {
                sb.append("  descriptors: [ ").append(joinQuotedHocon(descs)).append(" ]\n");
            }
            sb.append("}\n\n");
        } else {
            sb.append("annotationSanitizer.enabled = false\n\n");
        }

        List<String> exemptions = new ArrayList<>();
        if (exemptMixinsCheck.isSelected()) {
            exemptions.add("\"class{^.*/mixin/}\"");
            exemptions.add("\"class{^.*/mixins/}\"");
        }

        String raw = exemptionsArea.getText();
        if (raw != null && !raw.isBlank()) {
            for (String line : raw.split("\n")) {
                String item = line.trim();
                if (item.isEmpty() || item.startsWith("#")) {
                    continue;
                }
                String converted = convertUserExemptionToPattern(item);
                if (converted != null) {
                    exemptions.add("\"" + converted + "\"");
                }
            }
        }

        if (!exemptions.isEmpty()) {
            sb.append("defaults.exempt = [\n");
            for (int i = 0; i < exemptions.size(); i++) {
                sb.append("  ").append(exemptions.get(i));
                if (i < exemptions.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("]\n");
        }

        return sb.toString();
    }

    private String convertUserExemptionToPattern(String item) {
        if (item.startsWith("class{") || item.startsWith("method{") || item.startsWith("field{")) {
            return item;
        }

        String clean = item.replace('.', '/');
        if (clean.endsWith("/*") || clean.endsWith(".*")) {
            String pkg = clean.substring(0, clean.length() - 2);
            return "class{^" + pkg + "/}";
        }
        if (clean.endsWith("/")) {
            return "class{^" + clean + "}";
        }
        if (clean.contains("/")) {
            return "class{^" + clean + "($|/)}";
        }
        return "class{^" + clean + "$}";
    }

    private void revealInExplorer(File file) {
        if (file == null) {
            return;
        }
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                if (file.exists()) {
                    new ProcessBuilder("explorer.exe", "/select,", file.getAbsolutePath()).start();
                } else if (file.getParentFile() != null && file.getParentFile().exists()) {
                    new ProcessBuilder("explorer.exe", file.getParentFile().getAbsolutePath()).start();
                }
            } else if (Desktop.isDesktopSupported()) {
                File dir = file.isDirectory() ? file : file.getParentFile();
                if (dir != null && dir.exists()) {
                    Desktop.getDesktop().open(dir);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void startObfuscation() {
        String inPath = inputField.getText().trim();
        String outPath = outputField.getText().trim();

        if (inPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажи входной JAR файл!", "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File inFile = new File(inPath);
        if (!inFile.isFile()) {
            JOptionPane.showMessageDialog(this, "Входной JAR файл не существует:\n" + inPath, "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (outPath.isEmpty()) {
            outPath = computeDefaultOutput(inFile);
            outputField.setText(outPath);
        }

        File outFile = new File(outPath);

        setRunning(true);
        logArea.setText("");
        log("[*] Запуск Nullfuscator...");
        log("[*] Вход:  " + inFile.getAbsolutePath());
        log("[*] Выход: " + outFile.getAbsolutePath());

        String preset = (String) presetCombo.getSelectedItem();
        boolean saveMap = saveMappingCheck.isSelected();
        String customCfg = customConfigField.getText().trim();
        String seedText = seedField.getText().trim();

        List<String> libs = new ArrayList<>();
        for (int i = 0; i < libsModel.size(); i++) {
            libs.add(libsModel.get(i));
        }

        savePreferences();

        SwingWorker<Boolean, String> worker = new SwingWorker<>() {
            @Override
            protected Boolean doInBackground() {
                Path tempConfig = null;
                PrintStream oldOut = System.out;
                PrintStream oldErr = System.err;

                try {
                    OutputStream logPipe = new OutputStream() {
                        private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream();

                        @Override
                        public synchronized void write(int b) {
                            if (b == '\n') {
                                String line = lineBuf.toString(StandardCharsets.UTF_8);
                                lineBuf.reset();
                                publish(line);
                            } else if (b != '\r') {
                                lineBuf.write(b);
                            }
                        }
                    };

                    PrintStream pipePrint = new PrintStream(logPipe, true, StandardCharsets.UTF_8);
                    System.setOut(pipePrint);
                    System.setErr(pipePrint);

                    List<String> args = new ArrayList<>();
                    args.add(inFile.getAbsolutePath());
                    args.add(outFile.getAbsolutePath());

                    if (!customCfg.isEmpty()) {
                        args.add("--config");
                        args.add(customCfg);
                    } else {
                        tempConfig = Files.createTempFile("nullfuscator-gui-", ".hocon");
                        String cfgContent = buildHoconConfig(preset);
                        Files.writeString(tempConfig, cfgContent, StandardCharsets.UTF_8);
                        args.add("--config");
                        args.add(tempConfig.toAbsolutePath().toString());
                    }

                    if (!seedText.isEmpty()) {
                        try {
                            Long.parseLong(seedText);
                            args.add("--seed");
                            args.add(seedText);
                        } catch (NumberFormatException ignored) {
                        }
                    }

                    for (String lib : libs) {
                        args.add("-l");
                        args.add(lib);
                    }

                    if (!saveMap) {
                        args.add("-M");
                    }

                    Main.run(args.toArray(new String[0]));
                    return true;
                } catch (Throwable t) {
                    publish("[x] Ошибка: " + (t.getMessage() != null ? t.getMessage() : t.toString()));
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    publish(sw.toString());
                    return false;
                } finally {
                    System.setOut(oldOut);
                    System.setErr(oldErr);
                    if (tempConfig != null) {
                        try {
                            Files.deleteIfExists(tempConfig);
                        } catch (IOException ignored) {}
                    }
                }
            }

            @Override
            protected void process(List<String> chunks) {
                for (String chunk : chunks) {
                    log(chunk);
                }
            }

            @Override
            protected void done() {
                try {
                    boolean ok = get();
                    setRunning(false);
                    if (ok) {
                        Toolkit.getDefaultToolkit().beep();
                        statusLabel.setText("Обфускация успешно завершена!");
                        progressBar.setValue(100);
                        progressBar.setString("100% Успех");

                        int opt = JOptionPane.showOptionDialog(
                                NullfuscatorFrame.this,
                                "Обфускация успешно завершена!\n\nФайл:\n" + outFile.getAbsolutePath(),
                                "Готово",
                                JOptionPane.YES_NO_OPTION,
                                JOptionPane.INFORMATION_MESSAGE,
                                null,
                                new String[]{"Показать в проводнике", "OK"},
                                "Показать в проводнике"
                        );
                        if (opt == 0) {
                            revealInExplorer(outFile);
                        }
                    } else {
                        statusLabel.setText("Ошибка во время обфускации");
                        progressBar.setValue(0);
                        progressBar.setString("Ошибка");
                    }
                } catch (Exception e) {
                    setRunning(false);
                    statusLabel.setText("Сбой: " + e.getMessage());
                }
            }
        };

        worker.execute();
    }

    private void runRetrace() {
        String mapPath = retraceMapField.getText().trim();
        if (mapPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажи файл маппинга (.map)", "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File mapFile = new File(mapPath);
        if (!mapFile.isFile()) {
            JOptionPane.showMessageDialog(this, "Файл маппинга не найден:\n" + mapPath, "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String input = retraceInArea.getText();
        if (input.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Вставь стектрейс для восстановления", "Предупреждение", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            String result = ObfMapping.retrace(mapFile, input);
            retraceOutArea.setText(result);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Ошибка демаппинга: " + e.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void runMappingInfo() {
        String mapPath = infoMapField.getText().trim();
        if (mapPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажи файл маппинга (.map)", "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File mapFile = new File(mapPath);
        if (!mapFile.isFile()) {
            JOptionPane.showMessageDialog(this, "Файл маппинга не найден:\n" + mapPath, "Ошибка", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            ObfMapping.Metadata meta = ObfMapping.readMetadata(mapFile);
            StringBuilder sb = new StringBuilder();
            sb.append("Файл маппинга:   ").append(mapFile.getAbsolutePath()).append("\n");
            sb.append("Размер файла:    ").append(mapFile.length()).append(" байт\n");
            sb.append("Формат:          ").append(meta.format()).append("\n");
            sb.append("Версия обф.:     ").append(meta.toolVersion()).append("\n");
            sb.append("Сид (seed):      ").append(meta.seed()).append("\n");
            sb.append("Хэш входного:    ").append(meta.inputSha256()).append("\n");
            sb.append("Размер входного: ").append(meta.inputBytes()).append(" байт\n");

            long classCount = Files.lines(mapFile.toPath())
                    .filter(line -> line.contains(" -> ") && line.endsWith(":"))
                    .count();
            sb.append("\nПереименовано классов: ").append(classCount).append("\n");
            infoOutArea.setText(sb.toString());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Ошибка чтения маппинга: " + e.getMessage(), "Ошибка", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setRunning(boolean running) {
        obfuscateBtn.setEnabled(!running);
        progressBar.setIndeterminate(running);
        if (running) {
            statusLabel.setText("Идёт процесс обфускации...");
            progressBar.setString("Обработка...");
        }
    }

    private void log(String line) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(line + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private String computeDefaultOutput(File input) {
        String name = input.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : ".jar";
        File parent = input.getParentFile();
        return new File(parent, base + "-obf" + ext).getAbsolutePath();
    }

    private JPanel createSectionPanel(String title) {
        JPanel p = new JPanel() {
            @Override
            public Dimension getMaximumSize() {
                Dimension pref = getPreferredSize();
                return new Dimension(Integer.MAX_VALUE, pref.height);
            }

            @Override
            protected void addImpl(Component comp, Object constraints, int index) {
                if (comp instanceof JComponent jc) {
                    jc.setAlignmentX(Component.LEFT_ALIGNMENT);
                }
                super.addImpl(comp, constraints, index);
            }
        };
        p.setBackground(new Color(36, 37, 43));
        p.setBorder(createTitledBorder(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static Font safeFont(Font font, int style, float size) {
        if (font != null) {
            return font.deriveFont(style, size);
        }
        Font base = UIManager.getFont("Label.font");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        return base.deriveFont(style, size);
    }

    private javax.swing.border.Border createTitledBorder(String title) {
        javax.swing.border.TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(68, 70, 80), 1, true),
                " " + title + " ",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                safeFont(null, Font.BOLD, 13f),
                new Color(244, 244, 246)
        );
        return BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(4, 2, 8, 2),
                BorderFactory.createCompoundBorder(
                        tb,
                        BorderFactory.createEmptyBorder(8, 12, 10, 12)
                )
        );
    }

    private void setupGlobalDropTarget() {
        new DropTarget(this, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    dtde.acceptDrop(DnDConstants.ACTION_COPY);
                    Object data = dtde.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (data instanceof List<?> list && !list.isEmpty()) {
                        for (Object item : list) {
                            if (item instanceof File f && f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                                handleSelectedInputJar(f);
                                dtde.dropComplete(true);
                                return;
                            }
                        }
                    }
                    dtde.dropComplete(false);
                } catch (Exception e) {
                    dtde.dropComplete(false);
                }
            }
        });
    }

    private void putSpinner(String key, JSpinner sp) {
        Object val = sp.getValue();
        if (val instanceof Number n) {
            prefs.putInt(key, n.intValue());
        }
    }

    private void getSpinner(String key, JSpinner sp) {
        Object val = sp.getValue();
        int def = (val instanceof Number n) ? n.intValue() : 0;
        int v = prefs.getInt(key, def);
        sp.setValue(v);
    }

    private void savePreferences() {
        try {
            prefs.put(PREF_PRESET, (String) presetCombo.getSelectedItem());
            prefs.putInt(PREF_PROFILE, profileCombo.getSelectedIndex());
            prefs.put(PREF_EXEMPTIONS, exemptionsArea.getText());
            prefs.put("pref_hot_paths", hotPathsArea.getText());
            prefs.put(PREF_SEED, seedField.getText().trim());
            prefs.putBoolean(PREF_KEEP_PUBLIC, keepPublicMethodsCheck.isSelected());
            prefs.putBoolean(PREF_KEEP_VIRTUAL, keepVirtualMethodsCheck.isSelected());
            prefs.putBoolean(PREF_KEEP_SIGNATURES, keepSignaturesCheck.isSelected());
            prefs.putBoolean(PREF_EXEMPT_MIXINS, exemptMixinsCheck.isSelected());
            prefs.putBoolean(PREF_ALLOW_INCOMPLETE, allowIncompleteClasspathCheck.isSelected());
            prefs.putBoolean("pref_allow_multi_release", allowMultiReleaseCheck.isSelected());
            prefs.putBoolean(PREF_SAVE_MAPPING, saveMappingCheck.isSelected());

            prefs.putBoolean("budgets_enabled", budgetsEnabledCheck.isSelected());
            putSpinner("budget_max_jar_pct", maxJarGrowthPercentSpinner);
            putSpinner("budget_jar_bytes", jarGrowthBytesSpinner);
            putSpinner("budget_max_cls_pct", maxClassGrowthPercentSpinner);
            putSpinner("budget_cls_allowance", classGrowthAllowanceSpinner);
            putSpinner("budget_max_mtd_pct", maxMethodGrowthPercentSpinner);
            putSpinner("budget_mtd_allowance", methodGrowthAllowanceSpinner);
            putSpinner("budget_max_insn_pct", maxInstructionGrowthPercentSpinner);
            putSpinner("budget_insn_allowance", instructionGrowthAllowanceSpinner);

            prefs.putBoolean("coverage_enabled", coverageEnabledCheck.isSelected());
            putSpinner("coverage_min_pct", coverageMinPercentSpinner);
            prefs.put("coverage_required", coverageRequiredField.getText());

            prefs.putBoolean("anti_ai_enabled", antiAiCheck.isSelected());
            putSpinner("anti_ai_level", antiAiLevelSpinner);
            putSpinner("anti_ai_const_pct", antiAiConstPercentSpinner);
            putSpinner("anti_ai_decoys", antiAiDecoySpinner);
            putSpinner("anti_ai_branch_pct", antiAiBranchPercentSpinner);

            prefs.putBoolean("anti_decomp_enabled", antiDecompilerCheck.isSelected());
            putSpinner("anti_decomp_level", antiDecompilerLevelSpinner);

            prefs.putBoolean("anti_deobf_enabled", antiDeobfCheck.isSelected());
            putSpinner("anti_deobf_level", antiDeobfLevelSpinner);
            prefs.putBoolean("anti_deobf_bad_sig", antiDeobfBadSigCheck.isSelected());
            prefs.putBoolean("anti_deobf_bad_cls_sig", antiDeobfBadClassSigCheck.isSelected());
            prefs.putBoolean("anti_deobf_poison_anno", antiDeobfPoisonAnnoCheck.isSelected());
            prefs.putBoolean("anti_deobf_poison_inner", antiDeobfPoisonInnerCheck.isSelected());
            prefs.putBoolean("anti_deobf_trap_real", antiDeobfTrapRealCheck.isSelected());
            prefs.putBoolean("anti_deobf_honeypots", antiDeobfHoneypotsCheck.isSelected());

            prefs.putBoolean("anti_debug_enabled", antiDebugCheck.isSelected());
            putSpinner("anti_debug_pct", antiDebugCheckPercentSpinner);
            putSpinner("anti_debug_detectors", antiDebugDetectorsSpinner);

            prefs.putBoolean("file_crasher_enabled", fileCrasherCheck.isSelected());
            prefs.putBoolean("runtime_integrity_enabled", runtimeIntegrityCheck.isSelected());
            prefs.put("runtime_integrity_include", runtimeIntegrityIncludeField.getText());

            prefs.putBoolean("flow_flatten_enabled", flowFlattenCheck.isSelected());
            putSpinner("flow_flatten_pct", flowFlattenPercentSpinner);
            prefs.putBoolean("flow_flatten_ref_locals", flowFlattenAllowRefLocalsCheck.isSelected());

            prefs.putBoolean("safe_cf_enabled", safeControlFlowCheck.isSelected());
            putSpinner("safe_cf_level", safeControlFlowLevelSpinner);

            prefs.putBoolean("mangled_jump_enabled", mangledJumpCheck.isSelected());
            prefs.putBoolean("flow_switch_enabled", flowSwitchCheck.isSelected());
            prefs.putBoolean("int_return_enabled", integerReturnCheck.isSelected());
            prefs.putBoolean("exception_return_enabled", exceptionReturnCheck.isSelected());
            putSpinner("exception_return_pct", exceptionReturnPercentSpinner);

            prefs.putBoolean("string_enc_enabled", stringEncCheck.isSelected());
            prefs.put("string_enc_type", (String) stringEncTypeCombo.getSelectedItem());

            prefs.putBoolean("num_enc_enabled", numberEncCheck.isSelected());
            putSpinner("num_enc_layers", numberEncLayersSpinner);

            prefs.putBoolean("ref_hiding_enabled", refHidingCheck.isSelected());
            putSpinner("ref_hiding_pct", refHidingPercentSpinner);
            putSpinner("ref_hiding_pool", refHidingBootstrapPoolSpinner);

            prefs.putBoolean("field_indir_enabled", fieldIndirectionCheck.isSelected());
            putSpinner("field_indir_pct", fieldIndirectionPercentSpinner);

            prefs.putBoolean("field_pack_enabled", fieldPackingCheck.isSelected());
            putSpinner("field_pack_min", fieldPackingMinFieldsSpinner);

            prefs.putBoolean("dispersion_enabled", crossClassDispersionCheck.isSelected());
            putSpinner("dispersion_pct", dispersionPercentSpinner);
            putSpinner("dispersion_sinks", dispersionSinksSpinner);
            putSpinner("dispersion_variants", dispersionVariantsSpinner);
            putSpinner("dispersion_max_helpers", dispersionMaxHelpersSpinner);
            putSpinner("dispersion_max_ops", dispersionMaxOpsSpinner);

            prefs.putBoolean("sem_core_enabled", semanticCoreCheck.isSelected());
            putSpinner("sem_core_pct", semanticCorePercentSpinner);
            putSpinner("sem_core_min_ops", semanticCoreMinOpsSpinner);
            putSpinner("sem_core_max_mtd", semanticCoreMaxMethodsSpinner);

            prefs.putBoolean("sem_fab_enabled", semanticFabricCheck.isSelected());
            putSpinner("sem_fab_pct", semanticFabricPercentSpinner);
            putSpinner("sem_fab_min_ops", semanticFabricMinOpsSpinner);
            putSpinner("sem_fab_max_mtd", semanticFabricMaxMethodsSpinner);
            putSpinner("sem_fab_carriers", semanticFabricCarriersSpinner);

            prefs.putBoolean("mtd_reloc_enabled", methodRelocationCheck.isSelected());
            putSpinner("mtd_reloc_pct", methodRelocPercentSpinner);
            putSpinner("mtd_reloc_max_mtd", methodRelocMaxMethodsSpinner);
            putSpinner("mtd_reloc_carriers", methodRelocCarriersSpinner);
            putSpinner("mtd_reloc_min_insns", methodRelocMinInsnsSpinner);

            prefs.putBoolean("mtd_extract_enabled", methodExtractionCheck.isSelected());
            putSpinner("mtd_extract_pct", methodExtractPercentSpinner);
            putSpinner("mtd_extract_max_mtd", methodExtractMaxMethodsSpinner);
            putSpinner("mtd_extract_carriers", methodExtractCarriersSpinner);
            putSpinner("mtd_extract_min_insns", methodExtractMinInsnsSpinner);

            prefs.putBoolean("rename_cls_enabled", renameClassesCheck.isSelected());
            prefs.put("rename_cls_prefix", classPrefixField.getText());
            putSpinner("rename_cls_depth", classDepthSpinner);
            prefs.putBoolean("rename_cls_main", renameMainCheck.isSelected());

            prefs.putBoolean("rename_mtd_enabled", renameMethodsCheck.isSelected());
            prefs.putBoolean("rename_mtd_public", renameMethodsPublicCheck.isSelected());
            prefs.putBoolean("rename_mtd_virtual", renameMethodsVirtualCheck.isSelected());
            putSpinner("rename_mtd_depth", methodDepthSpinner);

            prefs.putBoolean("rename_fld_enabled", renameFieldsCheck.isSelected());
            putSpinner("rename_fld_depth", fieldDepthSpinner);

            prefs.put("naming_alphabet", namingAlphabetField.getText());

            prefs.putBoolean("record_meta_enabled", recordMetadataCheck.isSelected());
            prefs.putBoolean("record_erase_id", recordEraseIdentityCheck.isSelected());
            putSpinner("record_depth", recordDepthSpinner);

            prefs.putBoolean("res_renamer_enabled", resourceRenamerCheck.isSelected());
            putSpinner("res_renamer_depth", resourceDepthSpinner);
            prefs.put("res_renamer_exclude_ns", resourceExcludeNsField.getText());
            prefs.put("res_renamer_exclude_dirs", resourceExcludeDirsField.getText());

            prefs.putBoolean("src_strip_enabled", sourceStripCheck.isSelected());
            prefs.putBoolean("strip_line_nums", stripLineNumbersCheck.isSelected());
            prefs.putBoolean("strip_local_vars", stripLocalVarsCheck.isSelected());
            prefs.putBoolean("strip_sigs", stripSignaturesCheck.isSelected());

            prefs.putBoolean("rel_hard_enabled", releaseHardeningCheck.isSelected());
            prefs.putBoolean("strip_maven", stripMavenCheck.isSelected());
            prefs.putBoolean("strip_fabric_desc", stripFabricDescCheck.isSelected());

            prefs.putBoolean("anno_sanitizer_enabled", annotationSanitizerCheck.isSelected());
            prefs.put("anno_sanitizer_descs", annotationSanitizerDescriptorsField.getText());

            List<String> libs = new ArrayList<>();
            for (int i = 0; i < libsModel.size(); i++) {
                libs.add(libsModel.get(i));
            }
            prefs.put(PREF_LIBS, String.join(File.pathSeparator, libs));
        } catch (Exception ignored) {
        }
    }

    private void loadPreferences() {
        try {
            String p = prefs.get(PREF_PRESET, "full");
            presetCombo.setSelectedItem(p);

            int prof = prefs.getInt(PREF_PROFILE, 0);
            profileCombo.setSelectedIndex(Math.max(0, Math.min(prof, profileCombo.getItemCount() - 1)));

            String ex = prefs.get(PREF_EXEMPTIONS, "");
            exemptionsArea.setText(ex);

            hotPathsArea.setText(prefs.get("pref_hot_paths", ""));

            seedField.setText(prefs.get(PREF_SEED, ""));

            keepPublicMethodsCheck.setSelected(prefs.getBoolean(PREF_KEEP_PUBLIC, false));
            keepVirtualMethodsCheck.setSelected(prefs.getBoolean(PREF_KEEP_VIRTUAL, false));
            keepSignaturesCheck.setSelected(prefs.getBoolean(PREF_KEEP_SIGNATURES, false));
            exemptMixinsCheck.setSelected(prefs.getBoolean(PREF_EXEMPT_MIXINS, false));
            allowIncompleteClasspathCheck.setSelected(prefs.getBoolean(PREF_ALLOW_INCOMPLETE, true));
            allowMultiReleaseCheck.setSelected(prefs.getBoolean("pref_allow_multi_release", true));
            saveMappingCheck.setSelected(prefs.getBoolean(PREF_SAVE_MAPPING, false));

            budgetsEnabledCheck.setSelected(prefs.getBoolean("budgets_enabled", budgetsEnabledCheck.isSelected()));
            getSpinner("budget_max_jar_pct", maxJarGrowthPercentSpinner);
            getSpinner("budget_jar_bytes", jarGrowthBytesSpinner);
            getSpinner("budget_max_cls_pct", maxClassGrowthPercentSpinner);
            getSpinner("budget_cls_allowance", classGrowthAllowanceSpinner);
            getSpinner("budget_max_mtd_pct", maxMethodGrowthPercentSpinner);
            getSpinner("budget_mtd_allowance", methodGrowthAllowanceSpinner);
            getSpinner("budget_max_insn_pct", maxInstructionGrowthPercentSpinner);
            getSpinner("budget_insn_allowance", instructionGrowthAllowanceSpinner);

            coverageEnabledCheck.setSelected(prefs.getBoolean("coverage_enabled", coverageEnabledCheck.isSelected()));
            getSpinner("coverage_min_pct", coverageMinPercentSpinner);
            coverageRequiredField.setText(prefs.get("coverage_required", coverageRequiredField.getText()));

            antiAiCheck.setSelected(prefs.getBoolean("anti_ai_enabled", antiAiCheck.isSelected()));
            getSpinner("anti_ai_level", antiAiLevelSpinner);
            getSpinner("anti_ai_const_pct", antiAiConstPercentSpinner);
            getSpinner("anti_ai_decoys", antiAiDecoySpinner);
            getSpinner("anti_ai_branch_pct", antiAiBranchPercentSpinner);

            antiDecompilerCheck.setSelected(prefs.getBoolean("anti_decomp_enabled", antiDecompilerCheck.isSelected()));
            getSpinner("anti_decomp_level", antiDecompilerLevelSpinner);

            antiDeobfCheck.setSelected(prefs.getBoolean("anti_deobf_enabled", antiDeobfCheck.isSelected()));
            getSpinner("anti_deobf_level", antiDeobfLevelSpinner);
            antiDeobfBadSigCheck.setSelected(prefs.getBoolean("anti_deobf_bad_sig", antiDeobfBadSigCheck.isSelected()));
            antiDeobfBadClassSigCheck.setSelected(prefs.getBoolean("anti_deobf_bad_cls_sig", antiDeobfBadClassSigCheck.isSelected()));
            antiDeobfPoisonAnnoCheck.setSelected(prefs.getBoolean("anti_deobf_poison_anno", antiDeobfPoisonAnnoCheck.isSelected()));
            antiDeobfPoisonInnerCheck.setSelected(prefs.getBoolean("anti_deobf_poison_inner", antiDeobfPoisonInnerCheck.isSelected()));
            antiDeobfTrapRealCheck.setSelected(prefs.getBoolean("anti_deobf_trap_real", antiDeobfTrapRealCheck.isSelected()));
            antiDeobfHoneypotsCheck.setSelected(prefs.getBoolean("anti_deobf_honeypots", antiDeobfHoneypotsCheck.isSelected()));

            antiDebugCheck.setSelected(prefs.getBoolean("anti_debug_enabled", antiDebugCheck.isSelected()));
            getSpinner("anti_debug_pct", antiDebugCheckPercentSpinner);
            getSpinner("anti_debug_detectors", antiDebugDetectorsSpinner);

            fileCrasherCheck.setSelected(prefs.getBoolean("file_crasher_enabled", fileCrasherCheck.isSelected()));
            runtimeIntegrityCheck.setSelected(prefs.getBoolean("runtime_integrity_enabled", runtimeIntegrityCheck.isSelected()));
            runtimeIntegrityIncludeField.setText(prefs.get("runtime_integrity_include", runtimeIntegrityIncludeField.getText()));

            flowFlattenCheck.setSelected(prefs.getBoolean("flow_flatten_enabled", flowFlattenCheck.isSelected()));
            getSpinner("flow_flatten_pct", flowFlattenPercentSpinner);
            flowFlattenAllowRefLocalsCheck.setSelected(prefs.getBoolean("flow_flatten_ref_locals", flowFlattenAllowRefLocalsCheck.isSelected()));

            safeControlFlowCheck.setSelected(prefs.getBoolean("safe_cf_enabled", safeControlFlowCheck.isSelected()));
            getSpinner("safe_cf_level", safeControlFlowLevelSpinner);

            mangledJumpCheck.setSelected(prefs.getBoolean("mangled_jump_enabled", mangledJumpCheck.isSelected()));
            flowSwitchCheck.setSelected(prefs.getBoolean("flow_switch_enabled", flowSwitchCheck.isSelected()));
            integerReturnCheck.setSelected(prefs.getBoolean("int_return_enabled", integerReturnCheck.isSelected()));
            exceptionReturnCheck.setSelected(prefs.getBoolean("exception_return_enabled", exceptionReturnCheck.isSelected()));
            getSpinner("exception_return_pct", exceptionReturnPercentSpinner);

            stringEncCheck.setSelected(prefs.getBoolean("string_enc_enabled", stringEncCheck.isSelected()));
            String strType = prefs.get("string_enc_type", (String) stringEncTypeCombo.getSelectedItem());
            stringEncTypeCombo.setSelectedItem(strType);

            numberEncCheck.setSelected(prefs.getBoolean("num_enc_enabled", numberEncCheck.isSelected()));
            getSpinner("num_enc_layers", numberEncLayersSpinner);

            refHidingCheck.setSelected(prefs.getBoolean("ref_hiding_enabled", refHidingCheck.isSelected()));
            getSpinner("ref_hiding_pct", refHidingPercentSpinner);
            getSpinner("ref_hiding_pool", refHidingBootstrapPoolSpinner);

            fieldIndirectionCheck.setSelected(prefs.getBoolean("field_indir_enabled", fieldIndirectionCheck.isSelected()));
            getSpinner("field_indir_pct", fieldIndirectionPercentSpinner);

            fieldPackingCheck.setSelected(prefs.getBoolean("field_pack_enabled", fieldPackingCheck.isSelected()));
            getSpinner("field_pack_min", fieldPackingMinFieldsSpinner);

            crossClassDispersionCheck.setSelected(prefs.getBoolean("dispersion_enabled", crossClassDispersionCheck.isSelected()));
            getSpinner("dispersion_pct", dispersionPercentSpinner);
            getSpinner("dispersion_sinks", dispersionSinksSpinner);
            getSpinner("dispersion_variants", dispersionVariantsSpinner);
            getSpinner("dispersion_max_helpers", dispersionMaxHelpersSpinner);
            getSpinner("dispersion_max_ops", dispersionMaxOpsSpinner);

            semanticCoreCheck.setSelected(prefs.getBoolean("sem_core_enabled", semanticCoreCheck.isSelected()));
            getSpinner("sem_core_pct", semanticCorePercentSpinner);
            getSpinner("sem_core_min_ops", semanticCoreMinOpsSpinner);
            getSpinner("sem_core_max_mtd", semanticCoreMaxMethodsSpinner);

            semanticFabricCheck.setSelected(prefs.getBoolean("sem_fab_enabled", semanticFabricCheck.isSelected()));
            getSpinner("sem_fab_pct", semanticFabricPercentSpinner);
            getSpinner("sem_fab_min_ops", semanticFabricMinOpsSpinner);
            getSpinner("sem_fab_max_mtd", semanticFabricMaxMethodsSpinner);
            getSpinner("sem_fab_carriers", semanticFabricCarriersSpinner);

            methodRelocationCheck.setSelected(prefs.getBoolean("mtd_reloc_enabled", methodRelocationCheck.isSelected()));
            getSpinner("mtd_reloc_pct", methodRelocPercentSpinner);
            getSpinner("mtd_reloc_max_mtd", methodRelocMaxMethodsSpinner);
            getSpinner("mtd_reloc_carriers", methodRelocCarriersSpinner);
            getSpinner("mtd_reloc_min_insns", methodRelocMinInsnsSpinner);

            methodExtractionCheck.setSelected(prefs.getBoolean("mtd_extract_enabled", methodExtractionCheck.isSelected()));
            getSpinner("mtd_extract_pct", methodExtractPercentSpinner);
            getSpinner("mtd_extract_max_mtd", methodExtractMaxMethodsSpinner);
            getSpinner("mtd_extract_carriers", methodExtractCarriersSpinner);
            getSpinner("mtd_extract_min_insns", methodExtractMinInsnsSpinner);

            renameClassesCheck.setSelected(prefs.getBoolean("rename_cls_enabled", renameClassesCheck.isSelected()));
            classPrefixField.setText(prefs.get("rename_cls_prefix", classPrefixField.getText()));
            getSpinner("rename_cls_depth", classDepthSpinner);
            renameMainCheck.setSelected(prefs.getBoolean("rename_cls_main", renameMainCheck.isSelected()));

            renameMethodsCheck.setSelected(prefs.getBoolean("rename_mtd_enabled", renameMethodsCheck.isSelected()));
            renameMethodsPublicCheck.setSelected(prefs.getBoolean("rename_mtd_public", renameMethodsPublicCheck.isSelected()));
            renameMethodsVirtualCheck.setSelected(prefs.getBoolean("rename_mtd_virtual", renameMethodsVirtualCheck.isSelected()));
            getSpinner("rename_mtd_depth", methodDepthSpinner);

            renameFieldsCheck.setSelected(prefs.getBoolean("rename_fld_enabled", renameFieldsCheck.isSelected()));
            getSpinner("rename_fld_depth", fieldDepthSpinner);

            namingAlphabetField.setText(prefs.get("naming_alphabet", namingAlphabetField.getText()));

            recordMetadataCheck.setSelected(prefs.getBoolean("record_meta_enabled", recordMetadataCheck.isSelected()));
            recordEraseIdentityCheck.setSelected(prefs.getBoolean("record_erase_id", recordEraseIdentityCheck.isSelected()));
            getSpinner("record_depth", recordDepthSpinner);

            resourceRenamerCheck.setSelected(prefs.getBoolean("res_renamer_enabled", resourceRenamerCheck.isSelected()));
            getSpinner("res_renamer_depth", resourceDepthSpinner);
            resourceExcludeNsField.setText(prefs.get("res_renamer_exclude_ns", resourceExcludeNsField.getText()));
            resourceExcludeDirsField.setText(prefs.get("res_renamer_exclude_dirs", resourceExcludeDirsField.getText()));

            sourceStripCheck.setSelected(prefs.getBoolean("src_strip_enabled", sourceStripCheck.isSelected()));
            stripLineNumbersCheck.setSelected(prefs.getBoolean("strip_line_nums", stripLineNumbersCheck.isSelected()));
            stripLocalVarsCheck.setSelected(prefs.getBoolean("strip_local_vars", stripLocalVarsCheck.isSelected()));
            stripSignaturesCheck.setSelected(prefs.getBoolean("strip_sigs", stripSignaturesCheck.isSelected()));

            releaseHardeningCheck.setSelected(prefs.getBoolean("rel_hard_enabled", releaseHardeningCheck.isSelected()));
            stripMavenCheck.setSelected(prefs.getBoolean("strip_maven", stripMavenCheck.isSelected()));
            stripFabricDescCheck.setSelected(prefs.getBoolean("strip_fabric_desc", stripFabricDescCheck.isSelected()));

            annotationSanitizerCheck.setSelected(prefs.getBoolean("anno_sanitizer_enabled", annotationSanitizerCheck.isSelected()));
            annotationSanitizerDescriptorsField.setText(prefs.get("anno_sanitizer_descs", annotationSanitizerDescriptorsField.getText()));

            String libsStr = prefs.get(PREF_LIBS, "");
            if (!libsStr.isEmpty()) {
                for (String part : libsStr.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    if (!part.isBlank() && new File(part).exists() && !libsModel.contains(part)) {
                        libsModel.addElement(part);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        updateAllToggles();
    }
}
