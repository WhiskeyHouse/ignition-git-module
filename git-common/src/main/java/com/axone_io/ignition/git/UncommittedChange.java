package com.axone_io.ignition.git;

import java.io.Serializable;

/**
 * Data transfer object for uncommitted changes in Git repository.
 * Used for RPC communication between Gateway and Designer.
 */
public class UncommittedChange implements Serializable {
    private static final long serialVersionUID = 1L;

    private String resource;
    private String type;
    private String actor;

    // Default constructor required for serialization
    public UncommittedChange() {
    }

    public UncommittedChange(String resource, String type, String actor) {
        this.resource = resource;
        this.type = type;
        this.actor = actor;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }
}
