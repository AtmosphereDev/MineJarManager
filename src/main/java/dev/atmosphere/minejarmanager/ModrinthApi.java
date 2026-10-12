package dev.atmosphere.minejarmanager;

import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.DoubleConsumer;

/** Thin client for the Modrinth v2 API. */
final class ModrinthApi {
    static final String BASE = "https://api.modrinth.com/v2";
    private static final String UA = "PluginManager/1.0 (Java Swing plugin manager)";

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    // ---------------------------------------------------------------- hashing

    static String sha512(File f) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f), 1 << 16)) {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return hex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ------------------------------------------------------------ http helper

    private Object send(HttpRequest.Builder b) throws IOException, InterruptedException {
        b.header("User-Agent", UA).timeout(Duration.ofSeconds(30));
        for (int attempt = 0; attempt < 4; attempt++) {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            int code = r.statusCode();
            if (code == 429) {
                long wait = r.headers().firstValue("Retry-After").map(Long::parseLong).orElse(2L);
                Thread.sleep(Math.min(wait, 30) * 1000L);
                continue;
            }
            if (code == 404) return null;
            if (code >= 400) throw new IOException("Modrinth API error " + code + ": " + r.body());
            return Json.parse(r.body());
        }
        throw new IOException("Modrinth API rate limit exceeded, try again in a minute");
    }

    private Object get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(BASE + path)).GET());
    }

    private Object post(String path, Object body) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8)));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static <T> List<List<T>> chunks(Collection<T> c, int size) {
        List<List<T>> out = new ArrayList<>();
        List<T> all = new ArrayList<>(c);
        for (int i = 0; i < all.size(); i += size) out.add(all.subList(i, Math.min(all.size(), i + size)));
        return out;
    }

    // -------------------------------------------------------------- endpoints

    /** Identify files: hash -> the Modrinth version that file belongs to. */
    Map<String, ModVersion> identify(Collection<String> hashes) throws IOException, InterruptedException {
        Map<String, ModVersion> out = new HashMap<>();
        for (List<String> part : chunks(hashes, 100)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("hashes", part);
            body.put("algorithm", "sha512");
            Object res = post("/version_files", body);
            for (Map.Entry<String, Object> e : Json.obj(res).entrySet())
                out.put(e.getKey(), ModVersion.from(e.getValue()));
        }
        return out;
    }

    /** Latest compatible version for each file hash (matching loaders / MC version). */
    Map<String, ModVersion> latest(Collection<String> hashes, List<String> loaders, String gameVersion)
            throws IOException, InterruptedException {
        Map<String, ModVersion> out = new HashMap<>();
        for (List<String> part : chunks(hashes, 100)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("hashes", part);
            body.put("algorithm", "sha512");
            body.put("loaders", loaders);
            if (gameVersion != null) body.put("game_versions", List.of(gameVersion));
            Object res = post("/version_files/update", body);
            for (Map.Entry<String, Object> e : Json.obj(res).entrySet())
                out.put(e.getKey(), ModVersion.from(e.getValue()));
        }
        return out;
    }

    Map<String, Project> projects(Collection<String> ids) throws IOException, InterruptedException {
        Map<String, Project> out = new HashMap<>();
        for (List<String> part : chunks(ids, 50)) {
            Object res = get("/projects?ids=" + enc(Json.stringify(part)));
            for (Object o : Json.arr(res)) {
                Project p = Project.from(o);
                out.put(p.id, p);
            }
        }
        return out;
    }

    List<ModVersion> projectVersions(String projectId, List<String> loaders, String gameVersion)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder("/project/" + enc(projectId) + "/version");
        List<String> params = new ArrayList<>();
        if (loaders != null && !loaders.isEmpty()) params.add("loaders=" + enc(Json.stringify(loaders)));
        if (gameVersion != null) params.add("game_versions=" + enc(Json.stringify(List.of(gameVersion))));
        if (!params.isEmpty()) q.append('?').append(String.join("&", params));
        List<ModVersion> out = new ArrayList<>();
        for (Object o : Json.arr(get(q.toString()))) out.add(ModVersion.from(o));
        return out;
    }

    static final class SearchResult {
        final List<Project> hits = new ArrayList<>();
        long total;
    }

    /**
     * Searches mods/plugins. {@code loaders} (any-of) and {@code gameVersion} are optional filters;
     * {@code index} is one of relevance, downloads, follows, newest, updated.
     */
    SearchResult search(String query, List<String> loaders, String gameVersion, String index, int offset, int limit)
            throws IOException, InterruptedException {
        List<List<String>> facets = new ArrayList<>();
        if (loaders != null && !loaders.isEmpty()) {
            List<String> l = new ArrayList<>();
            for (String s : loaders) l.add("categories:" + s);
            facets.add(l);
        }
        facets.add(List.of("project_type:mod", "project_type:plugin"));
        if (gameVersion != null) facets.add(List.of("versions:" + gameVersion));
        String path = "/search?query=" + enc(query) + "&facets=" + enc(Json.stringify(facets))
                + "&index=" + enc(index) + "&offset=" + offset + "&limit=" + limit;
        Object res = get(path);
        SearchResult out = new SearchResult();
        out.total = Json.num(res, "total_hits");
        for (Object o : Json.arr(Json.obj(res).get("hits"))) out.hits.add(Project.fromSearchHit(o));
        return out;
    }

    /** Release game versions, newest first. */
    List<String> gameVersions() throws IOException, InterruptedException {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(get("/tag/game_version")))
            if ("release".equals(Json.str(o, "version_type"))) out.add(Json.str(o, "version"));
        return out;
    }

    // --------------------------------------------------------------- download

    /** Downloads to dest, verifying the SHA-512 when one is supplied. */
    void download(String url, File dest, String expectedSha512, DoubleConsumer progress)
            throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", UA).GET().build();
        HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() >= 400) throw new IOException("Download failed (HTTP " + r.statusCode() + ")");
        long total = r.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = r.body(); OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                md.update(buf, 0, n);
                done += n;
                if (total > 0 && progress != null) progress.accept((double) done / total);
            }
            out.flush();
            String got = hex(md.digest());
            if (expectedSha512 != null && !expectedSha512.equalsIgnoreCase(got)) {
                throw new IOException("Hash mismatch for downloaded file; aborting install");
            }
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        } catch (IOException e) {
            Files.deleteIfExists(dest.toPath());
            throw e;
        }
    }

    /**
     * Downloads {@code v}'s primary file next to the old jar, verifies it, backs up the old jar
     * (into .plugin-manager-backup) and puts the new jar in place. Returns the new file.
     */
    File install(ModVersion v, File oldJar, File folder, DoubleConsumer progress)
            throws IOException, InterruptedException {
        ModVersion.FileInfo f = v.primaryFile();
        if (f == null) throw new IOException("Version " + v.versionNumber + " has no files");
        String safeName = new File(f.filename).getName();
        File part = new File(folder, safeName + ".part");
        download(f.url, part, f.sha512, progress);
        File target = new File(folder, safeName);
        if (oldJar != null && oldJar.exists()) {
            File backupDir = new File(folder, ".plugin-manager-backup");
            Files.createDirectories(backupDir.toPath());
            File backup = new File(backupDir, oldJar.getName() + "." + System.currentTimeMillis() + ".bak");
            Files.move(oldJar.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
