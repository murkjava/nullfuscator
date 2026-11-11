package com.nullfuscator.obf.gui;

import com.nullfuscator.obf.core.BuildInfo;
import com.nullfuscator.obf.core.Main;
import com.nullfuscator.obf.core.ObfMapping;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;

public final class NullfuscatorFrame extends JFrame {

    private final JTextField inputField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JComboBox<String> presetCombo = new JComboBox<>(new String[]{"full", "strong", "balanced", "light"});
    private final JCheckBox fabricModeCheck = new JCheckBox("Режим Fabric / Minecraft мода", true);
    private final JCheckBox saveMappingCheck = new JCheckBox("Сохранять ProGuard mapping (.map)", true);
    private final JTextField customConfigField = new JTextField();
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
        setSize(1020, 780);
        setMinimumSize(new Dimension(860, 620));
        setLocationRelativeTo(null);

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(tabbedPane.getFont().deriveFont(Font.BOLD, 13f));
        tabbedPane.addTab("Обфускатор", createObfuscatorPanel());
        tabbedPane.addTab("Демаппинг (Retrace)", createRetracePanel());
        tabbedPane.addTab("Инфо о маппинге", createMappingInfoPanel());

        setContentPane(tabbedPane);
        setupGlobalDropTarget();
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
        JButton browseOutputBtn = new JButton("Обзор...");
        browseOutputBtn.addActionListener(e -> chooseOutputJar());
        filesCard.add(browseOutputBtn, gbc);

        configPanel.add(filesCard);
        configPanel.add(Box.createVerticalStrut(8));

        JPanel settingsCard = createSectionPanel("Параметры обфускации");
        settingsCard.setLayout(new GridBagLayout());
        GridBagConstraints sgbc = new GridBagConstraints();
        sgbc.insets = new Insets(4, 4, 4, 4);
        sgbc.fill = GridBagConstraints.HORIZONTAL;

        sgbc.gridx = 0;
        sgbc.gridy = 0;
        sgbc.weightx = 0.0;
        settingsCard.add(new JLabel("Пресет защиты:"), sgbc);

        sgbc.gridx = 1;
        sgbc.weightx = 0.4;
        settingsCard.add(presetCombo, sgbc);

        sgbc.gridx = 2;
        sgbc.weightx = 0.6;
        settingsCard.add(fabricModeCheck, sgbc);

        sgbc.gridx = 0;
        sgbc.gridy = 1;
        sgbc.weightx = 0.0;
        settingsCard.add(new JLabel("Кастомный .hocon:"), sgbc);

        sgbc.gridx = 1;
        sgbc.weightx = 0.6;
        settingsCard.add(customConfigField, sgbc);

        sgbc.gridx = 2;
        sgbc.weightx = 0.0;
        JPanel cfgBtnGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton browseCfgBtn = new JButton("Обзор...");
        browseCfgBtn.addActionListener(e -> chooseConfigFile());
        JButton clearCfgBtn = new JButton("Сбросить");
        clearCfgBtn.addActionListener(e -> customConfigField.setText(""));
        cfgBtnGroup.add(browseCfgBtn);
        cfgBtnGroup.add(clearCfgBtn);
        cfgBtnGroup.add(saveMappingCheck);
        settingsCard.add(cfgBtnGroup, sgbc);

        configPanel.add(settingsCard);
        configPanel.add(Box.createVerticalStrut(8));

        JPanel libsCard = createSectionPanel("Внешние библиотеки и зависимости (-l)");
        libsCard.setLayout(new BorderLayout(6, 6));

        libsList.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane libsScroll = new JScrollPane(libsList);
        libsScroll.setPreferredSize(new Dimension(100, 110));
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

        obfuscateBtn.setFont(obfuscateBtn.getFont().deriveFont(Font.BOLD, 14f));
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
                        if (isFabricJar(f)) {
                            fabricModeCheck.setSelected(true);
                        }
                    }
                }
            }
        });

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
        retraceBtn.setFont(retraceBtn.getFont().deriveFont(Font.BOLD, 13f));
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
        inspectBtn.setFont(inspectBtn.getFont().deriveFont(Font.BOLD, 13f));
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
        if (isFabricJar(jar)) {
            fabricModeCheck.setSelected(true);
            log("[*] Обнаружен fabric.mod.json: активирован режим Fabric мода");
            if (libsModel.isEmpty()) {
                runGradleScan();
            }
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
            log("[!] В ~/.gradle/caches не найдено известных библиотек (Fabric/Minecraft/Gson)");
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
        boolean fabricMode = fabricModeCheck.isSelected();
        boolean saveMap = saveMappingCheck.isSelected();
        String customCfg = customConfigField.getText().trim();

        List<String> libs = new ArrayList<>();
        for (int i = 0; i < libsModel.size(); i++) {
            libs.add(libsModel.get(i));
        }

        SwingWorker<Boolean, String> worker = new SwingWorker<>() {
            private String errorDetail = null;

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
                    } else if (fabricMode) {
                        tempConfig = Files.createTempFile("nullfuscator-fabric-", ".hocon");
                        String cfgContent = """
                                baseConfig = "%s.hocon"
                                resourceRenamer.enabled = false
                                compatibility {
                                  allowMultiReleaseRename: true
                                  allowIncompleteClasspath: true
                                }
                                sourceStrip { stripSignatures: false }
                                defaults.exempt = [
                                  "class{^.*/mixin/}",
                                  "class{^.*/mixins/}"
                                ]
                                """.formatted(preset);
                        Files.writeString(tempConfig, cfgContent, StandardCharsets.UTF_8);
                        args.add("--config");
                        args.add(tempConfig.toAbsolutePath().toString());
                    } else {
                        args.add("--preset");
                        args.add(preset);
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
                    errorDetail = t.getMessage();
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
                        statusLabel.setText("Обфускация успешно завершена!");
                        progressBar.setValue(100);
                        progressBar.setString("100% Успех");
                        JOptionPane.showMessageDialog(NullfuscatorFrame.this,
                                "Обфускация успешно завершена!\nСохранено в:\n" + outFile.getAbsolutePath(),
                                "Готово", JOptionPane.INFORMATION_MESSAGE);
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

    private boolean isFabricJar(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try (ZipFile z = new ZipFile(file)) {
            return z.getEntry("fabric.mod.json") != null;
        } catch (Exception e) {
            return false;
        }
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

    private javax.swing.border.TitledBorder createTitledBorder(String title) {
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(63, 63, 70), 1, true),
                " " + title + " ",
                javax.swing.border.TitledBorder.LEFT,
                javax.swing.border.TitledBorder.TOP,
                getFont().deriveFont(Font.BOLD, 12f)
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
}
