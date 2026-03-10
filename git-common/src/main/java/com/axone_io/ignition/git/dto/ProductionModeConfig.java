package com.axone_io.ignition.git.dto;

import java.io.Serializable;

/**
 * DTO for production mode configuration and validation results.
 * Contains settings and validation state for production-safe git operations.
 */
public class ProductionModeConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    private boolean productionMode;
    private String productionBranch;
    private String productionTagPattern;
    private boolean isValid;
    private String validationMessage;
    private String warningMessage;

    // Default constructor required for serialization
    public ProductionModeConfig() {
        this.productionMode = false;
        this.isValid = true;
        this.validationMessage = "";
        this.warningMessage = "";
    }

    public ProductionModeConfig(boolean productionMode, String productionBranch, String productionTagPattern) {
        this.productionMode = productionMode;
        this.productionBranch = productionBranch;
        this.productionTagPattern = productionTagPattern;
        this.isValid = true;
        this.validationMessage = "";
        this.warningMessage = "";
    }

    public boolean isProductionMode() {
        return productionMode;
    }

    public void setProductionMode(boolean productionMode) {
        this.productionMode = productionMode;
    }

    public String getProductionBranch() {
        return productionBranch;
    }

    public void setProductionBranch(String productionBranch) {
        this.productionBranch = productionBranch;
    }

    public String getProductionTagPattern() {
        return productionTagPattern;
    }

    public void setProductionTagPattern(String productionTagPattern) {
        this.productionTagPattern = productionTagPattern;
    }

    public boolean isValid() {
        return isValid;
    }

    public void setValid(boolean valid) {
        isValid = valid;
    }

    public String getValidationMessage() {
        return validationMessage;
    }

    public void setValidationMessage(String validationMessage) {
        this.validationMessage = validationMessage;
    }

    public String getWarningMessage() {
        return warningMessage;
    }

    public void setWarningMessage(String warningMessage) {
        this.warningMessage = warningMessage;
    }

    public boolean hasWarnings() {
        return warningMessage != null && !warningMessage.isEmpty();
    }

    @Override
    public String toString() {
        return "ProductionModeConfig{" +
                "productionMode=" + productionMode +
                ", productionBranch='" + productionBranch + '\'' +
                ", productionTagPattern='" + productionTagPattern + '\'' +
                ", isValid=" + isValid +
                ", validationMessage='" + validationMessage + '\'' +
                ", warningMessage='" + warningMessage + '\'' +
                '}';
    }
}
