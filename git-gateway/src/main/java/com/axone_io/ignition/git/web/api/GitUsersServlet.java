package com.axone_io.ignition.git.web.api;

import com.axone_io.ignition.git.GatewayHook;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.axone_io.ignition.git.records.GitReposUsersRecord;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.annotations.SerializedName;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simpleorm.dataset.SQuery;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for managing Git user configurations
 */
public class GitUsersServlet extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(GitUsersServlet.class);
    private final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.IDENTITY)
            .create();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            List<GitReposUsersRecord> users = GatewayHook.context
                    .getPersistenceInterface()
                    .query(new SQuery<>(GitReposUsersRecord.META));

            logger.info("Found {} users in database", users.size());

            Map<Long, String> projectNames = GatewayHook.context
                    .getPersistenceInterface()
                    .query(new SQuery<>(GitProjectsConfigRecord.META))
                    .stream()
                    .collect(Collectors.toMap(
                            GitProjectsConfigRecord::getId,
                            GitProjectsConfigRecord::getProjectName
                    ));

            logger.info("Found {} projects in database: {}", projectNames.size(), projectNames);

            List<UserDto> dtos = users.stream()
                    .map(u -> {
                        logger.debug("Processing user id={}, projectId={}", u.getId(), u.getProjectId());
                        String projectName = projectNames.get((long) u.getProjectId());
                        logger.debug("Map lookup result for projectId {}: {}", u.getProjectId(), projectName);
                        if (projectName == null) {
                            logger.warn("Project name not found in map for user id={}, projectId={}, falling back to resolveProjectName",
                                    u.getId(), u.getProjectId());
                            projectName = resolveProjectName(u);
                            logger.debug("resolveProjectName returned: {}", projectName);
                        }
                        return new UserDto(
                                u.getId(),
                                projectName,
                                u.getIgnitionUser(),
                                u.getUserName(),
                                u.getEmail(),
                                u.getPassword(),
                                u.getSSHKey()
                        );
                    })
                    .collect(Collectors.toList());

            resp.setContentType("application/json");
            resp.setStatus(HttpServletResponse.SC_OK);
            gson.toJson(dtos, resp.getWriter());
        } catch (Exception e) {
            logger.error("Error fetching users", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            UserDto dto = gson.fromJson(req.getReader(), UserDto.class);

            GitProjectsConfigRecord projectRecord = findProjectRecord(dto.projectName);
            if (projectRecord == null) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Project not found: " + dto.projectName);
                return;
            }

            GitReposUsersRecord record = GatewayHook.context
                    .getPersistenceInterface()
                    .createNew(GitReposUsersRecord.META);

            record.setIgnitionUser(dto.ignitionUser);
            record.setUserName(dto.userName);
            record.setEmail(dto.email);
            record.setPassword(dto.password);
            record.setSSHKey(dto.sshKey);
            record.setProjectId(projectRecord.getId());

            GatewayHook.context.getPersistenceInterface().save(record);

            resp.setStatus(HttpServletResponse.SC_CREATED);
            resp.setContentType("application/json");
            UserDto responseDto = new UserDto(
                    record.getId(),
                    projectRecord.getProjectName(),
                    record.getIgnitionUser(),
                    record.getUserName(),
                    record.getEmail(),
                    record.getPassword(),
                    record.getSSHKey()
            );
            gson.toJson(responseDto, resp.getWriter());
        } catch (Exception e) {
            logger.error("Error creating user", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.length() <= 1) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "User ID required");
                return;
            }

            int id = Integer.parseInt(pathInfo.substring(1));
            UserDto dto = gson.fromJson(req.getReader(), UserDto.class);

            GitReposUsersRecord record = GatewayHook.context
                    .getPersistenceInterface()
                    .find(GitReposUsersRecord.META, (long) id);

            if (record == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "User not found");
                return;
            }

            record.setIgnitionUser(dto.ignitionUser);
            record.setUserName(dto.userName);
            record.setEmail(dto.email);

            GitProjectsConfigRecord projectRecord = null;
            if (dto.projectName != null && !dto.projectName.isEmpty()) {
                projectRecord = findProjectRecord(dto.projectName);
                if (projectRecord == null) {
                    resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Project not found: " + dto.projectName);
                    return;
                }
                record.setProjectId(projectRecord.getId());
            }
            if (dto.password != null && !dto.password.isEmpty()) {
                record.setPassword(dto.password);
            }
            if (dto.sshKey != null && !dto.sshKey.isEmpty()) {
                record.setSSHKey(dto.sshKey);
            }

            GatewayHook.context.getPersistenceInterface().save(record);

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json");
            UserDto responseDto = new UserDto(
                    record.getId(),
                    projectRecord != null ? projectRecord.getProjectName() : resolveProjectName(record),
                    record.getIgnitionUser(),
                    record.getUserName(),
                    record.getEmail(),
                    record.getPassword(),
                    record.getSSHKey()
            );
            gson.toJson(responseDto, resp.getWriter());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid user ID");
        } catch (Exception e) {
            logger.error("Error updating user", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.length() <= 1) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "User ID required");
                return;
            }

            int id = Integer.parseInt(pathInfo.substring(1));

            GitReposUsersRecord record = GatewayHook.context
                    .getPersistenceInterface()
                    .find(GitReposUsersRecord.META, (long) id);

            if (record == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "User not found");
                return;
            }

            record.deleteRecord();
            GatewayHook.context.getPersistenceInterface().save(record);

            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid user ID");
        } catch (Exception e) {
            logger.error("Error deleting user", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // DTO for JSON serialization
    private static class UserDto {
        @SerializedName("id")
        public int id;
        @SerializedName("projectName")
        public String projectName;
        @SerializedName("ignitionUser")
        public String ignitionUser;
        @SerializedName("userName")
        public String userName;
        @SerializedName("email")
        public String email;
        @SerializedName("password")
        public String password;
        @SerializedName("sshKey")
        public String sshKey;

        public UserDto(int id, String projectName, String ignitionUser, String userName,
                       String email, String password, String sshKey) {
            this.id = id;
            this.projectName = projectName;
            this.ignitionUser = ignitionUser;
            this.userName = userName;
            this.email = email;
            this.password = password;
            this.sshKey = sshKey;
        }
    }

    private GitProjectsConfigRecord findProjectRecord(String projectName) {
        if (projectName == null || projectName.trim().isEmpty()) {
            return null;
        }

        SQuery<GitProjectsConfigRecord> query = new SQuery<>(GitProjectsConfigRecord.META)
                .eq(GitProjectsConfigRecord.ProjectName, projectName);

        return GatewayHook.context
                .getPersistenceInterface()
                .queryOne(query);
    }

    private String resolveProjectName(GitReposUsersRecord record) {
        try {
            if (record.getProjectId() > 0) {
                GitProjectsConfigRecord projectById = GatewayHook.context
                        .getPersistenceInterface()
                        .find(GitProjectsConfigRecord.META, (long) record.getProjectId());
                if (projectById != null) {
                    return projectById.getProjectName();
                }
            }

            String legacyName = record.getProjectName();
            if (legacyName != null && !legacyName.trim().isEmpty()) {
                GitProjectsConfigRecord projectByName = findProjectRecord(legacyName);
                if (projectByName != null) {
                    // Opportunistically repair missing foreign key
                    record.setProjectId(projectByName.getId());
                    GatewayHook.context.getPersistenceInterface().save(record);
                    return projectByName.getProjectName();
                }
                return legacyName;
            }
        } catch (Exception e) {
            logger.warn("Unable to resolve project name for user id {}", record.getId(), e);
        }
        return null;
    }
}
