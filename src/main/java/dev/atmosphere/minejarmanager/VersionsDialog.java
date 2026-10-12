package dev.atmosphere.minejarmanager;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Lists the available versions of a plugin and lets the user pick one to install. */
final class VersionsDialog extends JDialog {
    private final ModrinthApi api;
    private final PluginEntry entry;
    private final List<String> loaders;
    private final String gameVersion;
    private final Consumer<ModVersion> onInstall;

    private final List<ModVersion> versions = new ArrayList<>();
    private final JTable table;
    private final AbstractTableModel model;
    private final JCheckBox compatibleOnly = new JCheckBox("Only versions compatible with the selected loader / Minecraft version", true);
    private final JLabel status = new JLabel(" ");
    private final JButton installBtn = new JButton("Install selected version");

    private static final String[] COLS = {"", "Version", "Name", "Type", "Minecraft", "Loaders", "Published"};

    VersionsDialog(Frame owner, ModrinthApi api, PluginEntry entry, List<String> loaders,
                   String gameVersion, Consumer<ModVersion> onInstall) {
        super(owner, "Versions of " + entry.displayName(), true);
        this.api = api;
        this.entry = entry;
        this.loaders = loaders;
        this.gameVersion = gameVersion;
        this.onInstall = onInstall;

        model = new AbstractTableModel() {
            public int getRowCount() { return versions.size(); }
            public int getColumnCount() { return COLS.length; }
            public String getColumnName(int c) { return COLS[c]; }
            public Object getValueAt(int r, int c) {
                ModVersion v = versions.get(r);
                switch (c) {
                    case 0: return entry.current != null && v.id.equals(entry.current.id) ? "● installed" : "";
                    case 1: return v.versionNumber;
                    case 2: return v.name;
                    case 3: return v.versionType;
                    case 4: return summarize(v.gameVersions);
                    case 5: return String.join(", ", v.loaders);
                    default: return v.datePublished == null ? "" : v.datePublished.replace('T', ' ').substring(0, Math.min(16, v.datePublished.length()));
                }
            }
        };
        table = new JTable(model);
        table.setRowHeight(24);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setPreferredWidth(110);
        table.getColumnModel().getColumn(2).setPreferredWidth(200);
        table.getColumnModel().getColumn(3).setPreferredWidth(70);
        table.getColumnModel().getColumn(4).setPreferredWidth(160);
        table.getColumnModel().getColumn(5).setPreferredWidth(160);
        table.getColumnModel().getColumn(6).setPreferredWidth(120);

        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(8, 10, 4, 10));
        top.add(new JLabel("<html><b>" + escape(entry.displayName()) + "</b> &nbsp; installed: "
                + escape(entry.displayVersion()) + "</html>"), BorderLayout.NORTH);
        top.add(compatibleOnly, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.add(installBtn);
        btns.add(close);
        bottom.add(status, BorderLayout.CENTER);
        bottom.add(btns, BorderLayout.EAST);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        installBtn.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e -> updateButton());
        installBtn.addActionListener(e -> install());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) install();
            }
        });
        compatibleOnly.addActionListener(e -> load());

        setSize(900, 480);
        setLocationRelativeTo(owner);
        load();
    }

    private void updateButton() {
        int r = table.getSelectedRow();
        boolean ok = r >= 0 && !(entry.current != null && versions.get(r).id.equals(entry.current.id));
        installBtn.setEnabled(ok);
    }

    private void install() {
        int r = table.getSelectedRow();
        if (r < 0 || !installBtn.isEnabled()) return;
        ModVersion v = versions.get(r);
        int ok = JOptionPane.showConfirmDialog(this,
                "Replace " + entry.file.getName() + " with version " + v.versionNumber + "?\n"
                        + "The old jar is moved to .plugin-manager-backup.",
                "Install version", JOptionPane.OK_CANCEL_OPTION);
        if (ok != JOptionPane.OK_OPTION) return;
        dispose();
        onInstall.accept(v);
    }

    private void load() {
        status.setText("Loading versions…");
        installBtn.setEnabled(false);
        final boolean filter = compatibleOnly.isSelected();
        new SwingWorker<List<ModVersion>, Void>() {
            @Override protected List<ModVersion> doInBackground() throws Exception {
                return api.projectVersions(entry.project.id, filter ? loaders : null, filter ? gameVersion : null);
            }
            @Override protected void done() {
                try {
                    versions.clear();
                    versions.addAll(get());
                    model.fireTableDataChanged();
                    status.setText(versions.size() + " version(s)");
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("Failed: " + t.getMessage());
                }
            }
        }.execute();
    }

    private static String summarize(List<String> l) {
        if (l.size() <= 3) return String.join(", ", l);
        return l.get(0) + " … " + l.get(l.size() - 1) + " (" + l.size() + ")";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
