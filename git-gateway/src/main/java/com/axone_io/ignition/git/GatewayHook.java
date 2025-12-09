package com.axone_io.ignition.git;

import com.axone_io.ignition.git.commissioning.utils.GitCommissioningUtils;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.axone_io.ignition.git.records.GitReposUsersRecord;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.rpc.proto.ProtoRpcSerializer;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.rpc.GatewayRpcImplementation;
import com.inductiveautomation.ignition.gateway.web.systemjs.SystemJsModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Optional;

public class GatewayHook extends AbstractGatewayModuleHook {
    static public String MODULE_NAME = "Git";
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private GatewayScriptModule scriptModule;
    public static GatewayContext context;


    @Override
    public void setup(GatewayContext gatewayContext) {
        context = gatewayContext;
        scriptModule = new GatewayScriptModule(context);
        BundleUtil.get().addBundle("bundle_git", getClass(), "bundle_git");
        verifySchema(gatewayContext);

        // Register React-based config pages using 8.3 navigation API
        registerConfigPages(gatewayContext);

        logger.info("setup()");
    }

    @Override
    public Optional<GatewayRpcImplementation> getRpcImplementation() {
        // Return RPC implementation for 8.3 pattern using default protobuf serializer
        return Optional.of(GatewayRpcImplementation.of(
                ProtoRpcSerializer.DEFAULT_INSTANCE,
                scriptModule
        ));
    }

    // Removed - using mountRouteHandlers instead for Ignition 8.3

    private void registerConfigPages(GatewayContext context) {
        try {
            // Create SystemJsModule for Git Projects config page
            // Note: The first parameter is the exported React component name from your JS file
            // The second parameter uses /res/{alias}/{filename} - getMountedResourceFolder() automatically
            // maps the "mounted" folder, so we don't include it in the path
            SystemJsModule projectsModule = new SystemJsModule(
                    "GitProjectsConfig",
                    "/res/git/GitProjectsConfig.js"
            );

            // Create SystemJsModule for Git Users config page
            SystemJsModule usersModule = new SystemJsModule(
                    "GitUsersConfig",
                    "/res/git/GitUsersConfig.js"
            );

            // Add Git configuration category to Platform section
            // Using getPlatform() to add to Platform section where config pages go in 8.3
            context.getWebResourceManager().getNavigationModel()
                    .getPlatform()
                    .addCategory("git", cat -> cat
                            .label("Git")
                            .addPage("Git Projects", page -> page
                                    .position(10)
                                    // The second parameter must match the export name in your JS file
                                    .mount("/git/projects", "GitProjectsConfig", projectsModule)
                            )
                            .addPage("Git Users", page -> page
                                    .position(20)
                                    .mount("/git/users", "GitUsersConfig", usersModule)
                            )
                    );

            logger.info("Registered Git configuration pages");
        } catch (Exception e) {
            logger.error("Error registering config pages", e);
        }
    }

    private void verifySchema(GatewayContext context) {
        try {
            context.getSchemaUpdater().updatePersistentRecords(GitProjectsConfigRecord.META, GitReposUsersRecord.META);
        } catch (SQLException e) {
            logger.error("Error verifying persistent record schemas for HomeConnect records.", e);
        }
    }

    @Override
    public void startup(LicenseState licenseState) {
        GitCommissioningUtils.loadConfiguration();

        logger.info("startup()");
    }

    @Override
    public void shutdown() {
        logger.info("shutdown()");
    }

    @Override
    public boolean isFreeModule() {
        return true;
    }

    @Override
    public boolean isMakerEditionCompatible() {
        return true;
    }

    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.of("mounted");
    }

    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.of("git");
    }

    @Override
    public void mountRouteHandlers(RouteGroup routes) {
        // Mount Git API routes - accessible at /main/data/com.axone_io.ignition.git/*
        //
        // SECURITY: All routes are protected with session-based authentication and CSRF validation:
        // - Authentication: Requires valid gateway session (users must be logged in)
        //   * Verified by checking for active HttpSession with authenticated user
        //   * Returns HTTP 401 Unauthorized if session is missing or user is not authenticated
        //
        // - CSRF Protection: Validated for all mutating operations (POST/PUT/DELETE)
        //   * CSRF tokens must be provided in the X-CSRF-Token HTTP header
        //   * Tokens are validated against session tokens
        //   * Returns HTTP 403 Forbidden on CSRF validation failure
        //
        // - Route Protection:
        //   * GET endpoints: Authentication required
        //   * POST/PUT/DELETE endpoints: Authentication + CSRF token required
        //
        // - Credential Protection:
        //   * Passwords and SSH keys are never returned in API responses
        //   * Only hasPassword/hasSshKey boolean flags are exposed
        //
        // These security measures ensure that only authenticated gateway users can access
        // these endpoints, protecting against unauthorized access and CSRF attacks on Git
        // project configurations and user credentials (passwords, SSH keys).
        //
        // Implementation: RouteSecurityHelper wraps all route handlers with security checks.
        com.axone_io.ignition.git.web.api.GitRoutes.mountRoutes(routes);
        logger.info("Mounted Git API routes with session authentication and CSRF protection");
    }
}
