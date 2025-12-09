package com.axone_io.ignition.git;

import com.axone_io.ignition.git.commissioning.utils.GitCommissioningUtils;
import com.axone_io.ignition.git.managers.GitImageManager;
import com.axone_io.ignition.git.managers.GitProjectManager;
import com.axone_io.ignition.git.managers.GitTagManager;
import com.axone_io.ignition.git.managers.GitThemeManager;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.eclipse.jgit.api.*;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.axone_io.ignition.git.managers.GitImageManager.exportImages;
import static com.axone_io.ignition.git.managers.GitManager.*;
import static com.axone_io.ignition.git.managers.GitTagManager.exportTag;
import static com.axone_io.ignition.git.managers.GitThemeManager.exportTheme;

public class GatewayScriptModule extends AbstractScriptModule implements GitScriptInterface {
    private final LoggerEx logger = LoggerEx.newBuilder().build(getClass());
    private final GatewayContext context;

    GatewayScriptModule(GatewayContext context) {
        this.context = context;
    }

    @Override
    public boolean pullImpl(String projectName,
                            String userName,
                            boolean importTags,
                            boolean importTheme,
                            boolean importImages) throws Exception {

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            PullCommand pull = git.pull();
            setAuthentication(pull, projectName, userName);

            PullResult result = pull.call();
            if (!result.isSuccessful()) {
                logger.warn("Cannot pull from git");
            } else {
                logger.info("Pull was successful.");
            }

            GitProjectManager.importProject(projectName);

            if (importTags) {
                GitTagManager.importTagManager(projectName);
            }
            if (importTheme) {
                GitThemeManager.importTheme(projectName);
            }
            if (importImages) {
                GitImageManager.importImages(projectName);
            }
        } catch (GitAPIException e) {
            logger.error(e.toString());
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public boolean pushImpl(String projectName, String userName) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            PushCommand push = git.push();

            setAuthentication(push, projectName, userName);

            Iterable<PushResult> results = push.setPushAll().setPushTags().call();
            for (PushResult result : results) {
                logger.trace(result.getMessages());
            }

        } catch (GitAPIException e) {
            logger.error(e.toString(), e);
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    protected boolean commitImpl(String projectName, String userName, String[] changes, String message) {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            for (String change : changes) {
                git.add().addFilepattern(change).call();
                git.add().setUpdate(true).addFilepattern(change).call();
            }

            CommitCommand commit = git.commit().setMessage(message);
            setCommitAuthor(commit, projectName, userName);
            commit.call();
        } catch (GitAPIException e) {
            logger.error(e.toString(), e);
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    public List<UncommittedChange> getUncommitedChangesImpl(String projectName, String userName) {
        Path projectPath = getProjectFolderPath(projectName);
        List<String> seenPaths = new ArrayList<>();
        List<UncommittedChange> result = new ArrayList<>();

        try (Git git = getGit(projectPath)) {
            Status status = git.status().call();

            Set<String> missing = status.getMissing();
            logger.debug("Missing files: {}" + missing);
            collectUncommittedChanges(projectName, missing, "Deleted", seenPaths, result);

            Set<String> uncommittedChanges = status.getUncommittedChanges();
            logger.debug("Uncommitted changes: {}" + uncommittedChanges);
            collectUncommittedChanges(projectName, uncommittedChanges, "Uncommitted", seenPaths, result);

            Set<String> untracked = status.getUntracked();
            logger.debug("Untracked files: {}" + untracked);
            collectUncommittedChanges(projectName, untracked, "Created", seenPaths, result);

            Set<String> modified = status.getChanged();
            logger.debug("Modified files: {}" + modified);
            collectUncommittedChanges(projectName, modified, "Modified", seenPaths, result);
        } catch (Exception e) {
            logger.error(e.toString(), e);
        }

        return result;
    }

    private void collectUncommittedChanges(String projectName,
                                           Set<String> updates,
                                           String type,
                                           List<String> seenPaths,
                                           List<UncommittedChange> result) {
        for (String update : updates) {
            String actor = "unknown";
            String path = update;

            if (hasActor(path)) {
                String[] pathSplitted = update.split("/");
                path = String.join("/", Arrays.copyOf(pathSplitted, pathSplitted.length - 1));
                actor = getActor(projectName, path);
            }

            if (!seenPaths.contains(path)) {
                seenPaths.add(path);
                result.add(new UncommittedChange(path, type, actor));
            }
        }
    }

    @Override
    public boolean isRegisteredUserImpl(String projectName, String userName) {
        boolean registered;
        try {
            GitProjectsConfigRecord gitProjectsConfigRecord = getGitProjectConfigRecord(projectName);
            getGitReposUserRecord(gitProjectsConfigRecord, userName);
            registered = true;
        } catch (Exception e) {
            registered = false;
        }
        return registered;
    }

    @Override
    protected boolean exportConfigImpl(String projectName) {
        Path projectFolderPath = getProjectFolderPath(projectName);
        exportImages(projectFolderPath);
        exportTheme(projectFolderPath);
        exportTag(projectFolderPath);
        return true;
    }

    @Override
    public void setupLocalRepoImpl(String projectName, String userName) throws Exception {
        Path projectFolderPath = getProjectFolderPath(projectName);
        GitProjectsConfigRecord gitProjectsConfigRecord = getGitProjectConfigRecord(projectName);

        Path path = projectFolderPath.resolve(".git");

        if (!Files.exists(path)) {
            try (Git git = Git.init().setDirectory(projectFolderPath.toFile()).call()) {
                disableSsl(git);

                final URIish urIish = new URIish(gitProjectsConfigRecord.getURI());

                git.remoteAdd().setName("origin").setUri(urIish).call();

                FetchCommand fetch = git.fetch().setRemote("origin");

                setAuthentication(fetch, projectName, userName);
                fetch.call();

                ListBranchCommand listBranches = git.branchList();
                listBranches.setListMode(ListBranchCommand.ListMode.REMOTE);
                List<Ref> branches = listBranches.call();

                if (branches.isEmpty()) {
                    setupGitFromCurrentFolder(projectName, userName, git);
                } else {
                    setupGitFromRemoteRepo(projectName, git);
                }
            } catch (Exception e) {
                logger.warn("An error occurred while setting up local repo for '" + projectName + "' project.", e);
            }
        }
    }

    private void setupGitFromCurrentFolder(String projectName, String userName, Git git) throws Exception {
        try {
            git.add().addFilepattern(".").call();

            CommitCommand commit = git.commit().setMessage("Initial commit");
            setCommitAuthor(commit, projectName, userName);
            commit.call();

            PushCommand pushCommand = git.push();

            setAuthentication(pushCommand, projectName, userName);

            String branch = git.getRepository().getBranch();
            pushCommand.setRemote("origin").setRefSpecs(new RefSpec(branch)).call();
        } catch (GitAPIException e) {
            logger.error(e.toString());
            throw new RuntimeException(e);
        }
    }

    private void setupGitFromRemoteRepo(String projectName, Git git) throws Exception {
        try {
            CheckoutCommand checkout = git.checkout()
                    .setName("master")
                    .setCreateBranch(true)
                    .setForced(true)
                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                    .setStartPoint("origin/master");
            checkout.call();

            git.clean().setForce(true).call();
            git.reset().setMode(ResetCommand.ResetType.HARD).call();

            GitTagManager.importTagManager(projectName);

            GitThemeManager.importTheme(projectName);

            GitImageManager.importImages(projectName);
        } catch (GitAPIException e) {
            logger.error(e.toString());
            throw new RuntimeException(e);
        }
    }

    @Override
    protected List<CommitInfo> getCommitHistoryImpl(String projectName, String userName, int maxCount) {
        List<CommitInfo> commits = new ArrayList<>();

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            Repository repository = git.getRepository();
            Iterable<RevCommit> logs = git.log().setMaxCount(maxCount > 0 ? maxCount : 100).call();

            for (RevCommit commit : logs) {
                String commitHash = commit.getName();
                String shortHash = commit.abbreviate(7).name();
                String message = commit.getFullMessage();
                String author = commit.getAuthorIdent().getName();
                String authorEmail = commit.getAuthorIdent().getEmailAddress();
                long timestamp = commit.getCommitTime() * 1000L; // Convert to milliseconds

                // Get list of files changed in this commit
                List<String> filesChanged = new ArrayList<>();

                try {
                    if (commit.getParentCount() > 0) {
                        RevCommit parent = commit.getParent(0);

                        try (DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
                            diffFormatter.setRepository(repository);
                            List<DiffEntry> diffs = diffFormatter.scan(parent.getTree(), commit.getTree());

                            for (DiffEntry diff : diffs) {
                                String path = diff.getChangeType() == DiffEntry.ChangeType.DELETE
                                        ? diff.getOldPath()
                                        : diff.getNewPath();
                                filesChanged.add(path);
                            }
                        }
                    } else {
                        // First commit - all files are new
                        try (RevWalk revWalk = new RevWalk(repository);
                             org.eclipse.jgit.treewalk.TreeWalk treeWalk = new org.eclipse.jgit.treewalk.TreeWalk(repository)) {

                            RevTree tree = commit.getTree();
                            treeWalk.addTree(tree);
                            treeWalk.setRecursive(true);

                            while (treeWalk.next()) {
                                filesChanged.add(treeWalk.getPathString());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Could not get files changed for commit: " + shortHash, e);
                }

                CommitInfo commitInfo = new CommitInfo(
                        commitHash, shortHash, message, author,
                        authorEmail, timestamp, filesChanged
                );
                commits.add(commitInfo);
            }
        } catch (Exception e) {
            logger.error("Error fetching commit history for project: " + projectName, e);
        }

        return commits;
    }

    private Path getProjectFolderPath(String projectName) {
        Path dataDir = context.getSystemManager().getDataDir().toPath();
        return dataDir.resolve("projects").resolve(projectName);
    }

    @Override
    protected List<BranchInfo> listBranchesImpl(String projectName, String userName) throws Exception {
        List<BranchInfo> branches = new ArrayList<>();

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            Repository repository = git.getRepository();
            String currentBranchName = repository.getBranch();

            // List all branches (local and remote)
            List<Ref> allBranches = git.branchList()
                    .setListMode(ListBranchCommand.ListMode.ALL)
                    .call();

            for (Ref ref : allBranches) {
                String fullName = ref.getName();
                String branchName;
                boolean isLocal = fullName.startsWith(Constants.R_HEADS);
                boolean isRemote = fullName.startsWith(Constants.R_REMOTES);

                if (isLocal) {
                    branchName = fullName.substring(Constants.R_HEADS.length());
                } else if (isRemote) {
                    branchName = fullName.substring(Constants.R_REMOTES.length());
                } else {
                    continue; // Skip tags and other refs
                }

                boolean isCurrent = isLocal && branchName.equals(currentBranchName);

                // Calculate ahead/behind counts for local branches with tracking
                int commitsAhead = 0;
                int commitsBehind = 0;

                if (isLocal) {
                    try {
                        BranchTrackingStatus trackingStatus = BranchTrackingStatus.of(repository, branchName);
                        if (trackingStatus != null) {
                            commitsAhead = trackingStatus.getAheadCount();
                            commitsBehind = trackingStatus.getBehindCount();
                        }
                    } catch (Exception e) {
                        logger.debug("Could not get tracking status for branch: " + branchName, e);
                    }
                }

                BranchInfo branchInfo = new BranchInfo(
                        branchName,
                        isLocal,
                        isRemote,
                        isCurrent,
                        commitsAhead,
                        commitsBehind,
                        fullName
                );
                branches.add(branchInfo);
            }

        } catch (Exception e) {
            logger.error("Error listing branches for project: " + projectName, e);
            throw new Exception("Failed to list branches: " + e.getMessage(), e);
        }

        return branches;
    }

    @Override
    protected boolean fetchFromRemoteImpl(String projectName, String userName) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            FetchCommand fetch = git.fetch().setRemote("origin");
            setAuthentication(fetch, projectName, userName);

            fetch.call();
            logger.info("Successfully fetched branches from remote for project: " + projectName);
            return true;

        } catch (Exception e) {
            logger.error("Error fetching from remote for project: " + projectName, e);
            throw new Exception("Failed to fetch from remote: " + e.getMessage(), e);
        }
    }

    @Override
    protected String getCurrentBranchImpl(String projectName) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            String branchName = git.getRepository().getBranch();
            logger.debug("Current branch for project " + projectName + ": " + branchName);
            return branchName;

        } catch (Exception e) {
            logger.error("Error getting current branch for project: " + projectName, e);
            throw new Exception("Failed to get current branch: " + e.getMessage(), e);
        }
    }

    @Override
    protected BranchStatus getBranchStatusImpl(String projectName, String userName) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            Repository repository = git.getRepository();
            String currentBranch = repository.getBranch();

            // Check for uncommitted changes
            Status status = git.status().call();

            LinkedHashSet<String> uncommittedFilesSet = new LinkedHashSet<>();
            uncommittedFilesSet.addAll(status.getModified());
            uncommittedFilesSet.addAll(status.getChanged());
            uncommittedFilesSet.addAll(status.getMissing());
            List<String> uncommittedFiles = new ArrayList<>(uncommittedFilesSet);

            List<String> untrackedFiles = new ArrayList<>(status.getUntracked());

            boolean hasUncommittedChanges = !uncommittedFiles.isEmpty() || !untrackedFiles.isEmpty();

            // Calculate unpushed commits
            int unpushedCommits = 0;
            try {
                BranchTrackingStatus trackingStatus = BranchTrackingStatus.of(repository, currentBranch);
                if (trackingStatus != null) {
                    unpushedCommits = trackingStatus.getAheadCount();
                }
            } catch (Exception e) {
                logger.debug("Could not get tracking status for current branch: " + currentBranch, e);
            }

            BranchStatus branchStatus = new BranchStatus(
                    hasUncommittedChanges,
                    unpushedCommits,
                    uncommittedFiles,
                    untrackedFiles,
                    currentBranch
            );

            logger.debug("Branch status for project " + projectName + ": " + branchStatus);
            return branchStatus;

        } catch (Exception e) {
            logger.error("Error getting branch status for project: " + projectName, e);
            throw new Exception("Failed to get branch status: " + e.getMessage(), e);
        }
    }

    @Override
    protected boolean switchBranchImpl(String projectName, String userName, String branchName, boolean createNew) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            CheckoutCommand checkout = git.checkout();

            if (createNew) {
                // Create new branch from current HEAD
                checkout.setCreateBranch(true)
                        .setName(branchName);
                logger.info("Creating and switching to new branch: " + branchName);

            } else {
                // Check if branch exists locally
                List<Ref> localBranches = git.branchList().call();
                boolean existsLocally = localBranches.stream()
                        .anyMatch(ref -> ref.getName().equals(Constants.R_HEADS + branchName));

                if (existsLocally) {
                    // Switch to existing local branch
                    checkout.setName(branchName);
                    logger.info("Switching to existing local branch: " + branchName);

                } else {
                    // Check if it exists as a remote branch
                    List<Ref> remoteBranches = git.branchList()
                            .setListMode(ListBranchCommand.ListMode.REMOTE)
                            .call();

                    boolean existsRemotely = remoteBranches.stream()
                            .anyMatch(ref -> ref.getName().equals(Constants.R_REMOTES + "origin/" + branchName));

                    if (existsRemotely) {
                        // Create local tracking branch from remote
                        checkout.setCreateBranch(true)
                                .setName(branchName)
                                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                                .setStartPoint("origin/" + branchName);
                        logger.info("Creating local tracking branch from remote: " + branchName);

                    } else {
                        throw new Exception("Branch '" + branchName + "' does not exist locally or remotely.");
                    }
                }
            }

            checkout.call();
            logger.info("Successfully switched to branch: " + branchName);

            // Reload the project to reflect the new branch's contents
            GitProjectManager.importProject(projectName);

            // Import associated resources that may have changed
            GitTagManager.importTagManager(projectName);
            GitThemeManager.importTheme(projectName);
            GitImageManager.importImages(projectName);

            return true;

        } catch (Exception e) {
            logger.error("Error switching branch for project: " + projectName, e);
            throw new Exception("Failed to switch branch: " + e.getMessage(), e);
        }
    }
}
