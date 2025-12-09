package com.axone_io.ignition.git.web.api;

import com.google.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    public static SecuredRouteHandler requireAuthentication(SecuredRouteHandler handler) {
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
    public static SecuredRouteHandler requireAuthenticationAndCsrf(SecuredRouteHandler handler) {
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
            HttpSession session = httpReq.getSession(false);

            if (session == null) {
                return SecurityCheckResult.unauthorized(
                    "Authentication required. Please log in to the Gateway to access this endpoint."
                );
            }

            // Check if session has a valid user attribute (Ignition sets this on login)
            Object userAttr = session.getAttribute("user");
            if (userAttr == null) {
                return SecurityCheckResult.unauthorized(
                    "No authenticated user found in session. Please log in to the Gateway."
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
                // Generate a new token if one doesn't exist
                // In a real implementation, this should be set during login
                logger.warn("No CSRF token in session. This should be set during authentication.");
                return SecurityCheckResult.forbidden(
                    "CSRF token not initialized. Please refresh your session."
                );
            }

            // Validate tokens match
            if (!requestToken.equals(sessionToken.toString())) {
                logger.warn("CSRF token mismatch. Expected: {}, Got: {}", sessionToken, requestToken);
                return SecurityCheckResult.forbidden("Invalid CSRF token");
            }

            logger.debug("CSRF validation successful");
            return SecurityCheckResult.success();

        } catch (Exception e) {
            logger.error("Error validating CSRF token", e);
            return SecurityCheckResult.forbidden("CSRF validation failed: " + e.getMessage());
        }
    }
}
