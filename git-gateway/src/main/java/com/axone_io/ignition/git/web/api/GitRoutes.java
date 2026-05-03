package com.axone_io.ignition.git.web.api;

import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.axone_io.ignition.git.records.GitReposUsersRecord;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.dataroutes.HttpMethod;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.dataroutes.AccessControlStrategy;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simpleorm.dataset.SQuery;

import java.io.BufferedReader;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Route handlers for Git configuration API endpoints.
 *
 * <p>All routes use {@link AccessControlStrategy#OPEN_ROUTE} because
 * {@code SecurityZoneAccessControlStrategy} causes 401 errors (session context
 * doesn't carry security zone attributes for custom module routes).
 *
 * <p><b>Security note:</b> All routes use OPEN_ROUTE because Ignition's
 * {@code HttpSession} is not reliably available in data route handlers — even
 * when the user is logged into the Gateway, {@code getSession(false)} may
 * return null for data route requests.
 * <ul>
 *   <li>GET endpoints rely on Ignition's navigation model auth (Gateway login
 *       required to access config pages that call these endpoints).</li>
 *   <li>POST/PUT/DELETE endpoints are additionally wrapped with
 *       {@link RouteSecurityHelper#requireAuthenticationAndCsrf} which uses
 *       {@code getSession(true)} to create a session for CSRF token management.</li>
 * </ul>
 */
public class GitRoutes {
    private static final Logger logger = LoggerFactory.getLogger(GitRoutes.class);
    private static final Gson gson = new Gson();

    public static void mountRoutes(RouteGroup routes) {
        logger.info("GitRoutes.mountRoutes called - starting to mount routes");

        // CSRF token endpoint - frontend must call this first to obtain a token
        // for use in mutation requests (POST/PUT/DELETE) via the X-CSRF-Token header.
        routes.newRoute("/csrf-token")
            .type(RouteGroup.TYPE_JSON)
            .handler(RouteSecurityHelper::getCsrfToken)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        // Projects routes - GET endpoints use OPEN_ROUTE without additional auth checks.
        // Ignition's navigation model already requires gateway login to access config pages.
        // HttpSession-based auth doesn't work reliably for data routes (session may not exist).
        routes.newRoute("/projects")
            .type(RouteGroup.TYPE_JSON)
            .handler(GitRoutes::getProjects)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute("/projects/:id")
            .type(RouteGroup.TYPE_JSON)
            .handler(GitRoutes::getProject)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute("/projects")
            .type(RouteGroup.TYPE_JSON)
            .method(HttpMethod.POST)
            .handler(RouteSecurityHelper.requireAuthenticationAndCsrf(GitRoutes::createProject))
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute("/projects/:id")
            .type(RouteGroup.TYPE_JSON)
            .method(HttpMethod.PUT)
            .handler(RouteSecurityHelper.requireAuthenticationAndCsrf(GitRoutes::updateProject))
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute("/projects/:id")
            .type(RouteGroup.TYPE_JSON)
            .method(HttpMethod.DELETE)
            .handler(RouteSecurityHelper.requireAuthenticationAndCsrf(GitRoutes::deleteProject))
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        // Users routes - GET endpoint uses OPEN_ROUTE (same rationale as projects above)
        routes.newRoute("/users")
            .type(RouteGroup.TYPE_JSON)
            .handler(GitRoutes::getUsers)
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute("/users")
            .type(RouteGroup.TYPE_JSON)
            .method(HttpMethod.POST)
            .handler(RouteSecurityHelper.requireAuthenticationAndCsrf(GitRoutes::createUser))
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute("/users/:id")
            .type(RouteGroup.TYPE_JSON)
            .method(HttpMethod.PUT)
            .handler(RouteSecurityHelper.requireAuthenticationAndCsrf(GitRoutes::updateUser))
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        routes.newRoute("/users/:id")
            .type(RouteGroup.TYPE_JSON)
            .method(HttpMethod.DELETE)
            .handler(RouteSecurityHelper.requireAuthenticationAndCsrf(GitRoutes::deleteUser))
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        // Test route
        routes.newRoute("/test")
            .type(RouteGroup.TYPE_PLAIN_TEXT)
            .handler((req, res) -> "Git module routes are working!")
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        // Admin cleanup endpoint to truncate GitReposUsersRecord table
        // This uses JDBC to bypass ORM deserialization of corrupted encrypted fields
        routes.newRoute("/admin/cleanup-users")
            .type(RouteGroup.TYPE_JSON)
            .method(HttpMethod.POST)
            .handler(RouteSecurityHelper.requireAuthenticationAndCsrf((req, res) -> {
                try {
                    logger.info("Admin cleanup: Deleting all GitReposUsersRecord entries");
                    var context = req.getGatewayContext();

                    // Use JDBC to execute raw SQL and bypass ORM
                    var datasource = context.getDatasourceManager().getDatasource("config");
                    try (var conn = datasource.getConnection()) {
                        try (var stmt = conn.createStatement()) {
                            int deleted = stmt.executeUpdate("DELETE FROM GITREPOSUSERSRECORD");
                            logger.info("Deleted " + deleted + " corrupted Git user records");

                            JsonObject response = new JsonObject();
                            response.addProperty("success", true);
                            response.addProperty("message", "Deleted " + deleted + " records from GitReposUsersRecord");
                            response.addProperty("deleted", deleted);
                            return response;
                        }
                    }
                } catch (Exception e) {
                    logger.error("Admin cleanup failed", e);
                    res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "Cleanup failed: " + e.getMessage());
                    return error;
                }
            }))
            .accessControl(AccessControlStrategy.OPEN_ROUTE)
            .mount();

        logger.info("GitRoutes.mountRoutes completed - all routes mounted successfully");
    }

    // Project handlers
    private static Object getProjects(RequestContext req, HttpServletResponse res) {
        try {
            List<GitProjectsConfigRecord> projects = req.getGatewayContext()
                    .getPersistenceInterface()
                    .query(new SQuery<>(GitProjectsConfigRecord.META));

            return projects.stream()
                    .map(p -> {
                        JsonObject obj = new JsonObject();
                        obj.addProperty("id", p.getId());
                        obj.addProperty("projectName", p.getProjectName());
                        obj.addProperty("uri", p.getURI());
                        obj.addProperty("productionMode", p.getProductionMode());
                        obj.addProperty("productionBranch", p.getProductionBranch());
                        obj.addProperty("productionTagPattern", p.getProductionTagPattern());
                        return obj;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching projects", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static Object getProject(RequestContext req, HttpServletResponse res) {
        try {
            long id = Long.parseLong(req.getParameter("id"));
            GitProjectsConfigRecord project = req.getGatewayContext()
                    .getPersistenceInterface()
                    .find(GitProjectsConfigRecord.META, id);

            if (project == null) {
                res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Project not found");
                return error;
            }

            JsonObject obj = new JsonObject();
            obj.addProperty("id", project.getId());
            obj.addProperty("projectName", project.getProjectName());
            obj.addProperty("uri", project.getURI());
            obj.addProperty("productionMode", project.getProductionMode());
            obj.addProperty("productionBranch", project.getProductionBranch());
            obj.addProperty("productionTagPattern", project.getProductionTagPattern());
            return obj;
        } catch (Exception e) {
            logger.error("Error fetching project", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static Object createProject(RequestContext req, HttpServletResponse res) {
        try {
            BufferedReader reader = req.getRequest().getReader();
            JsonObject body = gson.fromJson(reader, JsonObject.class);

            // Validate request body
            if (body == null) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Request body is required");
                return error;
            }

            // Validate required fields
            if (!body.has("projectName") || !body.get("projectName").isJsonPrimitive()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Missing or invalid required field: projectName");
                return error;
            }

            if (!body.has("uri") || !body.get("uri").isJsonPrimitive()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Missing or invalid required field: uri");
                return error;
            }

            String projectName = body.get("projectName").getAsString();
            String uri = body.get("uri").getAsString();

            if (projectName == null || projectName.trim().isEmpty()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Field projectName cannot be empty");
                return error;
            }

            if (uri == null || uri.trim().isEmpty()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Field uri cannot be empty");
                return error;
            }

            GitProjectsConfigRecord record = req.getGatewayContext()
                    .getPersistenceInterface()
                    .createNew(GitProjectsConfigRecord.META);

            record.setProjectName(projectName);
            record.setURI(uri);
            applyProductionFields(record, body);

            req.getGatewayContext().getPersistenceInterface().save(record);

            res.setStatus(HttpServletResponse.SC_CREATED);
            JsonObject response = new JsonObject();
            response.addProperty("id", record.getId());
            response.addProperty("projectName", record.getProjectName());
            response.addProperty("uri", record.getURI());
            response.addProperty("productionMode", record.getProductionMode());
            response.addProperty("productionBranch", record.getProductionBranch());
            response.addProperty("productionTagPattern", record.getProductionTagPattern());
            return response;
        } catch (Exception e) {
            logger.error("Error creating project", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static Object updateProject(RequestContext req, HttpServletResponse res) {
        try {
            long id = Long.parseLong(req.getParameter("id"));
            BufferedReader reader = req.getRequest().getReader();
            JsonObject body = gson.fromJson(reader, JsonObject.class);

            // Validate request body
            if (body == null) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Request body is required");
                return error;
            }

            // Validate required fields
            if (!body.has("projectName") || !body.get("projectName").isJsonPrimitive()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Missing or invalid required field: projectName");
                return error;
            }

            if (!body.has("uri") || !body.get("uri").isJsonPrimitive()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Missing or invalid required field: uri");
                return error;
            }

            String projectName = body.get("projectName").getAsString();
            String uri = body.get("uri").getAsString();

            if (projectName == null || projectName.trim().isEmpty()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Field projectName cannot be empty");
                return error;
            }

            if (uri == null || uri.trim().isEmpty()) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Field uri cannot be empty");
                return error;
            }

            GitProjectsConfigRecord record = req.getGatewayContext()
                    .getPersistenceInterface()
                    .find(GitProjectsConfigRecord.META, id);

            if (record == null) {
                res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Project not found");
                return error;
            }

            record.setProjectName(projectName);
            record.setURI(uri);
            applyProductionFields(record, body);

            req.getGatewayContext().getPersistenceInterface().save(record);

            JsonObject response = new JsonObject();
            response.addProperty("id", record.getId());
            response.addProperty("projectName", record.getProjectName());
            response.addProperty("uri", record.getURI());
            response.addProperty("productionMode", record.getProductionMode());
            response.addProperty("productionBranch", record.getProductionBranch());
            response.addProperty("productionTagPattern", record.getProductionTagPattern());
            return response;
        } catch (Exception e) {
            logger.error("Error updating project", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static Object deleteProject(RequestContext req, HttpServletResponse res) {
        try {
            long id = Long.parseLong(req.getParameter("id"));
            GitProjectsConfigRecord record = req.getGatewayContext()
                    .getPersistenceInterface()
                    .find(GitProjectsConfigRecord.META, id);

            if (record == null) {
                res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Project not found");
                return error;
            }

            record.deleteRecord();
            req.getGatewayContext().getPersistenceInterface().save(record);

            res.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return null;
        } catch (Exception e) {
            logger.error("Error deleting project", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static void applyProductionFields(GitProjectsConfigRecord record, JsonObject body) {
        if (body.has("productionMode") && body.get("productionMode").isJsonPrimitive()) {
            record.setProductionMode(body.get("productionMode").getAsBoolean());
        }
        if (body.has("productionBranch") && body.get("productionBranch").isJsonPrimitive()) {
            String branch = body.get("productionBranch").getAsString();
            record.setProductionBranch(branch == null || branch.trim().isEmpty() ? null : branch.trim());
        } else if (body.has("productionBranch") && body.get("productionBranch").isJsonNull()) {
            record.setProductionBranch(null);
        }
        if (body.has("productionTagPattern") && body.get("productionTagPattern").isJsonPrimitive()) {
            String pattern = body.get("productionTagPattern").getAsString();
            record.setProductionTagPattern(pattern == null || pattern.trim().isEmpty() ? null : pattern.trim());
        } else if (body.has("productionTagPattern") && body.get("productionTagPattern").isJsonNull()) {
            record.setProductionTagPattern(null);
        }
    }

    // User handlers
    private static Object getUsers(RequestContext req, HttpServletResponse res) {
        try {
            logger.info("Starting getUsers request");

            // Query with raw SQL to avoid encrypted field issues during migration
            List<GitReposUsersRecord> users;
            try {
                // Try normal query first
                users = req.getGatewayContext()
                        .getPersistenceInterface()
                        .query(new SQuery<>(GitReposUsersRecord.META));
            } catch (Exception e) {
                // If encrypted fields cause issues, return helpful error
                String errorMsg = e.getMessage();
                if (errorMsg != null && (errorMsg.contains("Bad Hex") || errorMsg.contains("hex"))) {
                    logger.error("Database contains corrupted encrypted fields. You must delete all Git users and recreate them.", e);
                    res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "Database migration required. Run: DELETE FROM GitReposUsersRecord; " +
                            "Then refresh this page and recreate your Git users with proper credentials.");
                    error.addProperty("sqlCommand", "DELETE FROM GitReposUsersRecord;");
                    return error;
                }
                throw e;
            }

            logger.info("Found {} users in database", users.size());

            // Build project name lookup map
            List<GitProjectsConfigRecord> projects = req.getGatewayContext()
                    .getPersistenceInterface()
                    .query(new SQuery<>(GitProjectsConfigRecord.META));

            java.util.Map<Long, String> projectNames = projects.stream()
                    .collect(Collectors.toMap(
                            GitProjectsConfigRecord::getId,
                            GitProjectsConfigRecord::getProjectName
                    ));

            logger.info("Found {} projects in database", projectNames.size());

            logger.info("Starting to map {} users to JSON", users.size());
            return users.stream()
                    .map(u -> {
                        try {
                            JsonObject obj = new JsonObject();
                            obj.addProperty("id", u.getId());

                            // Resolve project name from projectId
                            String projectName = projectNames.get((long) u.getProjectId());
                        if (projectName == null) {
                            logger.warn("Project name not found for user id={}, projectId={}",
                                    u.getId(), u.getProjectId());
                            projectName = "";
                        }
                        obj.addProperty("projectName", projectName);

                        obj.addProperty("ignitionUser", u.getIgnitionUser() != null ? u.getIgnitionUser() : "");
                        obj.addProperty("userName", u.getUserName() != null ? u.getUserName() : "");
                        obj.addProperty("email", u.getEmail() != null ? u.getEmail() : "");
                        // Security: Never expose actual credentials - use boolean flags instead
                        obj.addProperty("hasPassword", u.getPassword() != null && !u.getPassword().isEmpty());
                        obj.addProperty("hasSshKey", u.getSSHKey() != null && !u.getSSHKey().isEmpty());

                            return obj;
                        } catch (Exception e) {
                            logger.error("Error mapping user id={}: {}", u.getId(), e.getMessage(), e);
                            throw new RuntimeException("Error mapping user", e);
                        }
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error fetching users", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static Object createUser(RequestContext req, HttpServletResponse res) {
        try {
            BufferedReader reader = req.getRequest().getReader();
            JsonObject body = gson.fromJson(reader, JsonObject.class);

            // Find project by name
            String projectName = body.get("projectName").getAsString();
            SQuery<GitProjectsConfigRecord> projectQuery = new SQuery<>(GitProjectsConfigRecord.META)
                    .eq(GitProjectsConfigRecord.ProjectName, projectName);
            GitProjectsConfigRecord project = req.getGatewayContext()
                    .getPersistenceInterface()
                    .queryOne(projectQuery);

            if (project == null) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                JsonObject error = new JsonObject();
                error.addProperty("error", "Project not found: " + projectName);
                return error;
            }

            // Create new user record
            GitReposUsersRecord record = req.getGatewayContext()
                    .getPersistenceInterface()
                    .createNew(GitReposUsersRecord.META);

            record.setProjectId(project.getId());
            record.setIgnitionUser(body.get("ignitionUser").getAsString());
            record.setUserName(body.has("userName") ? body.get("userName").getAsString() : "");
            record.setEmail(body.get("email").getAsString());
            record.setPassword(body.has("password") ? body.get("password").getAsString() : "");
            record.setSSHKey(body.has("sshKey") ? body.get("sshKey").getAsString() : "");

            req.getGatewayContext().getPersistenceInterface().save(record);

            res.setStatus(HttpServletResponse.SC_CREATED);
            JsonObject response = new JsonObject();
            response.addProperty("id", record.getId());
            response.addProperty("projectName", project.getProjectName());
            response.addProperty("ignitionUser", record.getIgnitionUser());
            response.addProperty("userName", record.getUserName());
            response.addProperty("email", record.getEmail());
            // Security: Never expose actual credentials - use boolean flags instead
            response.addProperty("hasPassword", record.getPassword() != null && !record.getPassword().isEmpty());
            response.addProperty("hasSshKey", record.getSSHKey() != null && !record.getSSHKey().isEmpty());
            return response;
        } catch (Exception e) {
            logger.error("Error creating user", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static Object updateUser(RequestContext req, HttpServletResponse res) {
        try {
            long id = Long.parseLong(req.getParameter("id"));
            BufferedReader reader = req.getRequest().getReader();
            JsonObject body = gson.fromJson(reader, JsonObject.class);

            // Use query instead of find since there's a composite primary key
            SQuery<GitReposUsersRecord> query = new SQuery<>(GitReposUsersRecord.META)
                    .eq(GitReposUsersRecord.Id, id);
            GitReposUsersRecord record = req.getGatewayContext()
                    .getPersistenceInterface()
                    .queryOne(query);

            if (record == null) {
                res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                JsonObject error = new JsonObject();
                error.addProperty("error", "User not found");
                return error;
            }

            // Update project if provided
            if (body.has("projectName")) {
                String projectName = body.get("projectName").getAsString();
                SQuery<GitProjectsConfigRecord> projectQuery = new SQuery<>(GitProjectsConfigRecord.META)
                        .eq(GitProjectsConfigRecord.ProjectName, projectName);
                GitProjectsConfigRecord project = req.getGatewayContext()
                        .getPersistenceInterface()
                        .queryOne(projectQuery);

                if (project == null) {
                    res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    JsonObject error = new JsonObject();
                    error.addProperty("error", "Project not found: " + projectName);
                    return error;
                }
                record.setProjectId(project.getId());
            }

            // Update other fields
            if (body.has("ignitionUser")) {
                record.setIgnitionUser(body.get("ignitionUser").getAsString());
            }
            if (body.has("userName")) {
                record.setUserName(body.get("userName").getAsString());
            }
            if (body.has("email")) {
                record.setEmail(body.get("email").getAsString());
            }
            if (body.has("password") && !body.get("password").getAsString().isEmpty()) {
                record.setPassword(body.get("password").getAsString());
            }
            if (body.has("sshKey") && !body.get("sshKey").getAsString().isEmpty()) {
                record.setSSHKey(body.get("sshKey").getAsString());
            }

            req.getGatewayContext().getPersistenceInterface().save(record);

            // Get project name for response
            GitProjectsConfigRecord project = req.getGatewayContext()
                    .getPersistenceInterface()
                    .find(GitProjectsConfigRecord.META, (long) record.getProjectId());

            res.setStatus(HttpServletResponse.SC_OK);
            JsonObject response = new JsonObject();
            response.addProperty("id", record.getId());
            response.addProperty("projectName", project != null ? project.getProjectName() : "");
            response.addProperty("ignitionUser", record.getIgnitionUser());
            response.addProperty("userName", record.getUserName());
            response.addProperty("email", record.getEmail());
            // Security: Never expose actual credentials - use boolean flags instead
            response.addProperty("hasPassword", record.getPassword() != null && !record.getPassword().isEmpty());
            response.addProperty("hasSshKey", record.getSSHKey() != null && !record.getSSHKey().isEmpty());
            return response;
        } catch (Exception e) {
            logger.error("Error updating user", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }

    private static Object deleteUser(RequestContext req, HttpServletResponse res) {
        try {
            long id = Long.parseLong(req.getParameter("id"));

            // Use query instead of find since there's a composite primary key
            SQuery<GitReposUsersRecord> query = new SQuery<>(GitReposUsersRecord.META)
                    .eq(GitReposUsersRecord.Id, id);
            GitReposUsersRecord record = req.getGatewayContext()
                    .getPersistenceInterface()
                    .queryOne(query);

            if (record == null) {
                res.setStatus(HttpServletResponse.SC_NOT_FOUND);
                JsonObject error = new JsonObject();
                error.addProperty("error", "User not found");
                return error;
            }

            record.deleteRecord();
            req.getGatewayContext().getPersistenceInterface().save(record);

            res.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return null;
        } catch (Exception e) {
            logger.error("Error deleting user", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", e.getMessage());
            return error;
        }
    }
}
