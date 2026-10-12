package dev.atmosphere.minejarmanager;

/** A Modrinth project (title, description, icon) — also used for search hits. */
final class Project {
    String id, slug, title, description, iconUrl, projectType, author;
    long downloads;

    static Project from(Object json) {
        Project p = new Project();
        p.id = Json.str(json, "id");
        p.slug = Json.str(json, "slug");
        p.title = Json.str(json, "title");
        p.description = Json.str(json, "description");
        p.iconUrl = Json.str(json, "icon_url");
        p.projectType = Json.str(json, "project_type");
        p.downloads = Json.num(json, "downloads");
        return p;
    }

    /** Search results use "project_id" and carry the author name. */
    static Project fromSearchHit(Object json) {
        Project p = from(json);
        p.id = Json.str(json, "project_id");
        p.author = Json.str(json, "author");
        return p;
    }
}
