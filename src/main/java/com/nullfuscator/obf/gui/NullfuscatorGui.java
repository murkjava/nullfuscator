package com.nullfuscator.obf.gui;

import com.formdev.flatlaf.FlatDarkLaf;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.swing.*;

public final class NullfuscatorGui {

    private NullfuscatorGui() {
    }

    public static void main(String[] args) {
        launch();
    }

    public static void launch() {
        try {
            Font uiFont = pickFont(Font.PLAIN, 13, "Segoe UI Variable Text", "Inter", "Segoe UI");
            Font uiBold = uiFont.deriveFont(Font.BOLD, 12f);
            Font monoFont = pickFont(Font.PLAIN, 12, "JetBrains Mono", "Cascadia Mono", "Consolas", Font.MONOSPACED);

            UIManager.put("defaultFont", uiFont);
            UIManager.put("TabbedPane.font", uiBold);
            UIManager.put("TabbedPane.tabAreaAlignment", "fill");
            UIManager.put("TextArea.font", monoFont);
            UIManager.put("Tree.font", monoFont);
            UIManager.put("List.font", monoFont);
            UIManager.put("TabbedPane.tabInsets", new Insets(8, 11, 8, 11));
            UIManager.put("TabbedPane.tabHeight", 38);
            UIManager.put("TabbedPane.showTabSeparators", false);
            UIManager.put("TabbedPane.contentSeparatorHeight", 1);
            UIManager.put("TabbedPane.underlineColor", new Color(59, 130, 246));
            UIManager.put("TabbedPane.hoverColor", new Color(34, 34, 42));
            UIManager.put("TabbedPane.selectedBackground", new Color(30, 30, 38));

            UIManager.put("Component.arrowType", "triangle");
            UIManager.put("Component.arc", 8);
            UIManager.put("Button.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("CheckBox.arc", 4);
            UIManager.put("Component.focusWidth", 1);
            UIManager.put("Component.innerFocusWidth", 0);
            UIManager.put("Component.borderColor", new Color(46, 46, 56));
            UIManager.put("Component.focusedBorderColor", new Color(59, 130, 246));
            UIManager.put("Component.accentColor", new Color(59, 130, 246));

            Color bgBase = new Color(22, 22, 26);
            Color bgSurface = new Color(30, 30, 36);
            Color bgButton = new Color(39, 39, 46);

            UIManager.put("Panel.background", bgBase);
            UIManager.put("RootPane.background", bgBase);
            UIManager.put("TitlePane.unifiedBackground", true);
            UIManager.put("TitlePane.background", bgBase);
            UIManager.put("TextField.background", bgSurface);
            UIManager.put("TextArea.background", bgSurface);
            UIManager.put("ComboBox.background", bgSurface);
            UIManager.put("Spinner.background", bgSurface);
            UIManager.put("List.background", bgSurface);
            UIManager.put("Tree.background", bgSurface);
            UIManager.put("Table.background", bgSurface);
            UIManager.put("Button.background", bgButton);
            UIManager.put("Button.hoverBackground", new Color(50, 50, 59));
            UIManager.put("Button.pressedBackground", new Color(58, 58, 69));

            UIManager.put("ScrollBar.width", 10);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("ScrollBar.track", bgBase);
            UIManager.put("ScrollBar.thumb", new Color(56, 56, 66));
            UIManager.put("ScrollBar.hoverThumbColor", new Color(78, 78, 92));
            FlatDarkLaf.setup();
        } catch (Throwable t) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Throwable ignored) {
            }
        }

        SwingUtilities.invokeLater(() -> {
            NullfuscatorFrame frame = new NullfuscatorFrame();
            frame.setVisible(true);
        });
    }

    private static Font pickFont(int style, int size, String... candidates) {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()
        ));
        for (String name : candidates) {
            if (available.contains(name)) {
                return new Font(name, style, size);
            }
        }
        return new Font(candidates[candidates.length - 1], style, size);
    }
}
