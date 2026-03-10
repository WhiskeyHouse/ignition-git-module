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
    public static final StringField ProductionBranch = new StringField(META, "ProductionBranch");
    public static final StringField ProductionTagPattern = new StringField(META, "ProductionTagPattern");

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
