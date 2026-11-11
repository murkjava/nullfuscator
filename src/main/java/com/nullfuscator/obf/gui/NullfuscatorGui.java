package com.nullfuscator.obf.gui;

import com.formdev.flatlaf.FlatDarkLaf;

import java.awt.Font;
import java.awt.Insets;
import javax.swing.*;

public final class NullfuscatorGui {

    private NullfuscatorGui() {
    }

    public static void main(String[] args) {
        launch();
    }

    public static void launch() {
        try {
            UIManager.put("defaultFont", new Font("Segoe UI", Font.PLAIN, 13));
            UIManager.put("TabbedPane.font", new Font("Segoe UI", Font.BOLD, 13));
            UIManager.put("TabbedPane.tabInsets", new Insets(7, 14, 7, 14));
            UIManager.put("Component.arrowType", "triangle");
            UIManager.put("ScrollBar.width", 14);
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
}
