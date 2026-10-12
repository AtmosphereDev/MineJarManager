package dev.atmosphere.minejarmanager;

import javax.swing.*;
import java.awt.*;

/** Dark colour scheme (Nimbus look and feel with overridden palette). */
final class Theme {
    private Theme() {}

    static final Color BG = new Color(0x1e1f22);
    static final Color BG_ALT = new Color(0x232529);
    static final Color PANEL = new Color(0x2b2d30);
    static final Color BORDER = new Color(0x3c3f45);
    static final Color FG = new Color(0xdfe1e5);
    static final Color MUTED = new Color(0x8b909a);
    static final Color ACCENT = new Color(0x3574f0);
    static final Color SELECT = new Color(0x2e436e);
    static final Color OK = new Color(0x5fb865);
    static final Color WARN = new Color(0xf0a63c);

    static void apply() {
        UIManager.put("control", PANEL);
        UIManager.put("info", new Color(0x3c3f41));
        UIManager.put("nimbusBase", new Color(0x3a3d43));
        UIManager.put("nimbusLightBackground", BG);
        UIManager.put("nimbusSelectionBackground", ACCENT);
        UIManager.put("nimbusSelectedText", Color.WHITE);
        UIManager.put("nimbusFocus", ACCENT);
        UIManager.put("nimbusDisabledText", MUTED);
        UIManager.put("nimbusBorder", BORDER);
        UIManager.put("nimbusOrange", ACCENT);
        UIManager.put("text", FG);
        try {
            for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(i.getName())) {
                    UIManager.setLookAndFeel(i.getClassName());
                    break;
                }
            }
        } catch (Exception ignored) {
            // fall back to the default look and feel
        }
        UIManager.put("ToolTip.background", new Color(0x3c3f41));
        UIManager.put("ToolTip.foreground", FG);
        UIManager.put("OptionPane.background", PANEL);
        UIManager.put("Panel.background", PANEL);
        UIManager.put("Table.background", BG);
        UIManager.put("Table.alternateRowColor", BG_ALT);
        UIManager.put("Table.gridColor", BORDER);
    }
}
