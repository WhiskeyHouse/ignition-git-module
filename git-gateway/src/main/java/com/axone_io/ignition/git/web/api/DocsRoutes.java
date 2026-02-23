package com.axone_io.ignition.git.web.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.SecurityZoneAccessControlStrategy;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Route handlers for serving project documentation (.md files).
 * All routes use SecurityZoneAccessControlStrategy.ZONE_READ for authentication.
 */
public class DocsRoutes {
    private static final Logger logger = LoggerFactory.getLogger(DocsRoutes.class);
    private static final Gson gson = new Gson();
    private static final long MAX_FILE_SIZE = 1024 * 1024; // 1MB
    private static final Set<String> MD_EXTENSIONS = Set.of(".md", ".markdown", ".mdown");

    public static void mountRoutes(RouteGroup routes) {
        logger.info("DocsRoutes.mountRoutes called - mounting documentation routes");

        // List projects that contain .md files
        routes.newRoute("/docs/projects")
            .type(RouteGroup.TYPE_JSON)
            .handler(DocsRoutes::listProjects)
            .accessControl(SecurityZoneAccessControlStrategy.ZONE_READ)
            .mount();

        // Get file tree of .md files in a project
        routes.newRoute("/docs/tree/:projectName")
            .type(RouteGroup.TYPE_JSON)
            .handler(DocsRoutes::getTree)
            .accessControl(SecurityZoneAccessControlStrategy.ZONE_READ)
            .mount();

        // Get raw markdown content of a file
        routes.newRoute("/docs/content/:projectName")
            .type(RouteGroup.TYPE_JSON)
            .handler(DocsRoutes::getContent)
            .accessControl(SecurityZoneAccessControlStrategy.ZONE_READ)
            .mount();

        // Deep-link redirect: stores params in sessionStorage then navigates to config page
        routes.newRoute("/docs/redirect")
            .type(RouteGroup.TYPE_PLAIN_TEXT)
            .handler(DocsRoutes::redirectToViewer)
            .accessControl(SecurityZoneAccessControlStrategy.ZONE_READ)
            .mount();

        logger.info("DocsRoutes.mountRoutes completed");
    }

    private static Path getProjectsDir(RequestContext req) {
        return req.getGatewayContext().getSystemManager().getDataDir().toPath().resolve("projects");
    }

