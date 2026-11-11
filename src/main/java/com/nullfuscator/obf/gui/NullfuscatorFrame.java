package com.nullfuscator.obf.gui;

import com.nullfuscator.obf.core.BuildInfo;
import com.nullfuscator.obf.core.Main;
import com.nullfuscator.obf.core.ObfMapping;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.DefaultMutableTreeNode;
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
    private final JCheckBox saveMappingCheck = new JCheckBox("Сохранять ProGuard mapping (.map)", true);

    private final JTree jarTree = new JTree(new DefaultMutableTreeNode("Перетащи JAR или выбери файл"));
    private final JLabel jarTreeInfoLabel = new JLabel("Классы архива не загружены");

    private final JTextArea exemptionsArea = new JTextArea();
    private final JCheckBox exemptMixinsCheck = new JCheckBox("Исключать все миксины (*/mixin/*)", false);
    private final JCheckBox keepPublicMethodsCheck = new JCheckBox("Не переименовывать публичные методы", false);
    private final JCheckBox keepVirtualMethodsCheck = new JCheckBox("Не переименовывать виртуальные методы", false);
    private final JCheckBox keepSignaturesCheck = new JCheckBox("Сохранять сигнатуры дженериков (для Gson/Jackson)", false);
    private final JCheckBox allowIncompleteClasspathCheck = new JCheckBox("Разрешить неполный класспас (Allow Incomplete Classpath)", true);

    private final JCheckBox stringEncCheck = new JCheckBox("Шифрование строк (Polymorphic Strings)", true);
    private final JCheckBox numberEncCheck = new JCheckBox("Шифрование чисел (Number Math)", true);
    private final JCheckBox flowFlattenCheck = new JCheckBox("Сглаживание потока управления (Control Flow)", true);
    private final JCheckBox antiDecompilerCheck = new JCheckBox("Защита от декомпиляторов (Anti-Decompiler)", true);
    private final JCheckBox antiDeobfCheck = new JCheckBox("Защита от деобфускаторов (Anti-Deobf AST)", true);
    private final JCheckBox antiAiCheck = new JCheckBox("Защита от анализа нейросетями (Anti-AI)", true);
    private final JCheckBox antiDebugCheck = new JCheckBox("Обнаружение отладчиков (Anti-Debug)", true);
    private final JCheckBox renameClassesCheck = new JCheckBox("Переименование классов (Class Renamer)", true);
    private final JCheckBox renameMethodsCheck = new JCheckBox("Переименование методов (Method Renamer)", true);
    private final JCheckBox renameFieldsCheck = new JCheckBox("Переименование полей (Field Renamer)", true);
    private final JCheckBox resourceRenamerCheck = new JCheckBox("Переименование ресурсов (Resource Renamer)", false);

    private final DefaultListModel<String> libsModel = new DefaultListModel<>();
    private final JList<String> libsList = new JList<>(libsModel);
    private final JTextArea logArea = new JTextArea();
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel statusLabel = new JLabel("Готов к работе");
    private final JButton obfuscateBtn = new JButton("⚡ Запустить обфускацию");

    private final JTextField retraceMapField = new JTextField();
    private final JTextArea retraceInArea = new JTextArea();
    private final JTextArea retraceOutArea = new JTextArea();

    private final JTextField infoMapField = new JTextField();
    private final JTextArea infoOutArea = new JTextArea();

    public NullfuscatorFrame() {
        setTitle("Nullfuscator " + BuildInfo.VERSION + " — Standalone Obfuscator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1080, 820);
        setMinimumSize(new Dimension(900, 660));
        setLocationRelativeTo(null);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(safeFont(tabbedPane.getFont(), Font.BOLD, 13f));
        tabbedPane.addTab("Обфускатор", createObfuscatorPanel());
        tabbedPane.addTab("Инспектор JAR", createJarInspectorPanel());
        tabbedPane.addTab("Исключения (Exemptions)", createExemptionsPanel());
        tabbedPane.addTab("Трансформеры", createTransformersPanel());
        tabbedPane.addTab("Демаппинг (Retrace)", createRetracePanel());
        tabbedPane.addTab("Инфо о маппинге", createMappingInfoPanel());

        setContentPane(tabbedPane);
        setupAppIcon();
        setupGlobalDropTarget();
        loadPreferences();

        profileCombo.addActionListener(e -> applyProfileDefaults());
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
        JButton revealOutputBtn = new JButton("📁 Папка");
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
        JButton randSeedBtn = new JButton("🎲");
        randSeedBtn.setToolTipText("Сгенерировать случайный сид");
        randSeedBtn.addActionListener(e -> seedField.setText(String.valueOf(Math.abs(new SecureRandom().nextLong()))));
        JButton clearSeedBtn = new JButton("✖");
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
        JButton previewCfgBtn = new JButton("👁 HOCON");
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
        JButton autoScanBtn = new JButton("🔍 Найти в .gradle");
        autoScanBtn.setToolTipText("Автоматически сканирует ~/.gradle/caches на наличие Minecraft, Fabric Loader, Fabric API, Gson");
        autoScanBtn.addActionListener(e -> runGradleScan());

        JButton addLibBtn = new JButton("➕ Добавить JAR...");
        addLibBtn.addActionListener(e -> addLibraryFile());

        JButton removeLibBtn = new JButton("➖ Удалить");
        removeLibBtn.addActionListener(e -> removeSelectedLibs());

        JButton clearLibsBtn = new JButton("Очистить все");
        clearLibsBtn.addActionListener(e -> libsModel.clear());

        libsBtnBar.add(autoScanBtn);
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

        JButton refreshBtn = new JButton("🔄 Перечитать JAR");
        refreshBtn.addActionListener(e -> {
            String p = inputField.getText().trim();
            if (!p.isEmpty()) {
                refreshJarTree(new File(p));
            }
        });
        topBar.add(refreshBtn, BorderLayout.EAST);
        root.add(topBar, BorderLayout.NORTH);

        jarTree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane treeScroll = new JScrollPane(jarTree);
        root.add(treeScroll, BorderLayout.CENTER);

        JPopupMenu treeMenu = new JPopupMenu();
        JMenuItem excludeItem = new JMenuItem("🛡 Добавить в исключения");
        excludeItem.addActionListener(e -> addSelectedTreeNodesToExemptions(false));
        JMenuItem excludeAllItem = new JMenuItem("🛡 Исключить весь пакет (*)");
        excludeAllItem.addActionListener(e -> addSelectedTreeNodesToExemptions(true));
        JMenuItem copyItem = new JMenuItem("📋 Скопировать полное имя");
        copyItem.addActionListener(e -> copySelectedTreeNodeName());

        treeMenu.add(excludeItem);
        treeMenu.add(excludeAllItem);
        treeMenu.addSeparator();
        treeMenu.add(copyItem);

        jarTree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = jarTree.getClosestRowForLocation(e.getX(), e.getY());
                    if (row >= 0) {
                        jarTree.setSelectionRow(row);
                        treeMenu.show(jarTree, e.getX(), e.getY());
                    }
                }
            }
        });

        JLabel footer = new JLabel("<html><font color='#a1a1aa'>💡 Кликни правой кнопкой мыши по классу или пакету, чтобы исключить его из обфускации.</font></html>");
        root.add(footer, BorderLayout.SOUTH);

        return root;
    }

    private void refreshJarTree(File jar) {
        if (jar == null || !jar.isFile()) {
            jarTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Файл не выбран")));
            jarTreeInfoLabel.setText("Классы архива не загружены");
            return;
        }

        DefaultMutableTreeNode root = new DefaultMutableTreeNode(jar.getName());
        int count = 0;

        try (ZipFile z = new ZipFile(jar)) {
            Enumeration<? extends ZipEntry> entries = z.entries();
            Map<String, DefaultMutableTreeNode> dirNodes = new HashMap<>();

            List<String> classNames = new ArrayList<>();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class")) {
                    classNames.add(name);
                    count++;
                }
            }
            Collections.sort(classNames);

            for (String name : classNames) {
                String[] parts = name.split("/");
                DefaultMutableTreeNode current = root;
                String currentPath = "";

                for (int i = 0; i < parts.length; i++) {
                    String part = parts[i];
                    currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
                    if (i == parts.length - 1) {
                        current.add(new DefaultMutableTreeNode(part));
                    } else {
                        DefaultMutableTreeNode dirNode = dirNodes.get(currentPath);
                        if (dirNode == null) {
                            dirNode = new DefaultMutableTreeNode(part);
                            dirNodes.put(currentPath, dirNode);
                            current.add(dirNode);
                        }
                        current = dirNode;
                    }
                }
            }
            jarTree.setModel(new DefaultTreeModel(root));
            jarTreeInfoLabel.setText("В архиве обнаружено " + count + " классов. Путь: " + jar.getName());
            for (int i = 0; i < Math.min(jarTree.getRowCount(), 25); i++) {
                jarTree.expandRow(i);
            }
        } catch (Exception e) {
            jarTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Ошибка чтения: " + e.getMessage())));
            jarTreeInfoLabel.setText("Ошибка чтения JAR: " + e.getMessage());
        }
    }

    private void addSelectedTreeNodesToExemptions(boolean entirePackage) {
        TreePath path = jarTree.getSelectionPath();
        if (path == null) {
            return;
        }

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        String full = getNodeFullPath(node);
        if (full.isEmpty()) {
            return;
        }

        if (entirePackage && !full.endsWith(".*")) {
            if (node.isLeaf() && full.contains(".")) {
                full = full.substring(0, full.lastIndexOf('.')) + ".*";
            } else {
                full = full + ".*";
            }
        }

        String current = exemptionsArea.getText().trim();
        if (!current.contains(full)) {
            if (!current.isEmpty()) {
                current += "\n";
            }
            current += full;
            exemptionsArea.setText(current);
            log("[+] Добавлено в исключения: " + full);
            JOptionPane.showMessageDialog(this, "Добавлено в список исключений:\n" + full, "Исключение добавлено", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void copySelectedTreeNodeName() {
        TreePath path = jarTree.getSelectionPath();
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            String full = getNodeFullPath(node);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(full), null);
        }
    }

    private String getNodeFullPath(DefaultMutableTreeNode node) {
        TreeNode[] path = node.getPath();
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < path.length; i++) {
            if (sb.length() > 0) {
                sb.append(".");
            }
            sb.append(path[i].toString());
        }
        String res = sb.toString();
        if (res.endsWith(".class")) {
            res = res.substring(0, res.length() - 6);
        }
        return res;
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
        root.add(rulesPanel, BorderLayout.NORTH);

        JPanel customPanel = createSectionPanel("Пакеты и классы для исключения (по одному в строке)");
        customPanel.setLayout(new BorderLayout(6, 6));

        exemptionsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        exemptionsArea.setToolTipText("Примеры:\nru.myproject.gui\ndev.ranked.ac.security.*\ncom.example.api.PublicClass");
        JScrollPane scroll = new JScrollPane(exemptionsArea);
        customPanel.add(scroll, BorderLayout.CENTER);

        JLabel hintLabel = new JLabel("<html><font color='#a1a1aa'>Укажи пакеты или классы без обфускации. Например: <code>com.mycompany.api.*</code> или <code>ru.project.config.MyConfig</code></font></html>");
        customPanel.add(hintLabel, BorderLayout.SOUTH);

        root.add(customPanel, BorderLayout.CENTER);

        return root;
    }

    private JPanel createTransformersPanel() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(14, 14, 14, 14));

        JPanel transCard = createSectionPanel("Активные модули защиты (Transformers)");
        transCard.setLayout(new GridLayout(6, 2, 10, 10));

        transCard.add(stringEncCheck);
        transCard.add(numberEncCheck);
        transCard.add(flowFlattenCheck);
        transCard.add(antiDecompilerCheck);
        transCard.add(antiDeobfCheck);
        transCard.add(antiAiCheck);
        transCard.add(antiDebugCheck);
        transCard.add(renameClassesCheck);
        transCard.add(renameMethodsCheck);
        transCard.add(renameFieldsCheck);
        transCard.add(resourceRenamerCheck);

        root.add(transCard, BorderLayout.NORTH);

        JPanel infoPanel = createSectionPanel("Подсказка по режимам");
        infoPanel.setLayout(new BorderLayout(6, 6));
        JTextArea infoText = new JTextArea();
        infoText.setEditable(false);
        infoText.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        infoText.setBackground(root.getBackground());
        infoText.setText(
                "• Для Fabric/Forge модов обязательно отключайте 'Переименование ресурсов' (иначе ломаются lang/*.json и текстуры).\n" +
                "• 'Не переименовывать публичные методы' рекомендуется для плагинов и модов с открытыми API и событиями.\n" +
                "• 'Сохранять сигнатуры дженериков' необходимо, если используется сериализация Gson или Jackson.\n" +
                "• При возникновении проблем с производительностью отключите Control Flow или выберите пресет Balanced/Light."
        );
        infoPanel.add(infoText, BorderLayout.CENTER);
        root.add(infoPanel, BorderLayout.CENTER);

        return root;
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
        JButton retraceBtn = new JButton("⚡ Восстановить стектрейс");
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
        JButton inspectBtn = new JButton("🔍 Анализ маппинга");
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

    private void runGradleScan() {
        log("[*] Поиск библиотек в ~/.gradle/caches...");
        List<File> found = GradleScanner.scanCommonLibraries();
        int added = 0;
        for (File f : found) {
            String path = f.getAbsolutePath();
            if (!libsModel.contains(path)) {
                libsModel.addElement(path);
                added++;
            }
        }
        if (added > 0) {
            log("[+] Добавлено " + added + " библиотек из Gradle cache");
        } else if (!found.isEmpty()) {
            log("[*] Все найденные библиотеки уже есть в списке (" + found.size() + ")");
        } else {
            log("[!] В ~/.gradle/caches не найдено библиотек");
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
        JButton copyBtn = new JButton("📋 Скопировать в буфер");
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(hocon), null);
            JOptionPane.showMessageDialog(dialog, "Конфигурация скопирована в буфер обмена!");
        });

        JButton saveBtn = new JButton("💾 Сохранить как...");
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

    private String buildHoconConfig(String basePreset) {
        StringBuilder sb = new StringBuilder();
        sb.append("baseConfig = \"").append(basePreset).append(".hocon\"\n\n");

        sb.append("compatibility {\n");
        sb.append("  allowMultiReleaseRename: true\n");
        sb.append("  allowIncompleteClasspath: ").append(allowIncompleteClasspathCheck.isSelected()).append("\n");
        sb.append("}\n\n");

        sb.append("sourceStrip {\n");
        sb.append("  stripSignatures: ").append(!keepSignaturesCheck.isSelected()).append("\n");
        sb.append("}\n\n");

        sb.append("resourceRenamer.enabled = ").append(resourceRenamerCheck.isSelected()).append("\n");
        sb.append("stringEncryption.enabled = ").append(stringEncCheck.isSelected()).append("\n");
        sb.append("numberEncryption.enabled = ").append(numberEncCheck.isSelected()).append("\n");
        sb.append("flatten.enabled = ").append(flowFlattenCheck.isSelected()).append("\n");
        sb.append("antiDecompiler.enabled = ").append(antiDecompilerCheck.isSelected()).append("\n");
        sb.append("antiDeobf.enabled = ").append(antiDeobfCheck.isSelected()).append("\n");
        sb.append("antiAI.enabled = ").append(antiAiCheck.isSelected()).append("\n");
        sb.append("antiDebug.enabled = ").append(antiDebugCheck.isSelected()).append("\n");
        sb.append("classRenamer.enabled = ").append(renameClassesCheck.isSelected()).append("\n");
        sb.append("fieldRenamer.enabled = ").append(renameFieldsCheck.isSelected()).append("\n");

        sb.append("methodRenamer {\n");
        sb.append("  enabled: ").append(renameMethodsCheck.isSelected()).append("\n");
        sb.append("  renamePublic: ").append(!keepPublicMethodsCheck.isSelected()).append("\n");
        sb.append("  renameVirtual: ").append(!keepVirtualMethodsCheck.isSelected()).append("\n");
        sb.append("}\n\n");

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
                                new String[]{"📁 Показать в проводнике", "OK"},
                                "📁 Показать в проводнике"
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
        JPanel p = new JPanel();
        p.setBorder(createTitledBorder(title));
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

    private javax.swing.border.TitledBorder createTitledBorder(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(63, 63, 70), 1, true),
                " " + title + " ",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                safeFont(null, Font.BOLD, 12f)
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

    private void savePreferences() {
        try {
            prefs.put(PREF_PRESET, (String) presetCombo.getSelectedItem());
            prefs.putInt(PREF_PROFILE, profileCombo.getSelectedIndex());
            prefs.put(PREF_EXEMPTIONS, exemptionsArea.getText());
            prefs.put(PREF_SEED, seedField.getText().trim());
            prefs.putBoolean(PREF_KEEP_PUBLIC, keepPublicMethodsCheck.isSelected());
            prefs.putBoolean(PREF_KEEP_VIRTUAL, keepVirtualMethodsCheck.isSelected());
            prefs.putBoolean(PREF_KEEP_SIGNATURES, keepSignaturesCheck.isSelected());
            prefs.putBoolean(PREF_EXEMPT_MIXINS, exemptMixinsCheck.isSelected());
            prefs.putBoolean(PREF_ALLOW_INCOMPLETE, allowIncompleteClasspathCheck.isSelected());

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

            seedField.setText(prefs.get(PREF_SEED, ""));

            keepPublicMethodsCheck.setSelected(prefs.getBoolean(PREF_KEEP_PUBLIC, false));
            keepVirtualMethodsCheck.setSelected(prefs.getBoolean(PREF_KEEP_VIRTUAL, false));
            keepSignaturesCheck.setSelected(prefs.getBoolean(PREF_KEEP_SIGNATURES, false));
            exemptMixinsCheck.setSelected(prefs.getBoolean(PREF_EXEMPT_MIXINS, false));
            allowIncompleteClasspathCheck.setSelected(prefs.getBoolean(PREF_ALLOW_INCOMPLETE, true));

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
    }
}
