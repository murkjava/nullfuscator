package com.nullfuscator.obf.gui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;

public final class NullfuscatorGui {

    private NullfuscatorGui() {
    }

    public static void main(String[] args) {
        launch();
    }

    public static void launch() {
        try {
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
