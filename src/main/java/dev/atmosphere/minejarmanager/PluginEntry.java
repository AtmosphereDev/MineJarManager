package dev.atmosphere.minejarmanager;

import java.io.File;

/** One jar in the plugins folder together with everything we know about it. */
final class PluginEntry {
    enum Status {
        UPDATE_AVAILABLE("Update available"), UP_TO_DATE("Up to date"),
        UNKNOWN("Not on Modrinth"), UNCHECKED("Not checked");
        final String label;
        Status(String l) { label = l; }
    }

    static final String DISABLED_SUFFIX = ".disabled";

    File file;
    boolean enabled;
    String sha512;
    String localName, localDescription, localVersion;
    byte[] embeddedIcon;
    ModVersion current, latest;
    Project project;
    Status status = Status.UNCHECKED;

    PluginEntry(File file) {
        this.file = file;
        this.enabled = !file.getName().toLowerCase().endsWith(DISABLED_SUFFIX);
    }

    void applyMeta(JarMeta m) {
        localName = m.name;
        localDescription = m.description;
        localVersion = m.version;
        embeddedIcon = m.icon;
    }

    String displayName() {
        return project != null && project.title != null ? project.title : localName;
    }

    String displayDescription() {
        if (project != null && project.description != null && !project.description.isBlank())
            return project.description;
        return localDescription == null ? "" : localDescription;
    }

    String displayVersion() {
        if (current != null && current.versionNumber != null) return current.versionNumber;
        return localVersion == null ? "" : localVersion;
    }

    String latestVersionLabel() {
        return status == Status.UPDATE_AVAILABLE && latest != null ? latest.versionNumber : "";
    }

    boolean onModrinth() {
        return current != null && project != null;
    }

    void computeStatus() {
        if (current == null) {
            status = Status.UNKNOWN;
        } else if (latest != null && !latest.id.equals(current.id)
                && latest.datePublished != null && current.datePublished != null
                && latest.datePublished.compareTo(current.datePublished) > 0) {
            status = Status.UPDATE_AVAILABLE;
        } else {
            status = Status.UP_TO_DATE;
        }
    }
}
