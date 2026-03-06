package com.axone_io.ignition.git.commissioning;

import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.Nullable;

public class ProjectConfig {
    // Getters and setters
    @lombok.Getter
    @lombok.Setter
    private String initDefaultBranch;

    @Getter
    @Setter
    private String repo_uri;
    @Getter
    @Setter
    private String repo_branch;
    @Getter
    @Setter
    private String ignition_projectName;
    @Getter
    @Setter
    private String ignition_userName;
    @Getter
    @Setter
    private Boolean ignition_inheritable;
    @Getter
    @Setter
    @Nullable
    private String ignition_parentName;
    @Getter
    @Setter
    private String user_name;
    @Getter
    @Setter
    private String user_email;
    @Getter
    @Setter
    private String user_password;
    @Getter
    @Setter
    private Boolean commissioning_importThemes;
    @Getter
    @Setter
    private Boolean commissioning_importTags;
    @Getter
    @Setter
    private Boolean commissioning_importImages;
    @Getter
    @Setter
    private Boolean commissioning_enforceBranch;
    @Getter
    @Setter
    private Boolean production_mode;
    @Getter
    @Setter
    private String production_branch;
    @Getter
    @Setter
    private String production_tagPattern;

}
