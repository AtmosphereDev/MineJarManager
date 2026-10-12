package dev.atmosphere.minejarmanager;

import java.io.*;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads local metadata (name, version, description, icon) from a plugin/mod jar. */
final class JarMeta {
    String name, version, description;
    byte[] icon;

    static JarMeta read(File jar) {
        JarMeta m = new JarMeta();
        try (ZipFile zip = new ZipFile(jar)) {
            if (tryFabricLike(zip, "fabric.mod.json", m)) return m.finish(jar);
            if (tryFabricLike(zip, "quilt.mod.json", m)) return m.finish(jar);
            String yml = text(zip, "paper-plugin.yml");
            if (yml == null) yml = text(zip, "plugin.yml");
            if (yml == null) yml = text(zip, "bungee.yml");
            if (yml != null) {
                m.name = yamlValue(yml, "name");
                m.version = yamlValue(yml, "version");
                m.description = yamlValue(yml, "description");
                return m.finish(jar);
            }
            String toml = text(zip, "META-INF/neoforge.mods.toml");
            if (toml == null) toml = text(zip, "META-INF/mods.toml");
            if (toml != null) {
                m.name = first(toml, "displayName\\s*=\\s*\"([^\"]*)\"");
                m.version = first(toml, "(?m)^\\s*version\\s*=\\s*\"([^\"]*)\"");
                m.description = first(toml, "description\\s*=\\s*'''([\\s\\S]*?)'''");
                if (m.description == null) m.description = first(toml, "description\\s*=\\s*\"([^\"]*)\"");
                String logo = first(toml, "logoFile\\s*=\\s*\"([^\"]*)\"");
                if (logo != null) m.icon = bytes(zip, logo);
            }
            String vlt = text(zip, "velocity-plugin.json");
            if (vlt != null) {
                Object j = Json.parse(vlt);
                m.name = Json.str(j, "name");
                m.version = Json.str(j, "version");
                m.description = Json.str(j, "description");
            }
        } catch (Exception ignored) {
            // unreadable jar: fall back to the file name
        }
        return m.finish(jar);
    }

    private JarMeta finish(File jar) {
        if (name == null || name.isBlank()) name = jar.getName().replaceAll("(?i)\\.jar(\\.disabled)?$", "");
        if (description != null) description = description.trim().replaceAll("\\s+", " ");
        return this;
    }

    private static boolean tryFabricLike(ZipFile zip, String entry, JarMeta m) {
        String s = text(zip, entry);
        if (s == null) return false;
        try {
            Object j = Json.parse(s);
            Map<String, Object> root = Json.obj(j);
            Map<String, Object> info = root.containsKey("quilt_loader")
                    ? Json.obj(Json.obj(root.get("quilt_loader")).get("metadata")) : root;
            m.name = Json.str(info, "name");
            m.description = Json.str(info, "description");
            m.version = Json.str(root.containsKey("quilt_loader") ? root.get("quilt_loader") : root, "version");
            Object icon = info.get("icon");
            String path = null;
            if (icon instanceof String) path = (String) icon;
            else if (icon instanceof Map) {
                int best = -1;
                for (Map.Entry<String, Object> e : Json.obj(icon).entrySet()) {
                    try {
                        int sz = Integer.parseInt(e.getKey());
                        if (sz > best) { best = sz; path = String.valueOf(e.getValue()); }
                    } catch (NumberFormatException ignored) {}
                }
            }
            if (path != null) m.icon = bytes(zip, path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String text(ZipFile zip, String name) {
        byte[] b = bytes(zip, name);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] bytes(ZipFile zip, String name) {
        try {
            if (name.startsWith("/")) name = name.substring(1);
            ZipEntry e = zip.getEntry(name);
            if (e == null || e.getSize() > 8_000_000) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        } catch (IOException ex) {
            return null;
        }
    }

    private static String first(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Very small YAML top-level scalar reader (enough for plugin.yml). */
    private static String yamlValue(String yml, String key) {
        String[] lines = yml.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (!l.startsWith(key + ":")) continue;
            String v = l.substring(key.length() + 1).trim();
            if (v.equals(">") || v.equals("|") || v.equals(">-") || v.equals("|-")) {
                StringBuilder sb = new StringBuilder();
                for (int j = i + 1; j < lines.length && (lines[j].startsWith(" ") || lines[j].isBlank()); j++)
                    sb.append(lines[j].trim()).append(' ');
                return sb.toString().trim();
            }
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'")))
                v = v.substring(1, v.length() - 1);
            return v;
        }
        return null;
    }
}
