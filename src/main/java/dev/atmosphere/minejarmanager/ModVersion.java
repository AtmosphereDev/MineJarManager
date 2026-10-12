package dev.atmosphere.minejarmanager;

import java.util.ArrayList;
import java.util.List;

/** A Modrinth version (a single release of a project). */
final class ModVersion {
    static final class FileInfo {
        String url, filename, sha512, sha1;
        boolean primary;
        long size;
    }

    String id, projectId, versionNumber, name, versionType, datePublished;
    List<String> gameVersions = new ArrayList<>();
    List<String> loaders = new ArrayList<>();
    List<FileInfo> files = new ArrayList<>();

    static ModVersion from(Object json) {
        if (json == null) return null;
        ModVersion v = new ModVersion();
        v.id = Json.str(json, "id");
        v.projectId = Json.str(json, "project_id");
        v.versionNumber = Json.str(json, "version_number");
        v.name = Json.str(json, "name");
        v.versionType = Json.str(json, "version_type");
        v.datePublished = Json.str(json, "date_published");
        v.gameVersions = Json.strings(Json.obj(json).get("game_versions"));
        v.loaders = Json.strings(Json.obj(json).get("loaders"));
        for (Object f : Json.arr(Json.obj(json).get("files"))) {
            FileInfo fi = new FileInfo();
            fi.url = Json.str(f, "url");
            fi.filename = Json.str(f, "filename");
            fi.primary = Json.bool(f, "primary");
            fi.size = Json.num(f, "size");
            Object hashes = Json.obj(f).get("hashes");
            fi.sha512 = Json.str(hashes, "sha512");
            fi.sha1 = Json.str(hashes, "sha1");
            v.files.add(fi);
        }
        return v;
    }

    FileInfo primaryFile() {
        for (FileInfo f : files) if (f.primary) return f;
        return files.isEmpty() ? null : files.get(0);
    }

    @Override
    public String toString() {
        return versionNumber;
    }
}
