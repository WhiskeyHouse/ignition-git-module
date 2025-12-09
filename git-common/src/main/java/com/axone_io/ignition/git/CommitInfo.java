package com.axone_io.ignition.git;

import java.io.Serializable;
import java.util.List;

/**
 * Data transfer object for commit information in Git repository.
 * Used for RPC communication between Gateway and Designer.
 */
public class CommitInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String commitHash;
    private String shortHash;
    private String message;
    private String author;
    private String authorEmail;
    private long timestamp;
    private List<String> filesChanged;

    // Default constructor required for serialization
    public CommitInfo() {
    }

    public CommitInfo(String commitHash, String shortHash, String message, String author,
                     String authorEmail, long timestamp, List<String> filesChanged) {
        this.commitHash = commitHash;
        this.shortHash = shortHash;
        this.message = message;
        this.author = author;
        this.authorEmail = authorEmail;
        this.timestamp = timestamp;
        this.filesChanged = filesChanged;
    }

    public String getCommitHash() {
        return commitHash;
    }

    public void setCommitHash(String commitHash) {
        this.commitHash = commitHash;
    }

    public String getShortHash() {
        return shortHash;
    }

    public void setShortHash(String shortHash) {
        this.shortHash = shortHash;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getAuthorEmail() {
        return authorEmail;
    }

    public void setAuthorEmail(String authorEmail) {
        this.authorEmail = authorEmail;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public List<String> getFilesChanged() {
        return filesChanged;
    }

    public void setFilesChanged(List<String> filesChanged) {
        this.filesChanged = filesChanged;
    }

    @Override
    public String toString() {
        return "CommitInfo{" +
                "shortHash='" + shortHash + '\'' +
                ", message='" + message + '\'' +
                ", author='" + author + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}
