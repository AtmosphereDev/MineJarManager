package dev.atmosphere.minejarmanager;

import com.formdev.flatlaf.intellijthemes.FlatDarkFlatIJTheme;
import com.formdev.flatlaf.util.SystemInfo;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/** Plugin manager: scan a folder, identify jars on Modrinth, update, switch, enable/disable, download. */
public final class PluginManagerApp extends JFrame {
    /** Display name -> Modrinth loader categories that count as compatible. */
    static final Map<String, List<String>> LOADERS = new LinkedHashMap<>();
    static {
        LOADERS.put("Paper", List.of("paper", "spigot", "bukkit"));
        LOADERS.put("Purpur", List.of("purpur", "paper", "spigot", "bukkit"));
        LOADERS.put("Folia", List.of("folia", "paper", "spigot", "bukkit"));
        LOADERS.put("Spigot", List.of("spigot", "bukkit"));
        LOADERS.put("Bukkit", List.of("bukkit"));
        LOADERS.put("Velocity", List.of("velocity"));
        LOADERS.put("BungeeCord", List.of("bungeecord"));
        LOADERS.put("Waterfall", List.of("waterfall", "bungeecord"));
        LOADERS.put("Fabric", List.of("fabric"));
        LOADERS.put("Quilt", List.of("quilt", "fabric"));
        LOADERS.put("Forge", List.of("forge"));
        LOADERS.put("NeoForge", List.of("neoforge"));
    }

    // column indexes
    private static final int C_ON = 0, C_ICON = 1, C_NAME = 2, C_DESC = 3, C_VER = 4, C_LATEST = 5, C_STATUS = 6, C_FILE = 7;
    private static final String[] COLS = {"Enable", "Image", "Name", "Description", "Version", "Latest", "Status", "File"};

    private final Preferences prefs = Preferences.userNodeForPackage(PluginManagerApp.class);
    private final ModrinthApi api = new ModrinthApi();
    private final IconStore icons = new IconStore();

    private final PluginTableModel tableModel = new PluginTableModel();
    private final JTable table = new JTable(tableModel);
    private final TableRowSorter<PluginTableModel> sorter = new TableRowSorter<>(tableModel);
    private final JTextField filterField = new JTextField(16);
    private final JTextField folderField = new JTextField();
    private final JComboBox<String> loaderBox = new JComboBox<>(LOADERS.keySet().toArray(new String[0]));
    private final JComboBox<String> mcBox = new JComboBox<>(new String[]{"Any"});
    private final JButton scanBtn = new JButton("Scan & check updates");
    private final JButton downloadBtn = new JButton("Download plugins");
    private final JButton versionsBtn = new JButton("Change version");
    private final JButton updateBtn = new JButton("Update selected");
    private final JButton updateAllBtn = new JButton("Update all");
    private final JLabel statusLabel = new JLabel("Choose a plugins folder and press Scan.");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private boolean busy;

    public PluginManagerApp() {
        super("MineJarManager");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1180, 720);
        setLocationRelativeTo(null);

        // ---- top: folder + loader
        JButton browse = new JButton("Browse");
        browse.addActionListener(e -> chooseFolder());
        mcBox.setEditable(true);
        mcBox.setPrototypeDisplayValue("1.21.11-pre-release");

        JPanel row1 = new JPanel(new BorderLayout(6, 0));
        row1.add(new JLabel("Plugins folder:"), BorderLayout.WEST);
        row1.add(folderField, BorderLayout.CENTER);
        row1.add(browse, BorderLayout.EAST);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row2.add(new JLabel("Loader / platform:"));
        row2.add(loaderBox);
        row2.add(new JLabel("Minecraft version:"));
        row2.add(mcBox);
        row2.add(scanBtn);
        row2.add(downloadBtn);

