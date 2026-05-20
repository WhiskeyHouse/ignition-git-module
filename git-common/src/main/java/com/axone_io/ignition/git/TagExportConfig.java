package com.axone_io.ignition.git;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-project tag export configuration stored as {@code <project>/tags/.tag-config.json}.
 * Controls which tag providers are exported, which paths are excluded, and the
 * collision policy used during import.
 *
 * <p>Inspired by the <a href="https://github.com/design-group/ignition-tag-cicd-module">
 * Ignition Tag CI/CD Module</a> by Barry-Wehmiller Design Group (Keith Gamble).</p>
 */
public class TagExportConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * Name of Ignition's built-in tag provider that holds runtime/diagnostic tags
     * (gateway uptime, performance metrics, etc.). These are environment-specific
     * and must never be tracked in git.
     */
    public static final String SYSTEM_PROVIDER_NAME = "System";

    /** Tag providers to include in export. Empty list means all providers. */
    private List<String> includedProviders;

    /** Relative tag paths to skip during export (e.g. "Folder1/TempTag"). */
    private List<String> excludedTagPaths;

    /**
     * Collision policy for tag import:
     * <ul>
     *   <li>{@code "o"} - Overwrite (default)</li>
     *   <li>{@code "m"} - Merge</li>
     *   <li>{@code "d"} - Delete &amp; Replace</li>
     * </ul>
     */
    private String collisionPolicy;

    /** Default constructor required for serialization. */
    public TagExportConfig() {
        this.includedProviders = new ArrayList<>();
        this.excludedTagPaths = new ArrayList<>();
        this.collisionPolicy = "o";
    }

    public TagExportConfig(List<String> includedProviders, List<String> excludedTagPaths, String collisionPolicy) {
        this.includedProviders = includedProviders != null ? includedProviders : new ArrayList<>();
        this.excludedTagPaths = excludedTagPaths != null ? excludedTagPaths : new ArrayList<>();
        this.collisionPolicy = collisionPolicy != null ? collisionPolicy : "o";
    }

    public List<String> getIncludedProviders() {
        return includedProviders;
    }

    public void setIncludedProviders(List<String> includedProviders) {
        this.includedProviders = includedProviders;
    }

    public List<String> getExcludedTagPaths() {
        return excludedTagPaths;
    }

    public void setExcludedTagPaths(List<String> excludedTagPaths) {
        this.excludedTagPaths = excludedTagPaths;
    }

    public String getCollisionPolicy() {
        return collisionPolicy;
    }

    public void setCollisionPolicy(String collisionPolicy) {
        this.collisionPolicy = collisionPolicy;
    }

    /**
     * Returns {@code true} if the given provider name should be included in export,
     * based on the {@link #includedProviders} list. An empty list means all providers.
     * The Ignition built-in {@link #SYSTEM_PROVIDER_NAME System} provider is always
     * excluded regardless of configuration.
     */
    public boolean isProviderIncluded(String providerName) {
        if (SYSTEM_PROVIDER_NAME.equals(providerName)) {
            return false;
        }
        return includedProviders == null || includedProviders.isEmpty()
                || includedProviders.contains(providerName);
    }

    /**
     * Returns {@code true} if the given relative tag path should be excluded from export.
     */
    public boolean isPathExcluded(String relativePath) {
        if (excludedTagPaths == null || excludedTagPaths.isEmpty()) {
            return false;
        }
        for (String excluded : excludedTagPaths) {
            if (relativePath.equals(excluded) || relativePath.startsWith(excluded + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "TagExportConfig{" +
                "includedProviders=" + includedProviders +
                ", excludedTagPaths=" + excludedTagPaths +
                ", collisionPolicy='" + collisionPolicy + '\'' +
                '}';
    }
}
