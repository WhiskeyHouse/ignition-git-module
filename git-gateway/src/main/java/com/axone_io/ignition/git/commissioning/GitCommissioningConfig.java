package com.axone_io.ignition.git.commissioning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

public class GitCommissioningConfig {

    // Existing fields and methods
    @Getter
    @Setter
    private String repoURI;
    @Getter
    @Setter
    private String repoBranch;
    @Getter
    @Setter
    private String ignitionProjectName;
    @Getter
    @Setter
    private String ignitionUserName;
    @Getter
    @Setter
    private boolean ignitionProjectInheritable;
    @Getter
    @Setter
    @Nullable
    // This field is nullable
    private String ignitionProjectParentName;
    @Getter
    @Setter
    private String userName;
    @Getter
    @Setter
    private String userPassword;
    @Getter
    @Setter
    private String sshKey;
    @Getter
    @Setter
    private String userEmail;
    @Getter
    @Setter
    private boolean importImages = false;
    @Getter
    @Setter
    private boolean importTags = false;
    @Getter
    @Setter
    private boolean importThemes = false;
    @Getter
    @Setter
    private String initDefaultBranch;

    public void loadFromProjectConfig(ProjectConfig projectConfig) {

        this.repoURI = projectConfig.getRepo_uri();
        this.repoBranch = projectConfig.getRepo_branch();
        this.ignitionProjectName = projectConfig.getIgnition_projectName();
        this.ignitionUserName = projectConfig.getIgnition_userName();
        this.ignitionProjectInheritable = Boolean.TRUE.equals(projectConfig.getIgnition_inheritable());
        this.ignitionProjectParentName = projectConfig.getIgnition_parentName();
        this.userName = projectConfig.getUser_name();
        this.userEmail = projectConfig.getUser_email();
        this.userPassword = projectConfig.getUser_password();
        this.importImages = Boolean.TRUE.equals(projectConfig.getCommissioning_importImages());
        this.importTags = Boolean.TRUE.equals(projectConfig.getCommissioning_importTags());
        this.importThemes = Boolean.TRUE.equals(projectConfig.getCommissioning_importThemes());
        this.initDefaultBranch = projectConfig.getInitDefaultBranch();
    }

    public void setSecretFromFilePath(Path filePath, boolean isSSHAuth) throws IOException {
        if (filePath.toFile().exists() && filePath.toFile().isFile()) {
            String secret = Files.readString(filePath, StandardCharsets.UTF_8).trim();
            if (isSSHAuth) {
                this.sshKey = secret;
            } else {
                this.userPassword = secret;
            }
        }
    }
}


