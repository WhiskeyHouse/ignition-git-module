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
import org.eclipse.jgit.lib.RepositoryState;
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

import simpleorm.dataset.SQuery;

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
            // Get the actual remote name (may not be "origin")
            String remoteName = getRemoteName(git);

            PullCommand pull = git.pull();
            pull.setRemote(remoteName);
            setAuthentication(pull, projectName, userName);

            logger.info("Pulling from remote '" + remoteName + "' for project: " + projectName);
            PullResult result = pull.call();
            if (!result.isSuccessful()) {
                logger.warn("Cannot pull from git");
            } else {
                logger.info("Pull was successful.");
            }

            GitProjectManager.importProject(projectName);

            if (importTags) {
                GitTagManager.importTagManager(projectName, null);
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
            // Get the actual remote name (may not be "origin")
            String remoteName = getRemoteName(git);

            PushCommand push = git.push();
            push.setRemote(remoteName);

            setAuthentication(push, projectName, userName);

            logger.info("Pushing to remote '" + remoteName + "' for project: " + projectName);
            Iterable<PushResult> results = push.setPushAll().setPushTags().call();
            for (PushResult result : results) {
                logger.trace(result.getMessages());
            }
            logger.info("Successfully pushed to remote '" + remoteName + "' for project: " + projectName);

        } catch (GitAPIException e) {
            logger.error("Error pushing to remote for project " + projectName + ": " + e.toString(), e);
            throw new RuntimeException(e);
        }
        return true;
    }

    @Override
    protected boolean commitImpl(String projectName, String userName, String[] changes, String message) {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            if (changes != null && changes.length > 0) {
                AddCommand addCommand = git.add();
                AddCommand updateCommand = git.add().setUpdate(true);
                boolean hasValidPattern = false;
                for (String change : changes) {
                    if (change != null && !change.isEmpty()) {
                        addCommand.addFilepattern(change);
                        updateCommand.addFilepattern(change);
                        hasValidPattern = true;
                    }
                }
                if (hasValidPattern) {
                    addCommand.call();
                    updateCommand.call();
                } else {
                    logger.warn("commitImpl called for project '" + projectName + "' with no valid file patterns; skipping add/update.");
                    return false;
                }
            } else {
                logger.warn("commitImpl called for project '" + projectName + "' with null or empty changes; skipping commit.");
                return false;
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

            GitTagManager.importTagManager(projectName, null);

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
        final int MAX_BRANCHES = 500; // Limit to prevent memory issues

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            Repository repository = git.getRepository();
            String currentBranchName = repository.getBranch();
            String remoteName = getRemoteName(git);

            // List all branches (local and remote)
            List<Ref> allBranches = git.branchList()
                    .setListMode(ListBranchCommand.ListMode.ALL)
                    .call();

            logger.debug("Total branches found: " + allBranches.size());

            // Process local branches first (they're more important)
            List<Ref> localRefs = new ArrayList<>();
            List<Ref> remoteRefs = new ArrayList<>();

            for (Ref ref : allBranches) {
                if (ref.getName().startsWith(Constants.R_HEADS)) {
                    localRefs.add(ref);
                } else if (ref.getName().startsWith(Constants.R_REMOTES)) {
                    remoteRefs.add(ref);
                }
            }

            // Process local branches
            for (Ref ref : localRefs) {
                if (branches.size() >= MAX_BRANCHES) {
                    logger.warn("Branch limit reached (" + MAX_BRANCHES + "), some branches will not be shown");
                    break;
                }

                String fullName = ref.getName();
                String branchName = fullName.substring(Constants.R_HEADS.length());
                boolean isCurrent = branchName.equals(currentBranchName);

                // Calculate ahead/behind counts for local branches with tracking
                int commitsAhead = 0;
                int commitsBehind = 0;

                try {
                    BranchTrackingStatus trackingStatus = BranchTrackingStatus.of(repository, branchName);
                    if (trackingStatus != null) {
                        commitsAhead = trackingStatus.getAheadCount();
                        commitsBehind = trackingStatus.getBehindCount();
                    }
                } catch (Exception e) {
                    logger.debug("Could not get tracking status for branch: " + branchName, e);
                }

                BranchInfo branchInfo = new BranchInfo(
                        branchName,
                        true,  // isLocal
                        false, // isRemote (will be updated if remote exists)
                        isCurrent,
                        commitsAhead,
                        commitsBehind,
                        fullName
                );
                branches.add(branchInfo);
            }

            // Process remote branches
            for (Ref ref : remoteRefs) {
                if (branches.size() >= MAX_BRANCHES) {
                    logger.warn("Branch limit reached (" + MAX_BRANCHES + "), some remote branches will not be shown");
                    break;
                }

                String fullName = ref.getName();
                // Strip refs/remotes/ prefix
                String remoteBranchName = fullName.substring(Constants.R_REMOTES.length());
                String branchName;

                // Also strip the remote name prefix (e.g., "origin/" or "whk-distillery01-ignition-global/")
                if (remoteBranchName.startsWith(remoteName + "/")) {
                    branchName = remoteBranchName.substring(remoteName.length() + 1);
                } else {
                    // Fallback: try to strip any remote prefix (everything before first /)
                    int slashIndex = remoteBranchName.indexOf('/');
                    branchName = slashIndex > 0 ? remoteBranchName.substring(slashIndex + 1) : remoteBranchName;
                }

                // Skip HEAD reference
                if ("HEAD".equals(branchName)) {
                    continue;
                }

                BranchInfo branchInfo = new BranchInfo(
                        branchName,
                        false, // isLocal
                        true,  // isRemote
                        false, // isCurrent
                        0,     // commitsAhead
                        0,     // commitsBehind
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
            // Get the actual remote name from the repository config
            // (it may not be "origin" - the cloning process uses the repo name as remote name)
            String remoteName = getRemoteName(git);

            FetchCommand fetch = git.fetch().setRemote(remoteName);
            setAuthentication(fetch, projectName, userName);

            fetch.call();
            logger.info("Successfully fetched branches from remote '" + remoteName + "' for project: " + projectName);
            return true;

        } catch (Exception e) {
            logger.error("Error fetching from remote for project: " + projectName, e);
            throw new Exception("Failed to fetch from remote: " + e.getMessage(), e);
        }
    }

    /**
     * Gets the remote name for a repository. Returns the first configured remote,
     * or "origin" as fallback if no remotes are configured.
     */
    private String getRemoteName(Git git) {
        try {
            var remotes = git.getRepository().getRemoteNames();
            if (remotes != null && !remotes.isEmpty()) {
                String remoteName = remotes.iterator().next();
                logger.debug("Using remote: " + remoteName);
                return remoteName;
            }
        } catch (Exception e) {
            logger.warn("Error getting remote name, falling back to 'origin': " + e.getMessage());
        }
        return "origin";
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

            // Get conflicting files
            List<String> conflictingFiles = new ArrayList<>(status.getConflicting());

            // Check if repository is in a merging state
            RepositoryState repoState = repository.getRepositoryState();
            boolean isMerging = repoState == RepositoryState.MERGING ||
                               repoState == RepositoryState.MERGING_RESOLVED;

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
                    conflictingFiles,
                    isMerging,
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
    protected boolean switchBranchImpl(String projectName, String userName, String branchName, boolean createNew, boolean forceCheckout) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            CheckoutCommand checkout = git.checkout();
            String remoteName = getRemoteName(git);

            // If force checkout, discard any local changes
            if (forceCheckout) {
                checkout.setForced(true);
                logger.info("Force checkout enabled - local changes will be discarded");
            }

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

                    // Check for remote branch with the actual remote name
                    String remoteRef = Constants.R_REMOTES + remoteName + "/" + branchName;
                    boolean existsRemotely = remoteBranches.stream()
                            .anyMatch(ref -> ref.getName().equals(remoteRef));

                    if (existsRemotely) {
                        // Create local tracking branch from remote
                        checkout.setCreateBranch(true)
                                .setName(branchName)
                                .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.TRACK)
                                .setStartPoint(remoteName + "/" + branchName);
                        logger.info("Creating local tracking branch from remote '" + remoteName + "': " + branchName);

                    } else {
                        throw new Exception("Branch '" + branchName + "' does not exist locally or remotely.");
                    }
                }
            }

            checkout.call();
            logger.info("Successfully switched to branch: " + branchName);

            // Reload the project to reflect the new branch's contents
            GitProjectManager.importProject(projectName);

            // NOTE: Tags, images, and themes are gateway-global resources.
            // Auto-importing them here would overwrite ALL projects' resources,
            // not just this project's. Users should explicitly import these
            // via the Import Resources action when needed.

            return true;

        } catch (Exception e) {
            logger.error("Error switching branch for project: " + projectName, e);
            throw new Exception("Failed to switch branch: " + e.getMessage(), e);
        }
    }

    @Override
    protected boolean stashChangesImpl(String projectName, String userName, String message) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            StashCreateCommand stash = git.stashCreate();

            if (message != null && !message.trim().isEmpty()) {
                stash.setWorkingDirectoryMessage(message);
            }

            RevCommit stashCommit = stash.call();

            if (stashCommit != null) {
                logger.info("Successfully stashed changes for project: " + projectName + " (stash: " + stashCommit.getName() + ")");
                return true;
            } else {
                logger.info("No changes to stash for project: " + projectName);
                return false;
            }

        } catch (Exception e) {
            logger.error("Error stashing changes for project: " + projectName, e);
            throw new Exception("Failed to stash changes: " + e.getMessage(), e);
        }
    }

    @Override
    protected boolean stashPopImpl(String projectName, String userName) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            ObjectId stashRef = git.stashApply().call();

            // Only drop the stash if there are no conflicts
            Status status = git.status().call();
            if (status.getConflicting().isEmpty()) {
                git.stashDrop().call();
                logger.info("Successfully applied and dropped stash for project: " + projectName);
            } else {
                logger.warn("Conflicts detected after stash apply; stash retained for project: " + projectName);
            }

            // Reload the project to reflect the restored changes
            GitProjectManager.importProject(projectName);

            return true;

        } catch (Exception e) {
            logger.error("Error applying stash for project: " + projectName, e);
            throw new Exception("Failed to apply stash: " + e.getMessage(), e);
        }
    }

    @Override
    protected boolean discardAllChangesImpl(String projectName, String userName) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            // Reset all tracked files to HEAD
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .call();

            // Clean untracked files
            git.clean()
                .setCleanDirectories(true)
                .setForce(true)
                .call();

            logger.info("Successfully discarded all changes for project: " + projectName);

            // Reload the project to reflect the clean state
            GitProjectManager.importProject(projectName);

            return true;

        } catch (Exception e) {
            logger.error("Error discarding changes for project: " + projectName, e);
            throw new Exception("Failed to discard changes: " + e.getMessage(), e);
        }
    }

    @Override
    protected List<String> getConflictingFilesImpl(String projectName) throws Exception {
        List<String> conflictingFiles = new ArrayList<>();

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            Status status = git.status().call();
            conflictingFiles.addAll(status.getConflicting());

            logger.debug("Conflicting files for project " + projectName + ": " + conflictingFiles);
            return conflictingFiles;

        } catch (Exception e) {
            logger.error("Error getting conflicting files for project: " + projectName, e);
            throw new Exception("Failed to get conflicting files: " + e.getMessage(), e);
        }
    }

    @Override
    protected boolean resolveConflictsImpl(String projectName, String strategy) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            Status status = git.status().call();
            Set<String> conflictingFiles = status.getConflicting();

            if (conflictingFiles.isEmpty()) {
                logger.info("No conflicts to resolve for project: " + projectName);
                return true;
            }

            if ("ours".equalsIgnoreCase(strategy)) {
                // Resolve conflicts by keeping our version (current branch)
                for (String file : conflictingFiles) {
                    git.checkout()
                        .setStage(CheckoutCommand.Stage.OURS)
                        .addPath(file)
                        .call();
                    git.add().addFilepattern(file).call();
                }
                logger.info("Resolved " + conflictingFiles.size() + " conflicts using 'ours' strategy");

            } else if ("theirs".equalsIgnoreCase(strategy)) {
                // Resolve conflicts by accepting their version (incoming changes)
                for (String file : conflictingFiles) {
                    git.checkout()
                        .setStage(CheckoutCommand.Stage.THEIRS)
                        .addPath(file)
                        .call();
                    git.add().addFilepattern(file).call();
                }
                logger.info("Resolved " + conflictingFiles.size() + " conflicts using 'theirs' strategy");

            } else {
                throw new Exception("Invalid conflict resolution strategy: " + strategy + ". Use 'ours' or 'theirs'.");
            }

            // Reload the project to reflect the resolved state
            GitProjectManager.importProject(projectName);

            return true;

        } catch (Exception e) {
            logger.error("Error resolving conflicts for project: " + projectName, e);
            throw new Exception("Failed to resolve conflicts: " + e.getMessage(), e);
        }
    }

    @Override
    protected boolean abortMergeImpl(String projectName) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            Repository repository = git.getRepository();

            // Check if we're in a merge state
            RepositoryState state = repository.getRepositoryState();
            if (!state.equals(RepositoryState.MERGING) && !state.equals(RepositoryState.MERGING_RESOLVED)) {
                logger.info("Repository is not in a merge state for project: " + projectName);
                return false;
            }

            // Reset to HEAD to abort the merge
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef(Constants.HEAD)
                .call();

            logger.info("Successfully aborted merge for project: " + projectName);

            // Reload the project to reflect the clean state
            GitProjectManager.importProject(projectName);

            return true;

        } catch (Exception e) {
            logger.error("Error aborting merge for project: " + projectName, e);
            throw new Exception("Failed to abort merge: " + e.getMessage(), e);
        }
    }

    @Override
    protected boolean hasConflictsImpl(String projectName) throws Exception {
        try (Git git = getGit(getProjectFolderPath(projectName))) {
            Status status = git.status().call();
            return !status.getConflicting().isEmpty();
        } catch (Exception e) {
            logger.error("Error checking for conflicts for project: " + projectName, e);
            throw new Exception("Failed to check for conflicts: " + e.getMessage(), e);
        }
    }

    @Override
    protected boolean importResourcesImpl(String projectName, boolean importTags, boolean importTheme, boolean importImages, String collisionPolicy) throws Exception {
        try {
            logger.info("Importing resources for project: " + projectName +
                       " (tags=" + importTags + ", theme=" + importTheme + ", images=" + importImages +
                       ", collisionPolicy=" + collisionPolicy + ")");

            if (importTags) {
                GitTagManager.importTagManager(projectName, collisionPolicy);
                logger.info("Successfully imported tags for project: " + projectName);
            }
            if (importTheme) {
                GitThemeManager.importTheme(projectName);
                logger.info("Successfully imported theme for project: " + projectName);
            }
            if (importImages) {
                GitImageManager.importImages(projectName);
                logger.info("Successfully imported images for project: " + projectName);
            }

            return true;

        } catch (Exception e) {
            logger.error("Error importing resources for project: " + projectName, e);
            throw new Exception("Failed to import resources: " + e.getMessage(), e);
        }
    }

    @Override
    protected List<String> getGitTrackedProjectNamesImpl() {
        List<String> projectNames = new ArrayList<>();
        try {
            SQuery<GitProjectsConfigRecord> query = new SQuery<>(GitProjectsConfigRecord.META);
            List<GitProjectsConfigRecord> records = context.getPersistenceInterface().query(query);
            for (GitProjectsConfigRecord record : records) {
                projectNames.add(record.getProjectName());
            }
        } catch (Exception e) {
            logger.warn("Error querying Git-tracked projects.", e);
        }
        return projectNames;
    }
}
