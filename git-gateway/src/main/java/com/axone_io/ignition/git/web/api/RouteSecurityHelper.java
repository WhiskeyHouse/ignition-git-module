package com.axone_io.ignition.git.web.api;

import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Security helper for gateway data routes providing authentication, authorization, and CSRF protection.
 *
 * Security Model:
 * - Authentication: Requires valid gateway session (user must be logged in)
 * - CSRF Protection: Validates CSRF tokens for mutating operations (POST/PUT/DELETE)
 */
public class RouteSecurityHelper {
    private static final Logger logger = LoggerFactory.getLogger(RouteSecurityHelper.class);

    private static final String CSRF_TOKEN_HEADER = "X-CSRF-Token";
    private static final String CSRF_TOKEN_SESSION_ATTR = "CSRF_TOKEN";

    /**
     * Sanitizes a string value for safe logging by removing newlines and control characters
     * that could be used for log injection attacks.
     *
     * @param value The value to sanitize
     * @return Sanitized string safe for logging
     */
    private static String sanitizeForLogging(String value) {
        if (value == null) {
            return "null";
        }
        // Replace newlines, carriage returns, and other control characters
        return value.replaceAll("[\\r\\n\\t]", " ").replaceAll("\\p{Cntrl}", "");
    }

    /**
     * Security check result containing authentication and authorization status
     */
    public static class SecurityCheckResult {
        public final boolean authenticated;
        public final boolean csrfValid;
        public final String errorMessage;
        public final int httpStatus;

        private SecurityCheckResult(boolean authenticated, boolean csrfValid, String errorMessage, int httpStatus) {
            this.authenticated = authenticated;
            this.csrfValid = csrfValid;
            this.errorMessage = errorMessage;
            this.httpStatus = httpStatus;
        }

        public static SecurityCheckResult success() {
            return new SecurityCheckResult(true, true, null, HttpServletResponse.SC_OK);
        }

        public static SecurityCheckResult unauthorized(String message) {
            return new SecurityCheckResult(false, false, message, HttpServletResponse.SC_UNAUTHORIZED);
        }

        public static SecurityCheckResult forbidden(String message) {
            return new SecurityCheckResult(true, false, message, HttpServletResponse.SC_FORBIDDEN);
        }

        public boolean isValid() {
            return authenticated && csrfValid;
        }
    }

    /**
     * Secured route handler functional interface
     */
    @FunctionalInterface
    public interface SecuredRouteHandler {
        Object handle(RequestContext req, HttpServletResponse res) throws Exception;
    }

    /**
     * Wraps a route handler with authentication checks.
     * Validates that the user has a valid gateway session.
     *
     * @param handler The route handler to protect
     * @return A wrapped handler that performs authentication checks
     */
    public static RouteHandler requireAuthentication(SecuredRouteHandler handler) {
        return (req, res) -> {
            SecurityCheckResult authCheck = checkAuthentication(req);

            if (!authCheck.authenticated) {
                logger.warn("Authentication failed: {}", authCheck.errorMessage);
                res.setStatus(authCheck.httpStatus);
                JsonObject error = new JsonObject();
                error.addProperty("error", authCheck.errorMessage);
                return error;
            }

            return handler.handle(req, res);
        };
    }

    /**
     * Wraps a route handler with both authentication and CSRF protection.
     * Use this for all POST/PUT/DELETE endpoints.
     *
     * @param handler The route handler to protect
     * @return A wrapped handler that performs authentication and CSRF checks
     */
    public static RouteHandler requireAuthenticationAndCsrf(SecuredRouteHandler handler) {
        return (req, res) -> {
            // First check authentication
            SecurityCheckResult authCheck = checkAuthentication(req);

            if (!authCheck.authenticated) {
                logger.warn("Authentication failed: {}", authCheck.errorMessage);
                res.setStatus(authCheck.httpStatus);
                JsonObject error = new JsonObject();
                error.addProperty("error", authCheck.errorMessage);
                return error;
            }

            // Then check CSRF token
            SecurityCheckResult csrfCheck = checkCsrfToken(req);

            if (!csrfCheck.csrfValid) {
                logger.warn("CSRF validation failed: {}", csrfCheck.errorMessage);
                res.setStatus(csrfCheck.httpStatus);
                JsonObject error = new JsonObject();
                error.addProperty("error", csrfCheck.errorMessage);
                return error;
            }

            return handler.handle(req, res);
        };
    }

