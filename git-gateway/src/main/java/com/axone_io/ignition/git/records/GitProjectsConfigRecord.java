package com.axone_io.ignition.git.records;

// Disabled for 8.3 upgrade - web UI form editors
// import com.axone_io.ignition.git.web.ProjectList.ProjectListEditorSource;
import com.inductiveautomation.ignition.gateway.localdb.persistence.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import simpleorm.dataset.SFieldFlags;

public class GitProjectsConfigRecord extends PersistentRecord {
    private static final Logger logger = LoggerFactory.getLogger(GitProjectsConfigRecord.class);

    public static final RecordMeta<GitProjectsConfigRecord> META = new RecordMeta<>(
            GitProjectsConfigRecord.class, "GitProjectsConfigRecord");

    @Override
    public RecordMeta<?> getMeta() {
        return META;
    }

    public static final IdentityField Id = new IdentityField(META);
    public static final StringField ProjectName = new StringField(META, "ProjectName", SFieldFlags.SMANDATORY, SFieldFlags.SDESCRIPTIVE);
    public static final StringField URI =
            new StringField(META, "URI", SFieldFlags.SMANDATORY, SFieldFlags.SDESCRIPTIVE);
    public static final BooleanField ProductionMode = new BooleanField(META, "ProductionMode");
    /**
     * Marks this project as the owner of gateway-scoped resources (tags, themes, images).
     *
     * <p>Those resources belong to the gateway, not to any one project, but the export runs per
     * project — so without an owner every git-backed project writes a competing copy of the same
     * gateway state into its own repository. Exactly one project should have this set; the module
     * refuses to export gateway resources when zero or more than one project claims them.</p>
     */
    public static final BooleanField ExportGatewayResources =
            new BooleanField(META, "ExportGatewayResources").setDefault(false);
    public static final StringField ProductionBranch = new StringField(META, "ProductionBranch");
    public static final StringField ProductionTagPattern = new StringField(META, "ProductionTagPattern");
    public static final StringField LastHotfixStatus = new StringField(META, "LastHotfixStatus");
    public static final LongField LastHotfixTimestamp = new LongField(META, "LastHotfixTimestamp");
    public static final StringField LastHotfixUser = new StringField(META, "LastHotfixUser");
    public static final StringField LastHotfixBranch = new StringField(META, "LastHotfixBranch");
    public static final StringField LastHotfixPRUrl = new StringField(META, "LastHotfixPRUrl");

    // Category removed in 8.3 - was only used for Wicket form grouping
    // static final Category ProjectConfiguration = new Category("GitProjectsConfigRecord.Category.ProjectConfiguration", 1000).include(ProjectName, URI);


    public long getId() {
        return this.getLong(Id);
    }

    public String getProjectName() {
        return this.getString(ProjectName);
    }

    public String getURI() {
        return this.getString(URI);
    }

    public void setProjectName(String projectName) {
        setString(ProjectName, projectName);
    }

    public void setURI(String uri) {
        setString(URI, uri);
    }

    public boolean isExportGatewayResources() {
        return this.getBoolean(ExportGatewayResources);
    }

    public void setExportGatewayResources(boolean exportGatewayResources) {
        setBoolean(ExportGatewayResources, exportGatewayResources);
    }

    public boolean isSSHAuthentication() {
        return !this.getString(URI).toLowerCase().startsWith("http");
    }

    public boolean getProductionMode() {
        return this.getBoolean(ProductionMode);
    }

    public void setProductionMode(boolean productionMode) {
        setBoolean(ProductionMode, productionMode);
    }

    public String getProductionBranch() {
        return this.getString(ProductionBranch);
    }

    public void setProductionBranch(String productionBranch) {
        setString(ProductionBranch, productionBranch);
    }

    public String getProductionTagPattern() {
        return this.getString(ProductionTagPattern);
    }

    public void setProductionTagPattern(String productionTagPattern) {
        setString(ProductionTagPattern, productionTagPattern);
    }

    public String getLastHotfixStatus() {
        return this.getString(LastHotfixStatus);
    }

    public void setLastHotfixStatus(String status) {
        setString(LastHotfixStatus, status);
    }

    public long getLastHotfixTimestamp() {
        return this.getLong(LastHotfixTimestamp);
    }

    public void setLastHotfixTimestamp(long timestamp) {
        setLong(LastHotfixTimestamp, timestamp);
    }

    public String getLastHotfixUser() {
        return this.getString(LastHotfixUser);
    }

    public void setLastHotfixUser(String user) {
        setString(LastHotfixUser, user);
    }

    public String getLastHotfixBranch() {
        return this.getString(LastHotfixBranch);
    }

    public void setLastHotfixBranch(String branch) {
        setString(LastHotfixBranch, branch);
    }

    public String getLastHotfixPRUrl() {
        return this.getString(LastHotfixPRUrl);
    }

    public void setLastHotfixPRUrl(String prUrl) {
        setString(LastHotfixPRUrl, prUrl);
    }

    // DISABLED FOR 8.3 UPGRADE - Web UI form metadata not needed without config pages
    // TODO: Re-enable when migrating to new 8.3 web API
    /*
    static {
        ProjectName.getFormMeta().setEditorSource(ProjectListEditorSource.getSharedInstance());

        URI.getFormMeta().setFieldDescriptionKey("GitProjectsConfigRecord.URI.Desc");
        URI.getFormMeta().setFieldDescriptionKeyAddMode("GitProjectsConfigRecord.URI.NewDesc");
        URI.getFormMeta().setFieldDescriptionKeyEditMode("GitProjectsConfigRecord.URI.EditDesc");
        URI.setWide();

    }
    */
}
