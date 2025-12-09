package com.axone_io.ignition.git.web.api;

import com.axone_io.ignition.git.GatewayHook;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.google.gson.Gson;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simpleorm.dataset.SQuery;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST API for managing Git project configurations
 */
public class GitProjectsServlet extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(GitProjectsServlet.class);
    private final Gson gson = new Gson();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            List<GitProjectsConfigRecord> projects = GatewayHook.context
                    .getPersistenceInterface()
                    .query(new SQuery<>(GitProjectsConfigRecord.META));

            List<ProjectDto> dtos = projects.stream()
                    .map(p -> new ProjectDto(p.getId(), p.getProjectName(), p.getURI()))
                    .collect(Collectors.toList());

            resp.setContentType("application/json");
            resp.setStatus(HttpServletResponse.SC_OK);
            gson.toJson(dtos, resp.getWriter());
        } catch (Exception e) {
            logger.error("Error fetching projects", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            ProjectDto dto = gson.fromJson(req.getReader(), ProjectDto.class);

            GitProjectsConfigRecord record = GatewayHook.context
                    .getPersistenceInterface()
                    .createNew(GitProjectsConfigRecord.META);

            record.setProjectName(dto.projectName);
            record.setURI(dto.uri);

            GatewayHook.context.getPersistenceInterface().save(record);

            resp.setStatus(HttpServletResponse.SC_CREATED);
            resp.setContentType("application/json");
            gson.toJson(new ProjectDto(record.getId(), record.getProjectName(), record.getURI()), resp.getWriter());
        } catch (Exception e) {
            logger.error("Error creating project", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.length() <= 1) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Project ID required");
                return;
            }

            long id = Long.parseLong(pathInfo.substring(1));
            ProjectDto dto = gson.fromJson(req.getReader(), ProjectDto.class);

            GitProjectsConfigRecord record = GatewayHook.context
                    .getPersistenceInterface()
                    .find(GitProjectsConfigRecord.META, id);

            if (record == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Project not found");
                return;
            }

            record.setProjectName(dto.projectName);
            record.setURI(dto.uri);

            GatewayHook.context.getPersistenceInterface().save(record);

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.setContentType("application/json");
            gson.toJson(new ProjectDto(record.getId(), record.getProjectName(), record.getURI()), resp.getWriter());
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid project ID");
        } catch (Exception e) {
            logger.error("Error updating project", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            String pathInfo = req.getPathInfo();
            if (pathInfo == null || pathInfo.length() <= 1) {
                resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Project ID required");
                return;
            }

            long id = Long.parseLong(pathInfo.substring(1));

            GitProjectsConfigRecord record = GatewayHook.context
                    .getPersistenceInterface()
                    .find(GitProjectsConfigRecord.META, id);

            if (record == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Project not found");
                return;
            }

            record.deleteRecord();
            GatewayHook.context.getPersistenceInterface().save(record);

            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
        } catch (NumberFormatException e) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid project ID");
        } catch (Exception e) {
            logger.error("Error deleting project", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // DTO for JSON serialization
    private static class ProjectDto {
        public long id;
        public String projectName;
        public String uri;

        public ProjectDto(long id, String projectName, String uri) {
            this.id = id;
            this.projectName = projectName;
            this.uri = uri;
        }
    }
}
