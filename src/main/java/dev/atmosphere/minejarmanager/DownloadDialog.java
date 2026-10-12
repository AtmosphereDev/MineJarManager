package dev.atmosphere.minejarmanager;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Search Modrinth, pick a version and download it straight into the plugins folder. */
final class DownloadDialog extends JDialog {
    private static final String[] SORT_LABELS = {"Relevance", "Downloads", "Follows", "Newest", "Recently updated"};
    private static final String[] SORT_INDEX = {"relevance", "downloads", "follows", "newest", "updated"};
    private static final String[] COLS = {"", "Name", "Author", "Description", "Downloads"};

    private final ModrinthApi api;
    private final IconStore icons;
    private final File folder;
    private final List<String> loaders;
    private final String gameVersion;
    private final Runnable onInstalled;

    private final JTextField query = new JTextField();
    private final JComboBox<String> sortBox = new JComboBox<>(SORT_LABELS);
    private final JCheckBox compat;
    private final List<Project> hits = new ArrayList<>();
    private long total;
    private final AbstractTableModel model;
    private final JTable table;
    private final JButton moreBtn = new JButton("Load more");
    private final JLabel resultInfo = new JLabel(" ");

    private final JLabel title = new JLabel("Select a plugin or mod from the list");
    private final JLabel details = new JLabel(" ");
    private final JComboBox<ModVersion> versionBox = new JComboBox<>();
    private final JButton downloadBtn = new JButton("Download");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel status = new JLabel(" ");

    private int searchSeq, versionSeq;
    private Project selected;
    private final Timer debounce;