    /**
     * Checks if the request has a valid authenticated session.
     *
     * @param req The request context
     * @return SecurityCheckResult indicating authentication status
     */
    private static SecurityCheckResult checkAuthentication(RequestContext req) {
        try {
            HttpServletRequest httpReq = req.getRequest();
            // Use getSession(false) first to check if session exists
            HttpSession session = httpReq.getSession(false);

            if (session == null) {
                return SecurityCheckResult.unauthorized(
                    "Authentication required. Please log in to the Gateway to access this endpoint."
                );
            }

            // Require standard Ignition gateway authentication.
            // The config pages are protected by Ignition's navigation model auth which
            // sets the web-auth-request-collection session attribute on login.
            Object webAuthCollection = session.getAttribute("web-auth-request-collection");

            if (webAuthCollection == null) {
                return SecurityCheckResult.unauthorized(
                    "No authenticated session found. Please log in to the Gateway."
                );
            }

            logger.debug("Authentication successful for session: {}", session.getId());
            return SecurityCheckResult.success();

        } catch (Exception e) {
            logger.error("Error checking authentication", e);
            return SecurityCheckResult.unauthorized("Authentication check failed: " + e.getMessage());
        }
    }

    /**
     * Validates CSRF token for mutating operations (POST/PUT/DELETE).
     * Checks for token in X-CSRF-Token header and validates against session token.
     *
     * @param req The request context
     * @return SecurityCheckResult indicating CSRF validation status
     */
    private static SecurityCheckResult checkCsrfToken(RequestContext req) {
        try {
            HttpServletRequest httpReq = req.getRequest();
            HttpSession session = httpReq.getSession(false);

            if (session == null) {
                return SecurityCheckResult.forbidden("No session found for CSRF validation");
            }

            // Get CSRF token from request header
            String requestToken = httpReq.getHeader(CSRF_TOKEN_HEADER);

            if (requestToken == null || requestToken.trim().isEmpty()) {
                return SecurityCheckResult.forbidden(
                    "CSRF token required. Include X-CSRF-Token header with your request."
                );
            }

            // Get expected token from session
            Object sessionToken = session.getAttribute(CSRF_TOKEN_SESSION_ATTR);

            if (sessionToken == null) {
                logger.warn("No CSRF token in session. This should be set during authentication.");
                return SecurityCheckResult.forbidden(
                    "CSRF token not initialized. Please refresh your session."
                );
            }

            // Validate tokens match
            if (!requestToken.equals(sessionToken.toString())) {
                String requestPath = httpReq.getRequestURI();
                logger.warn("CSRF token mismatch detected - Path: {}, SessionId: {}",
                    sanitizeForLogging(requestPath), session.getId());
                return SecurityCheckResult.forbidden("Invalid CSRF token");
            }

            logger.debug("CSRF validation successful");
            return SecurityCheckResult.success();

        } catch (Exception e) {
            logger.error("Error validating CSRF token", e);
            return SecurityCheckResult.forbidden("CSRF validation failed: " + e.getMessage());
        }
    }

    /**
     * Generates a cryptographically secure CSRF token.
     *
     * @return A base64-encoded random token
     */
    private static String generateCsrfToken() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] tokenBytes = new byte[32]; // 256 bits
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    /**
     * Gets or creates a CSRF token for the current session.
     * If a token doesn't exist in the session, a new one is generated and stored.
     *
     * @param session The HTTP session
     * @return The CSRF token for this session
     */
    public static String getOrCreateCsrfToken(HttpSession session) {
        Object existingToken = session.getAttribute(CSRF_TOKEN_SESSION_ATTR);

        if (existingToken != null) {
            logger.debug("Returning existing CSRF token for session: {}", session.getId());
            return existingToken.toString();
        }

        // Generate new token
        String newToken = generateCsrfToken();
        session.setAttribute(CSRF_TOKEN_SESSION_ATTR, newToken);
        logger.info("Generated new CSRF token for session: {}", session.getId());

        return newToken;
    }

    /**
     * Route handler that returns the CSRF token for the authenticated user's session.
     * This endpoint should be called by the frontend when the page loads to obtain
     * the CSRF token, which must then be included in all mutating requests
     * (POST/PUT/DELETE) via the X-CSRF-Token header.
     *
     * @param req The request context
     * @param res The HTTP response
     * @return JSON object containing the CSRF token
     */
    public static Object getCsrfToken(RequestContext req, HttpServletResponse res) {
        try {
            HttpServletRequest httpReq = req.getRequest();
            HttpSession session = httpReq.getSession(false);

            if (session == null) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                JsonObject error = new JsonObject();
                error.addProperty("error", "No active session. Please log in to the Gateway.");
                return error;
            }

            String token = getOrCreateCsrfToken(session);

            JsonObject response = new JsonObject();
            response.addProperty("csrfToken", token);
            return response;

        } catch (Exception e) {
            logger.error("Error generating CSRF token", e);
            res.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            JsonObject error = new JsonObject();
            error.addProperty("error", "Failed to generate CSRF token: " + e.getMessage());
            return error;
        }
    }
}