    private static boolean isMarkdownFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return MD_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /**
     * GET /docs/projects - List project directories that contain at least one .md file.
     */
    private static Object listProjects(RequestContext req, HttpServletResponse res) {
        try {
            Path projectsDir = getProjectsDir(req);
            if (!Files.isDirectory(projectsDir)) {
                return new JsonArray();
            }

            JsonArray result = new JsonArray();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(projectsDir)) {
                for (Path entry : stream) {
                    if (!Files.isDirectory(entry)) continue;
                    String dirName = entry.getFileName().toString();
                    if (dirName.startsWith(".")) continue;

                    if (containsMarkdown(entry)) {
                        JsonObject project = new JsonObject();
                        project.addProperty("name", dirName);
                        result.add(project);
                    }
                }
            }

            return result;
        } catch (Exception e) {
            logger.error("Error listing docs projects", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static boolean containsMarkdown(Path dir) throws IOException {
        try (var stream = Files.walk(dir, 10)) {
            return stream.anyMatch(p -> {
                if (!Files.isRegularFile(p)) return false;
                // Skip .git directories
                for (Path component : dir.relativize(p)) {
                    if (component.toString().equals(".git")) return false;
                }
                return isMarkdownFile(p);
            });
        }
    }

    /**
     * GET /docs/tree/:projectName - Nested tree of .md files in a project.
     */
    private static Object getTree(RequestContext req, HttpServletResponse res) {
        try {
            String projectName = req.getParameter("projectName");
            Path projectsDir = getProjectsDir(req);
            Path projectDir = projectsDir.resolve(projectName).normalize();

            // Path traversal protection
            if (!projectDir.startsWith(projectsDir)) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Invalid project name");
                return error;
            }

            if (!Files.isDirectory(projectDir)) {
                res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Project not found");
                return error;
            }

            JsonObject tree = buildTree(projectDir, projectDir);
            return tree;
        } catch (Exception e) {
            logger.error("Error building docs tree", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static JsonObject buildTree(Path root, Path dir) throws IOException {
        JsonObject node = new JsonObject();
        node.addProperty("name", dir.getFileName().toString());
        node.addProperty("type", "directory");
        node.addProperty("path", root.equals(dir) ? "" : root.relativize(dir).toString());

        JsonArray children = new JsonArray();

        // Collect and sort entries: directories first, then files, both alphabetically
        List<Path> dirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (name.equals(".git") || name.startsWith(".")) continue;

                if (Files.isDirectory(entry)) {
                    dirs.add(entry);
                } else if (Files.isRegularFile(entry) && isMarkdownFile(entry)) {
                    files.add(entry);
                }
            }
        }

        dirs.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));
        files.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()));

        for (Path d : dirs) {
            JsonObject subtree = buildTree(root, d);
            // Only include directories that contain markdown files
            if (subtree.getAsJsonArray("children").size() > 0) {
                children.add(subtree);
            }
        }

        for (Path f : files) {
            JsonObject fileNode = new JsonObject();
            fileNode.addProperty("name", f.getFileName().toString());
            fileNode.addProperty("type", "file");
            fileNode.addProperty("path", root.relativize(f).toString());
            children.add(fileNode);
        }

        node.add("children", children);
        return node;
    }

    /**
     * GET /docs/content/:projectName?path=X - Raw markdown content for a file.
     */
    private static Object getContent(RequestContext req, HttpServletResponse res) {
        try {
            String projectName = req.getParameter("projectName");
            String filePath = req.getRequest().getParameter("path");

            if (filePath == null || filePath.isEmpty()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Missing 'path' query parameter");
                return error;
            }

            Path projectsDir = getProjectsDir(req);
            Path projectDir = projectsDir.resolve(projectName).normalize();
            Path target = projectDir.resolve(filePath).normalize();

            // Path traversal protection
            if (!target.startsWith(projectDir)) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Invalid file path");
                return error;
            }

            // Only serve markdown files
            if (!isMarkdownFile(target)) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Only markdown files can be served");
                return error;
            }

            if (!Files.isRegularFile(target)) {
                res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                JsonObject error = new JsonObject();
                error.addProperty("error", "File not found");
                return error;
            }

            // Size check
            long size = Files.size(target);
            if (size > MAX_FILE_SIZE) {
                res.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                JsonObject error = new JsonObject();
                error.addProperty("error", "File exceeds 1MB limit");
                return error;
            }

            String content = Files.readString(target, StandardCharsets.UTF_8);
            JsonObject result = new JsonObject();
            result.addProperty("content", content);
            result.addProperty("path", filePath);
            result.addProperty("project", projectName);
            return result;
        } catch (Exception e) {
            logger.error("Error reading docs content", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    /**
     * GET /docs/redirect?project=X&path=Y - Serves a small HTML page that stores
     * deep-link params in sessionStorage, then redirects to the config page.
     * This bypasses Ignition's SPA routing which strips hash/query params.
     */
    private static Object redirectToViewer(RequestContext req, HttpServletResponse res) {
        try {
            res.setContentType("text/html; charset=UTF-8");
            res.getWriter().write(
                "<!DOCTYPE html><html><head><title>Loading docs...</title></head><body>" +
                "<p>Loading documentation...</p>" +
                "<script>" +
                "(function(){" +
                "var p=new URLSearchParams(window.location.search);" +
                "var d={project:p.get('project')||'',path:p.get('path')||''};" +
                "if(d.project||d.path){sessionStorage.setItem('git-docs-deeplink',JSON.stringify(d));}" +
                "window.location.replace('/web/config/git/docs');" +
                "})();" +
                "</script>" +
                "<noscript><a href=\"/web/config/git/docs\">Click here to view docs</a></noscript>" +
                "</body></html>"
            );
            res.getWriter().flush();
            return null;
        } catch (IOException e) {
            logger.error("Error serving docs redirect", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            return "Error redirecting to docs viewer";
        }
    }
}