    DownloadDialog(Frame owner, ModrinthApi api, IconStore icons, File folder, String loaderName,
                   List<String> loaders, String gameVersion, Runnable onInstalled) {
        super(owner, "Download plugins / mods", true);
        this.api = api;
        this.icons = icons;
        this.folder = folder;
        this.loaders = loaders;
        this.gameVersion = gameVersion;
        this.onInstalled = onInstalled;

        compat = new JCheckBox("Only " + loaderName + (gameVersion != null ? " / " + gameVersion : "") + " compatible", true);

        // ---- search bar
        query.putClientProperty("JTextField.placeholderText", "Search Modrinth…");
        query.setToolTipText("Type to search Modrinth (leave empty for popular projects)");
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.add(new JLabel("Search:"), BorderLayout.WEST);
        bar.add(query, BorderLayout.CENTER);
        JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        opts.add(new JLabel("Sort by:"));
        opts.add(sortBox);
        opts.add(compat);
        JPanel top = new JPanel(new GridLayout(2, 1, 0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 6, 10));
        top.add(bar);
        top.add(opts);

        // ---- results table
        model = new AbstractTableModel() {
            public int getRowCount() { return hits.size(); }
            public int getColumnCount() { return COLS.length; }
            public String getColumnName(int c) { return COLS[c]; }
            public Object getValueAt(int r, int c) {
                Project p = hits.get(r);
                switch (c) {
                    case 0: return p;
                    case 1: return p.title;
                    case 2: return p.author == null ? "" : p.author;
                    case 3: return p.description == null ? "" : p.description;
                    default: return formatCount(p.downloads);
                }
            }
        };
        table = new JTable(model);
        table.setRowHeight(IconStore.SIZE + 12);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new ResultRenderer());
        int[] w = {56, 190, 110, 420, 90};
        for (int i = 0; i < w.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        table.getColumnModel().getColumn(0).setMaxWidth(56);

        JPanel resultsFooter = new JPanel(new BorderLayout(8, 0));
        resultsFooter.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        resultsFooter.add(resultInfo, BorderLayout.CENTER);
        resultsFooter.add(moreBtn, BorderLayout.EAST);
        JPanel results = new JPanel(new BorderLayout());
        results.add(new JScrollPane(table), BorderLayout.CENTER);
        results.add(resultsFooter, BorderLayout.SOUTH);

        // ---- details / version selection
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        versionBox.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                if (v instanceof ModVersion) setText(describe((ModVersion) v));
                return this;
            }
        });
        versionBox.setEnabled(false);
        downloadBtn.setEnabled(false);
        progress.setVisible(false);

        JPanel verRow = new JPanel(new BorderLayout(8, 0));
        verRow.add(new JLabel("Version:"), BorderLayout.WEST);
        verRow.add(versionBox, BorderLayout.CENTER);
        verRow.add(downloadBtn, BorderLayout.EAST);

        JPanel info = new JPanel(new GridLayout(4, 1, 0, 6));
        info.add(title);
        info.add(verRow);
        info.add(details);
        JPanel st = new JPanel(new BorderLayout(8, 0));
        st.add(status, BorderLayout.CENTER);
        progress.setPreferredSize(new Dimension(200, 16));
        st.add(progress, BorderLayout.EAST);
        info.add(st);
        info.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, results, info);
        split.setResizeWeight(1.0);
        split.setBorder(null);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        // ---- behaviour
        debounce = new Timer(400, e -> search(false));
        debounce.setRepeats(false);
        query.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { debounce.restart(); }
            public void removeUpdate(DocumentEvent e) { debounce.restart(); }
            public void changedUpdate(DocumentEvent e) { debounce.restart(); }
        });
        query.addActionListener(e -> { debounce.stop(); search(false); });
        sortBox.addActionListener(e -> search(false));
        compat.addActionListener(e -> {
            search(false);
            if (selected != null) loadVersions(selected);
        });
        moreBtn.addActionListener(e -> search(true));
        moreBtn.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int r = table.getSelectedRow();
            if (r >= 0 && r < hits.size()) select(hits.get(r));
        });
        downloadBtn.addActionListener(e -> download());

        setSize(980, 700);
        setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(() -> { query.requestFocusInWindow(); search(false); });
    }

    // ----------------------------------------------------------------- search

    private void search(boolean append) {
        final int seq = ++searchSeq;
        final int offset = append ? hits.size() : 0;
        final boolean filter = compat.isSelected();
        final String q = query.getText().trim();
        final String index = SORT_INDEX[sortBox.getSelectedIndex()];
        resultInfo.setText("Searching…");
        moreBtn.setEnabled(false);
        new SwingWorker<ModrinthApi.SearchResult, Void>() {
            @Override protected ModrinthApi.SearchResult doInBackground() throws Exception {
                return api.search(q, filter ? loaders : null, filter ? gameVersion : null, index, offset, 30);
            }
            @Override protected void done() {
                if (seq != searchSeq) return; // a newer search superseded this one
                try {
                    ModrinthApi.SearchResult r = get();
                    if (!append) {
                        hits.clear();
                        clearSelection();
                    }
                    hits.addAll(r.hits);
                    total = r.total;
                    model.fireTableDataChanged();
                    resultInfo.setText(hits.size() + " of " + total + " result(s)");
                    moreBtn.setEnabled(hits.size() < total);
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    resultInfo.setText("Search failed: " + t.getMessage());
                }
            }
        }.execute();
    }

    private void clearSelection() {
        selected = null;
        versionSeq++;
        title.setText("Select a plugin or mod from the list");
        details.setText(" ");
        versionBox.removeAllItems();
        versionBox.setEnabled(false);
        downloadBtn.setEnabled(false);
    }

    private void select(Project p) {
        selected = p;
        title.setText("<html>" + esc(p.title) + " <font color='#8b909a'>by " + esc(p.author) + "</font></html>");
        loadVersions(p);
    }

    private void loadVersions(Project p) {
        final int seq = ++versionSeq;
        final boolean filter = compat.isSelected();
        versionBox.removeAllItems();
        versionBox.setEnabled(false);
        downloadBtn.setEnabled(false);
        details.setText("Loading versions…");
        new SwingWorker<List<ModVersion>, Void>() {
            @Override protected List<ModVersion> doInBackground() throws Exception {
                return api.projectVersions(p.id, filter ? loaders : null, filter ? gameVersion : null);
            }
            @Override protected void done() {
                if (seq != versionSeq) return;
                try {
                    List<ModVersion> vs = get();
                    for (ModVersion v : vs) versionBox.addItem(v);
                    boolean any = !vs.isEmpty();
                    versionBox.setEnabled(any);
                    downloadBtn.setEnabled(any);
                    details.setText(any ? vs.size() + " version(s) available"
                            : "No compatible versions. Untick the compatibility filter to see all.");
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    details.setText("Failed to load versions: " + t.getMessage());
                }
            }
        }.execute();
    }

    // --------------------------------------------------------------- download

    private void download() {
        final ModVersion v = (ModVersion) versionBox.getSelectedItem();
        if (v == null || v.primaryFile() == null) return;
        final String fname = new File(v.primaryFile().filename).getName();
        File target = new File(folder, fname);
        if (target.exists() || new File(folder, fname + PluginEntry.DISABLED_SUFFIX).exists()) {
            int ok = JOptionPane.showConfirmDialog(this, fname + " already exists in the folder. Replace it?",
                    "File exists", JOptionPane.OK_CANCEL_OPTION);
            if (ok != JOptionPane.OK_OPTION) return;
        }
        downloadBtn.setEnabled(false);
        versionBox.setEnabled(false);
        progress.setValue(0);
        progress.setVisible(true);
        status.setText("Downloading " + fname + "…");
        SwingWorker<File, Void> w = new SwingWorker<>() {
            @Override protected File doInBackground() throws Exception {
                return api.install(v, null, folder, p -> setProgress((int) (p * 100)));
            }
            @Override protected void done() {
                progress.setVisible(false);
                versionBox.setEnabled(true);
                downloadBtn.setEnabled(true);
                try {
                    File f = get();
                    status.setText("Installed " + f.getName() + " — restart the server to load it.");
                    onInstalled.run();
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    status.setText("Download failed: " + t.getMessage());
                }
            }
        };
        w.addPropertyChangeListener(e -> {
            if ("progress".equals(e.getPropertyName())) progress.setValue((Integer) e.getNewValue());
        });
        w.execute();
    }

    // ---------------------------------------------------------------- helpers

    private static String describe(ModVersion v) {
        String mc = v.gameVersions.isEmpty() ? "" : v.gameVersions.size() <= 2 ? String.join(", ", v.gameVersions)
                : v.gameVersions.get(0) + " … " + v.gameVersions.get(v.gameVersions.size() - 1);
        return v.versionNumber + "   [" + v.versionType + "]   MC " + mc + "   " + String.join(", ", v.loaders);
    }

    static String formatCount(long n) {
        if (n >= 1_000_000) return String.format("%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format("%.1fK", n / 1_000.0);
        return String.valueOf(n);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }

    private final class ResultRenderer extends javax.swing.table.DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, "", sel, false, row, col);
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            setHorizontalAlignment(col == 4 ? SwingConstants.RIGHT : col == 0 ? SwingConstants.CENTER : SwingConstants.LEFT);
            setIcon(null);
            setToolTipText(null);
            if (col == 0) {
                Project p = (Project) v;
                setIcon(p.iconUrl != null ? icons.url(p.iconUrl, p.title, table::repaint) : icons.placeholder(p.title));
            } else {
                setText(String.valueOf(v));
                if (col == 3) setToolTipText("<html><body style='width:420px'>" + esc(String.valueOf(v)) + "</body></html>");
            }
            setFont(t.getFont().deriveFont(col == 1 ? Font.BOLD : Font.PLAIN));
            setForeground(sel ? Color.WHITE : col == 3 || col == 2 ? Theme.MUTED : Theme.FG);
            setBackground(sel ? Theme.SELECT : row % 2 == 0 ? Theme.BG : Theme.BG_ALT);
            return this;
        }
    }
}