        JPanel top = new JPanel(new GridLayout(2, 1, 0, 6));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));
        top.add(row1);
        top.add(row2);

        // ---- center: sortable table
        setupTable();

        // ---- bottom
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(versionsBtn);
        buttons.add(updateBtn);
        buttons.add(updateAllBtn);
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        filterPanel.add(new JLabel("Filter:"));
        filterPanel.add(filterField);
        JPanel btnRow = new JPanel(new BorderLayout());
        btnRow.add(buttons, BorderLayout.WEST);
        btnRow.add(filterPanel, BorderLayout.EAST);

        JPanel statusPanel = new JPanel(new BorderLayout(8, 0));
        statusPanel.add(statusLabel, BorderLayout.CENTER);
        progress.setPreferredSize(new Dimension(180, 16));
        progress.setVisible(false);
        statusPanel.add(progress, BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
        bottom.add(btnRow, BorderLayout.NORTH);
        bottom.add(statusPanel, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        scanBtn.addActionListener(e -> startScan());
        downloadBtn.addActionListener(e -> openDownloader());
        versionsBtn.addActionListener(e -> changeVersion());
        updateBtn.addActionListener(e -> updateSelected());
        updateAllBtn.addActionListener(e -> updateAll());
        filterField.putClientProperty("JTextField.placeholderText", "name, description, file…");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });

        loadPrefs();
        updateButtons();
        loadGameVersions();
    }

    // ------------------------------------------------------------------ table

    private void setupTable() {
        table.setRowHeight(IconStore.SIZE + 12);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setRowSorter(sorter);
        table.getTableHeader().setReorderingAllowed(true);

        CellRenderer renderer = new CellRenderer();
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(String.class, renderer);
        table.setDefaultRenderer(Boolean.class, new CheckRenderer());
        JCheckBox editorBox = new JCheckBox();
        editorBox.setHorizontalAlignment(SwingConstants.CENTER);
        editorBox.setBackground(Theme.SELECT);
        table.setDefaultEditor(Boolean.class, new DefaultCellEditor(editorBox));

        int[] widths = {46, 54, 200, 360, 110, 110, 130, 190};
        for (int i = 0; i < widths.length; i++) {
            TableColumn c = table.getColumnModel().getColumn(i);
            c.setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(C_ON).setMaxWidth(60);
        table.getColumnModel().getColumn(C_ICON).setMaxWidth(54);

        // sorting rules
        Comparator<String> ci = String.CASE_INSENSITIVE_ORDER;
        sorter.setSortable(C_ICON, false);
        sorter.setComparator(C_NAME, ci);
        sorter.setComparator(C_DESC, ci);
        sorter.setComparator(C_FILE, ci);
        sorter.setComparator(C_VER, (Comparator<Object>) (a, b) -> compareVersions((String) a, (String) b));
        sorter.setComparator(C_LATEST, (Comparator<Object>) (a, b) -> compareVersions((String) a, (String) b));
        sorter.setComparator(C_STATUS, (Comparator<Object>) (a, b) -> Integer.compare(statusRank((String) a), statusRank((String) b)));
        sorter.setSortKeys(List.of(new RowSorter.SortKey(C_NAME, SortOrder.ASCENDING)));

        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int col = table.columnAtPoint(e.getPoint());
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0 && table.convertColumnIndexToModel(col) != C_ON)
                    changeVersion();
            }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
        });
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
    }

    private static int statusRank(String label) {
        for (PluginEntry.Status s : PluginEntry.Status.values()) if (s.label.equals(label)) return s.ordinal();
        return 99;
    }

    /** Compares version strings piecewise: numeric parts numerically, others alphabetically; empty sorts last. */
    static int compareVersions(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() ? (b.isEmpty() ? 0 : 1) : -1;
        String[] x = a.split("[^A-Za-z0-9]+"), y = b.split("[^A-Za-z0-9]+");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean nx = x[i].matches("\\d{1,9}"), ny = y[i].matches("\\d{1,9}");
            int c = nx && ny ? Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]))
                    : x[i].compareToIgnoreCase(y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    private void applyFilter() {
        String t = filterField.getText().trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            sorter.setRowFilter(null);
            return;
        }
        sorter.setRowFilter(new RowFilter<>() {
            @Override public boolean include(Entry<? extends PluginTableModel, ? extends Integer> en) {
                PluginEntry p = tableModel.rows.get(en.getIdentifier());
                return p.displayName().toLowerCase(Locale.ROOT).contains(t)
                        || p.displayDescription().toLowerCase(Locale.ROOT).contains(t)
                        || p.file.getName().toLowerCase(Locale.ROOT).contains(t);
            }
        });
    }

    private PluginEntry selectedEntry() {
        int r = table.getSelectedRow();
        return r < 0 ? null : tableModel.rows.get(table.convertRowIndexToModel(r));
    }

    private final class PluginTableModel extends AbstractTableModel {
        final List<PluginEntry> rows = new ArrayList<>();

        void setRows(List<PluginEntry> list) {
            rows.clear();
            rows.addAll(list);
            fireTableDataChanged();
        }

        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return COLS.length; }
        @Override public String getColumnName(int c) { return COLS[c]; }

        @Override public Class<?> getColumnClass(int c) {
            return c == C_ON ? Boolean.class : c == C_ICON ? Object.class : String.class;
        }

        @Override public boolean isCellEditable(int r, int c) { return c == C_ON && !busy; }

        public Object getValueAt(int r, int c) {
            PluginEntry e = rows.get(r);
            switch (c) {
                case C_ON: return e.enabled;
                case C_ICON: return e;
                case C_NAME: return e.displayName();
                case C_DESC: return e.displayDescription();
                case C_VER: return e.displayVersion();
                case C_LATEST: return e.latestVersionLabel();
                case C_STATUS: return e.status.label;
                default: return e.file.getName();
            }
        }

        @Override public void setValueAt(Object v, int r, int c) {
            if (c == C_ON) toggle(rows.get(r), (Boolean) v);
        }
    }

    private Icon iconFor(PluginEntry e) {
        Runnable repaint = table::repaint;
        if (e.project != null && e.project.iconUrl != null) return icons.url(e.project.iconUrl, e.displayName(), repaint);
        if (e.embeddedIcon != null) return icons.bytes("jar:" + e.sha512, e.embeddedIcon, e.displayName(), repaint);
        return icons.placeholder(e.displayName());
    }

    private final class CellRenderer extends DefaultTableCellRenderer {
        private Font plain, bold;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            super.getTableCellRendererComponent(t, "", sel, false, row, col);
            if (plain == null) {
                plain = t.getFont().deriveFont(Font.PLAIN);
                bold = t.getFont().deriveFont(Font.BOLD);
            }
            PluginEntry e = tableModel.rows.get(t.convertRowIndexToModel(row));
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            setIcon(null);
            setToolTipText(null);
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(plain);

            Color fg = e.enabled ? Theme.FG : Theme.MUTED;
            if (col == C_ICON) {
                Icon ic = iconFor(e);
                setIcon(e.enabled ? ic : IconStore.dim(ic));
                setHorizontalAlignment(SwingConstants.CENTER);
            } else {
                String s = String.valueOf(v);
                setText(s);
                switch (col) {
                    case C_NAME:
                        setFont(bold);
                        break;
                    case C_DESC:
                        fg = e.enabled ? Theme.MUTED : new Color(0x5c6068);
                        setToolTipText(s.isEmpty() ? null : "<html><body style='width:420px'>" + esc(s) + "</body></html>");
                        break;
                    case C_LATEST:
                        if (e.enabled && !s.isEmpty()) fg = Theme.WARN;
                        break;
                    case C_STATUS:
                        if (e.enabled) {
                            if (e.status == PluginEntry.Status.UPDATE_AVAILABLE) fg = Theme.WARN;
                            else if (e.status == PluginEntry.Status.UP_TO_DATE) fg = Theme.OK;
                            else fg = Theme.MUTED;
                        }
                        break;
                    case C_FILE:
                        setToolTipText(e.file.getAbsolutePath());
                        break;
                    default:
                }
            }
            setForeground(sel ? Color.WHITE : fg);
            setBackground(sel ? Theme.SELECT : row % 2 == 0 ? Theme.BG : Theme.BG_ALT);
            return this;
        }
    }

    private static final class CheckRenderer extends JCheckBox implements javax.swing.table.TableCellRenderer {
        CheckRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setOpaque(true);
            setBorderPainted(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int row, int col) {
            setSelected(Boolean.TRUE.equals(v));
            setToolTipText("Untick to disable (renames the jar to .jar.disabled)");
            setBackground(sel ? Theme.SELECT : row % 2 == 0 ? Theme.BG : Theme.BG_ALT);
            return this;
        }
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;");
    }

    // ----------------------------------------------------- enable / disable

    private void toggle(PluginEntry e, boolean enable) {
        if (e.enabled == enable) return;
        File dir = e.file.getParentFile();
        String n = e.file.getName();
        File target = enable
                ? new File(dir, n.substring(0, n.length() - PluginEntry.DISABLED_SUFFIX.length()))
                : new File(dir, n + PluginEntry.DISABLED_SUFFIX);
        try {
            Files.move(e.file.toPath(), target.toPath()); // fails if target exists: never overwrites
            e.file = target;
            e.enabled = enable;
            statusLabel.setText((enable ? "Enabled " : "Disabled ") + e.displayName() + " — restart the server for it to take effect.");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not rename " + n + ":\n" + ex.getMessage()
                    + "\n(Is the file in use or does " + target.getName() + " already exist?)", "Enable/disable failed", JOptionPane.ERROR_MESSAGE);
        }
        int idx = tableModel.rows.indexOf(e);
        if (idx >= 0) tableModel.fireTableRowsUpdated(idx, idx);
        updateButtons();
    }

    // ------------------------------------------------------------------ prefs

    private void loadPrefs() {
        String def = new File("plugins").isDirectory() ? new File("plugins").getAbsolutePath() : "";
        folderField.setText(prefs.get("folder", def));
        loaderBox.setSelectedItem(prefs.get("loader", "Paper"));
        mcBox.setSelectedItem(prefs.get("mc", "Any"));
    }

    private void savePrefs() {
        prefs.put("folder", folderField.getText().trim());
        prefs.put("loader", String.valueOf(loaderBox.getSelectedItem()));
        prefs.put("mc", String.valueOf(mcBox.getSelectedItem()));
    }

    private void loadGameVersions() {
        new SwingWorker<List<String>, Void>() {
            @Override protected List<String> doInBackground() throws Exception { return api.gameVersions(); }
            @Override protected void done() {
                try {
                    Object keep = mcBox.getSelectedItem();
                    DefaultComboBoxModel<String> m = new DefaultComboBoxModel<>();
                    m.addElement("Any");
                    for (String v : get()) m.addElement(v);
                    mcBox.setModel(m);
                    mcBox.setSelectedItem(keep);
                } catch (Exception ignored) {
                    // offline: the box stays editable, user can type a version
                }
            }
        }.execute();
    }

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser(folderField.getText().trim().isEmpty() ? null : new File(folderField.getText().trim()));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Select the plugins (or mods) folder");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            folderField.setText(fc.getSelectedFile().getAbsolutePath());
            startScan();
        }
    }

    private String gameVersion() {
        Object o = mcBox.getSelectedItem();
        String s = o == null ? "" : o.toString().trim();
        return s.isEmpty() || s.equalsIgnoreCase("Any") ? null : s;
    }

    private List<String> loaders() {
        return LOADERS.get(String.valueOf(loaderBox.getSelectedItem()));
    }

    private File folderOrWarn() {
        File folder = new File(folderField.getText().trim());
        if (!folder.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Please select a valid plugins folder first.", "No folder", JOptionPane.WARNING_MESSAGE);
            return null;
        }
        return folder;
    }

    // ------------------------------------------------------------------- scan

    private static final class ScanResult {
        List<PluginEntry> entries = new ArrayList<>();
        String networkError;
    }

    private void startScan() {
        if (busy) return;
        final File folder = folderOrWarn();
        if (folder == null) return;
        savePrefs();
        final List<String> loaders = loaders();
        final String mc = gameVersion();
        setBusy(true);

        SwingWorker<ScanResult, String> w = new SwingWorker<>() {
            @Override protected ScanResult doInBackground() throws Exception {
                File[] jars = folder.listFiles((d, n) -> {
                    String l = n.toLowerCase(Locale.ROOT);
                    return l.endsWith(".jar") || l.endsWith(".jar" + PluginEntry.DISABLED_SUFFIX);
                });
                if (jars == null) jars = new File[0];
                Arrays.sort(jars, Comparator.comparing(f -> f.getName().toLowerCase(Locale.ROOT)));

                ScanResult res = new ScanResult();
                List<PluginEntry> out = res.entries;
                for (int i = 0; i < jars.length; i++) {
                    publish("Hashing " + jars[i].getName() + " (" + (i + 1) + "/" + jars.length + ")");
                    setProgress(i * 50 / Math.max(1, jars.length));
                    PluginEntry e = new PluginEntry(jars[i]);
                    e.sha512 = ModrinthApi.sha512(jars[i]);
                    e.applyMeta(JarMeta.read(jars[i]));
                    out.add(e);
                }
                if (out.isEmpty()) return res;

                try {
                    publish("Identifying plugins on Modrinth…");
                    setProgress(55);
                    List<String> hashes = new ArrayList<>();
                    for (PluginEntry e : out) hashes.add(e.sha512);
                    Map<String, ModVersion> ident = api.identify(hashes);
                    Set<String> projectIds = new LinkedHashSet<>();
                    List<String> known = new ArrayList<>();
                    for (PluginEntry e : out) {
                        e.current = ident.get(e.sha512);
                        if (e.current != null) {
                            projectIds.add(e.current.projectId);
                            known.add(e.sha512);
                        }
                    }

                    publish("Fetching project details…");
                    setProgress(70);
                    Map<String, Project> projects = projectIds.isEmpty() ? Map.of() : api.projects(projectIds);

                    publish("Checking for updates…");
                    setProgress(85);
                    Map<String, ModVersion> latest = known.isEmpty() ? Map.of() : api.latest(known, loaders, mc);
                    for (PluginEntry e : out) {
                        if (e.current != null) {
                            e.project = projects.get(e.current.projectId);
                            e.latest = latest.get(e.sha512);
                        }
                        e.computeStatus();
                    }
                } catch (Exception ex) {
                    // Keep the local listing; just report that Modrinth could not be reached.
                    res.networkError = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    for (PluginEntry e : out) {
                        e.current = null;
                        e.project = null;
                        e.status = PluginEntry.Status.UNCHECKED;
                    }
                }
                setProgress(100);
                return res;
            }

            @Override protected void process(List<String> chunks) {
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override protected void done() {
                setBusy(false);
                try {
                    ScanResult res = get();
                    tableModel.setRows(res.entries);
                    long upd = res.entries.stream().filter(e -> e.status == PluginEntry.Status.UPDATE_AVAILABLE).count();
                    long unk = res.entries.stream().filter(e -> e.status == PluginEntry.Status.UNKNOWN).count();
                    long off = res.entries.stream().filter(e -> !e.enabled).count();
                    statusLabel.setText(res.networkError != null
                            ? res.entries.size() + " plugin(s) listed, but Modrinth could not be reached: " + res.networkError
                            : res.entries.size() + " plugin(s), " + off + " disabled, " + upd + " update(s) available, "
                            + unk + " not found on Modrinth.");
                } catch (Exception ex) {
                    Throwable t = ex.getCause() != null ? ex.getCause() : ex;
                    statusLabel.setText("Scan failed: " + t.getMessage());
                    JOptionPane.showMessageDialog(PluginManagerApp.this, t.getMessage(), "Scan failed", JOptionPane.ERROR_MESSAGE);
                }
                updateButtons();
            }
        };
        w.addPropertyChangeListener(ev -> {
            if ("progress".equals(ev.getPropertyName())) progress.setValue((Integer) ev.getNewValue());
        });
        w.execute();
    }

    // ---------------------------------------------------------------- install

    private void openDownloader() {
        File folder = folderOrWarn();
        if (folder == null) return;
        savePrefs();
        new DownloadDialog(this, api, icons, folder, String.valueOf(loaderBox.getSelectedItem()),
                loaders(), gameVersion(), this::startScan).setVisible(true);
    }

    private void changeVersion() {
        PluginEntry e = selectedEntry();
        if (e == null) return;
        if (!e.onModrinth()) {
            JOptionPane.showMessageDialog(this, "This plugin was not found on Modrinth, so no versions are available.");
            return;
        }
        new VersionsDialog(this, api, e, loaders(), gameVersion(),
                v -> install(List.of(new AbstractMap.SimpleEntry<>(e, v)))).setVisible(true);
    }

    private void updateSelected() {
        PluginEntry e = selectedEntry();
        if (e != null && e.status == PluginEntry.Status.UPDATE_AVAILABLE)
            install(List.of(new AbstractMap.SimpleEntry<>(e, e.latest)));
    }

    private void updateAll() {
        List<Map.Entry<PluginEntry, ModVersion>> jobs = new ArrayList<>();
        for (PluginEntry e : tableModel.rows)
            if (e.status == PluginEntry.Status.UPDATE_AVAILABLE) jobs.add(new AbstractMap.SimpleEntry<>(e, e.latest));
        if (jobs.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Everything is up to date.");
            return;
        }
        int ok = JOptionPane.showConfirmDialog(this, "Update " + jobs.size() + " plugin(s)?\nOld jars are moved to .plugin-manager-backup.",
                "Update all", JOptionPane.OK_CANCEL_OPTION);
        if (ok == JOptionPane.OK_OPTION) install(jobs);
    }

    private void install(List<Map.Entry<PluginEntry, ModVersion>> jobs) {
        final File folder = new File(folderField.getText().trim());
        setBusy(true);
        SwingWorker<List<String>, String> w = new SwingWorker<>() {
            @Override protected List<String> doInBackground() {
                List<String> errors = new ArrayList<>();
                for (int i = 0; i < jobs.size(); i++) {
                    PluginEntry e = jobs.get(i).getKey();
                    ModVersion v = jobs.get(i).getValue();
                    publish("Installing " + e.displayName() + " " + v.versionNumber + " (" + (i + 1) + "/" + jobs.size() + ")");
                    final int idx = i;
                    try {
                        File nf = api.install(v, e.file, folder, p -> setProgress((int) ((idx + p) * 100 / jobs.size())));
                        if (!e.enabled) { // keep a disabled plugin disabled after updating it
                            Files.move(nf.toPath(), new File(nf.getParentFile(), nf.getName() + PluginEntry.DISABLED_SUFFIX).toPath(),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception ex) {
                        errors.add(e.displayName() + ": " + ex.getMessage());
                    }
                }
                return errors;
            }

            @Override protected void process(List<String> chunks) {
                statusLabel.setText(chunks.get(chunks.size() - 1));
            }

            @Override protected void done() {
                try {
                    List<String> errors = get();
                    if (!errors.isEmpty())
                        JOptionPane.showMessageDialog(PluginManagerApp.this, String.join("\n", errors), "Some installs failed", JOptionPane.ERROR_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(PluginManagerApp.this, ex.getMessage(), "Install failed", JOptionPane.ERROR_MESSAGE);
                }
                setBusy(false);
                startScan();
            }
        };
        w.addPropertyChangeListener(ev -> {
            if ("progress".equals(ev.getPropertyName())) progress.setValue((Integer) ev.getNewValue());
        });
        w.execute();
    }

    // --------------------------------------------------------------------- UI

    private void setBusy(boolean b) {
        busy = b;
        progress.setVisible(b);
        progress.setValue(0);
        scanBtn.setEnabled(!b);
        downloadBtn.setEnabled(!b);
        table.setEnabled(!b);
        updateButtons();
    }

    private void updateButtons() {
        PluginEntry e = selectedEntry();
        versionsBtn.setEnabled(!busy && e != null && e.onModrinth());
        updateBtn.setEnabled(!busy && e != null && e.status == PluginEntry.Status.UPDATE_AVAILABLE);
        boolean any = false;
        for (PluginEntry p : tableModel.rows) any |= p.status == PluginEntry.Status.UPDATE_AVAILABLE;
        updateAllBtn.setEnabled(!busy && any);
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger() || busy) return;
        int vr = table.rowAtPoint(e.getPoint());
        if (vr < 0) return;
        table.setRowSelectionInterval(vr, vr);
        PluginEntry p = selectedEntry();
        JPopupMenu m = new JPopupMenu();
        JMenuItem tog = new JMenuItem(p.enabled ? "Disable" : "Enable");
        tog.addActionListener(a -> toggle(p, !p.enabled));
        JMenuItem ver = new JMenuItem("Change version…");
        ver.setEnabled(p.onModrinth());
        ver.addActionListener(a -> changeVersion());
        JMenuItem web = new JMenuItem("Open on Modrinth");
        web.setEnabled(p.project != null);
        web.addActionListener(a -> {
            try {
                Desktop.getDesktop().browse(URI.create("https://modrinth.com/project/" + p.project.slug));
            } catch (Exception ex) {
                statusLabel.setText("Could not open browser: " + ex.getMessage());
            }
        });
        m.add(tog);
        m.add(ver);
        m.add(web);
        m.show(table, e.getX(), e.getY());
    }

    public static void main(String[] args) {
        System.setProperty("flatlaf.menuBarEmbedded", "true");
        if(SystemInfo.isLinux) {
            // enable custom window decorations
            JFrame.setDefaultLookAndFeelDecorated(true);
            JDialog.setDefaultLookAndFeelDecorated(true);
        }
//        Theme.apply();
        FlatDarkFlatIJTheme.setup();
        SwingUtilities.invokeLater(() -> new PluginManagerApp().setVisible(true));
    }
}
