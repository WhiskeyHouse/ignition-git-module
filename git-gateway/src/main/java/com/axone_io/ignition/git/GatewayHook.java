package com.axone_io.ignition.git;

import com.axone_io.ignition.git.commissioning.utils.GitCommissioningUtils;
import com.axone_io.ignition.git.managers.TagChangeWatcher;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.axone_io.ignition.git.records.GitReposUsersRecord;
import com.inductiveautomation.ignition.common.BundleUtil;
import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.rpc.proto.ProtoRpcSerializer;
import com.inductiveautomation.ignition.common.script.ScriptManager;
import com.inductiveautomation.ignition.common.script.hints.PropertiesFileDocProvider;
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

    private static GatewayScriptModule scriptModule;
    public static GatewayContext context;

    private TagChangeWatcher tagChangeWatcher;
    private com.inductiveautomation.ignition.common.resourcecollection.ResourceListener tagResourceListener;

    public static GatewayScriptModule getScriptModule() {
        return scriptModule;
    }


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

    @Override
    public void initializeScriptManager(ScriptManager manager) {
        super.initializeScriptManager(manager);

        // Curated scripting surface: tag import + read-only status only.
        // Enables Gateway Event Scripts (Startup/Timer) to call system.git.*.
        manager.addScriptModule(
                "system.git",
                new GitScriptFunctions(() -> scriptModule),
                new PropertiesFileDocProvider()
        );
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

            // Create SystemJsModule for Docs Viewer config page
            SystemJsModule docsModule = new SystemJsModule(
                    "DocsViewer",
                    "/res/git/DocsViewer.js"
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
                            .addPage("Project Docs", page -> page
                                    .position(30)
                                    .mount("/git/docs", "DocsViewer", docsModule)
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
        GitCommissioningUtils.startTagImportOnStartup();
        startTagChangeWatcher();

        logger.info("startup()");
    }

    /**
     * Watches Ignition's config resources for tag and UDT changes. Tags are not project
     * resources, so the Designer's project-save hook never sees them — without this, tag
     * edits would silently never reach git.
     */
    private void startTagChangeWatcher() {
        try {
            tagChangeWatcher = TagChangeWatcher.createDefault();
            tagResourceListener = tagChangeWatcher.asResourceListener();
            context.getConfigurationManager().getConfigCollection()
                    .addResourceListener(tagResourceListener);
            logger.info("Tag change watcher registered.");
        } catch (Exception e) {
            logger.error("Could not register the tag change watcher. Tag and UDT edits will not "
                    + "automatically prompt for a commit; the Export button still works.", e);
            if (tagChangeWatcher != null) {
                // createDefault() may have succeeded before registration threw, in which case
                // its scheduler thread is already running and would outlive this failure.
                tagChangeWatcher.shutdown();
            }
            tagChangeWatcher = null;
            tagResourceListener = null;
        }
    }

    @Override
    public void shutdown() {
        if (tagResourceListener != null) {
            // The ResourceCollection is owned by the gateway, not this module, so it outlives
            // a module stop/restart. Leaving the listener registered would let a stale
            // listener fire on the next tag/UDT edit after the executor below is shut down.
            try {
                context.getConfigurationManager().getConfigCollection()
                        .removeResourceListener(tagResourceListener);
            } catch (Exception e) {
                logger.warn("Could not deregister the tag change watcher's resource listener.", e);
            }
        }
        if (tagChangeWatcher != null) {
            tagChangeWatcher.shutdown();
        }
        tagChangeWatcher = null;
        tagResourceListener = null;
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
        // Mount Git API routes
        //
        // SECURITY:
        //   * GET endpoints use OPEN_ROUTE (config pages are auth-protected by Ignition's nav model)
        //   * POST/PUT/DELETE endpoints use ZONE_WRITE + CSRF token validation
        //   * Passwords and SSH keys are never returned in API responses
        com.axone_io.ignition.git.web.api.GitRoutes.mountRoutes(routes);
        com.axone_io.ignition.git.web.api.DocsRoutes.mountRoutes(routes);
        logger.info("Mounted Git API routes");
    }
}
