package com.axone_io.ignition.git;

import com.axone_io.ignition.git.commissioning.utils.GitCommissioningUtils;
import com.axone_io.ignition.git.dto.HotfixResult;
import com.axone_io.ignition.git.dto.ProductionModeConfig;
import com.axone_io.ignition.git.managers.GitImageManager;
import com.axone_io.ignition.git.managers.GitManager;
import com.axone_io.ignition.git.managers.GitProjectManager;
import com.axone_io.ignition.git.managers.GitPullPolicy;
import com.axone_io.ignition.git.managers.GitTagManager;
import com.axone_io.ignition.git.managers.GitThemeManager;
import com.axone_io.ignition.git.managers.HotfixManager;
import com.axone_io.ignition.git.managers.ProductionModeManager;
import com.axone_io.ignition.git.records.GitProjectsConfigRecord;
import com.axone_io.ignition.git.records.GitReposUsersRecord;
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

import java.io.IOException;
import java.nio.file.DirectoryStream;
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

        // Check production mode before pull
        ProductionModeConfig prodConfig = getProductionModeConfigImpl(projectName);
        if (prodConfig.isProductionMode()) {
            logger.info("[Production Git Operation] PULL initiated by user '" + userName + "' on project: " + projectName);
        }

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            // Validate production mode constraints before doing anything else
            if (prodConfig.isProductionMode()) {
                boolean isValid = ProductionModeManager.validatePull(git, prodConfig);
                if (!isValid) {
                    String errorMsg = "[Production Git Operation] Validation failed for PULL: " + prodConfig.getWarningMessage();
                    logger.error(errorMsg);
                    throw new RuntimeException(errorMsg);
                }
                logger.info("[Production Git Operation] Validation passed for PULL on branch '" + git.getRepository().getBranch() + "', project: " + projectName);

                // Backup tags after validation passes but before the actual pull
                if (importTags) {
                    logger.info("[Production Git Operation] Backing up tags before pull for project: " + projectName);
                    backupCurrentTagsImpl(projectName);
                    logger.info("[Production Git Operation] Tag backup completed for project: " + projectName);
                }
            }

            // Get the actual remote name (may not be "origin")
            String remoteName = getRemoteName(git);

            // Fast-forward or fail: never inherit pull.rebase from repository config, and never
            // let a deploy silently merge a diverged gateway branch.
            PullCommand pull = GitPullPolicy.applyTo(git.pull());
            pull.setRemote(remoteName);
            setAuthentication(pull, projectName, userName);

            logger.info("Pulling from remote '" + remoteName + "' for project: " + projectName);
            PullResult result = pull.call();

            // A failed pull leaves the working tree on the old revision — or, if it got far enough
            // to conflict, half-applied. Importing either into a running gateway is worse than not
            // deploying at all, so stop here rather than continuing on a warning.
            String failure = GitPullPolicy.describeFailure(result, git.getRepository());
            if (failure != null) {
                String errorMsg = "Pull failed for project '" + projectName + "': " + failure
                        + " Nothing was imported into the gateway.";
                logger.error(errorMsg);
                throw new RuntimeException(errorMsg);
            }
            logger.info("Pull was successful.");

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
            String currentBranch = git.getRepository().getBranch();

            // Check production mode before push
            ProductionModeConfig prodConfig = getProductionModeConfigImpl(projectName);
            if (prodConfig.isProductionMode()) {
                logger.info("[Production Git Operation] PUSH initiated by user '" + userName + "' on branch '" + currentBranch + "', project: " + projectName);

                boolean isValid = ProductionModeManager.validatePush(git, prodConfig, currentBranch);
                if (!isValid) {
                    String errorMsg = "[Production Git Operation] Validation failed for PUSH to branch '" + currentBranch + "': " + prodConfig.getWarningMessage();
                    logger.error(errorMsg);
                    throw new RuntimeException(errorMsg);
                }
                logger.info("[Production Git Operation] Validation passed for PUSH on branch '" + currentBranch + "', project: " + projectName);
            }

            // Get the actual remote name (may not be "origin")
            String remoteName = getRemoteName(git);

            PushCommand push = git.push();
            push.setRemote(remoteName);

            setAuthentication(push, projectName, userName);

            // Push only the current branch (not all branches) to avoid bypassing production guards
            RefSpec currentRefSpec = new RefSpec(
                    Constants.R_HEADS + currentBranch + ":" + Constants.R_HEADS + currentBranch
            );
            logger.info("Pushing branch '" + currentBranch + "' to remote '" + remoteName + "' for project: " + projectName);
            Iterable<PushResult> results = push.setRefSpecs(currentRefSpec).setPushTags().call();
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
        // Images, themes and tags all come from the gateway, not from this project. Only the
        // designated owner may write them, otherwise every git-backed project on the gateway keeps
        // its own competing copy of the same shared state.
        String skipReason = GatewayResourceExportPolicy.describeSkipReason(
                projectName, getGatewayResourceOwners());
        if (skipReason != null) {
            logger.warn("Not exporting gateway-scoped resources for project '" + projectName
                    + "': " + skipReason);
            return true;
        }

        Path projectFolderPath = getProjectFolderPath(projectName);
        exportImages(projectFolderPath);
        exportTheme(projectFolderPath);
        exportTag(projectFolderPath);
        return true;
    }

    /** Names of every configured project with {@code ExportGatewayResources} enabled. */
    private List<String> getGatewayResourceOwners() {
        List<String> owners = new ArrayList<>();
        try {
            SQuery<GitProjectsConfigRecord> query = new SQuery<>(GitProjectsConfigRecord.META);
            for (GitProjectsConfigRecord record : context.getPersistenceInterface().query(query)) {
                if (record.isExportGatewayResources()) {
                    owners.add(record.getProjectName());
                }
            }
        } catch (Exception e) {
            // An unreadable config must not be mistaken for "nobody owns them, so go ahead" —
            // describeSkipReason treats an empty list as unconfigured and refuses the export.
            logger.error("Could not determine which project owns gateway-scoped resources", e);
        }
        return owners;
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
     * Gets the remote name for a repository. Delegates to GitManager.
     */
    private String getRemoteName(Git git) {
        return GitManager.getRemoteName(git);
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
    protected boolean importTagsImpl(String projectName, String collisionPolicy) throws Exception {
        GitTagManager.importTagManager(projectName, collisionPolicy);
        return true;
    }

    @Override
    protected String diagnoseTagGroupsImpl() throws Exception {
        return com.axone_io.ignition.git.managers.GitTagGroupManager.diagnoseTagGroups();
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

    @Override
    protected List<String> listDocsForResourceImpl(String projectName, String resourcePath) throws Exception {
        List<String> docs = new ArrayList<>();
        Path projectDir = getProjectFolderPath(projectName);

        if (!Files.isDirectory(projectDir)) {
            return docs;
        }

        // Resolve and validate the resource path (prevent traversal)
        Path resourceDir = projectDir.resolve(resourcePath).normalize();
        if (!resourceDir.startsWith(projectDir)) {
            logger.warn("Path traversal attempt blocked: " + resourcePath);
            return docs;
        }

        // Only scan the resource's own directory for .md files
        if (Files.isDirectory(resourceDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(resourceDir, "*.md")) {
                for (Path mdFile : stream) {
                    if (Files.isRegularFile(mdFile)) {
                        docs.add(projectDir.relativize(mdFile).toString());
                    }
                }
            } catch (IOException e) {
                logger.debug("Error listing docs in " + resourceDir + ": " + e.getMessage());
            }
        }

        return docs;
    }

    @Override
    protected ProductionModeConfig getProductionModeConfigImpl(String projectName) throws Exception {
        logger.debug("Getting production mode config for project: " + projectName);

        GitProjectsConfigRecord config = GitManager.getGitProjectConfigRecord(projectName);
        if (config == null) {
            logger.warn("No git config found for project: " + projectName);
            return new ProductionModeConfig(false, null, null);
        }

        return ProductionModeManager.buildConfig(config);
    }

    @Override
    protected String getUnpushedProductionCommitsImpl(String projectName) throws Exception {
        GitProjectsConfigRecord config = GitManager.getGitProjectConfigRecord(projectName);
        if (config == null || !config.getProductionMode()) {
            return null;
        }

        String productionBranch = config.getProductionBranch();
        if (productionBranch == null || productionBranch.isEmpty()) {
            productionBranch = "main";
        }

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            return ProductionModeManager.describeUnpushedProductionCommits(git, productionBranch);
        }
    }

    @Override
    protected boolean validateProductionModePullImpl(String projectName, String userName) throws Exception {
        logger.info("[Production Git Operation] Validating PULL for user '" + userName + "', project: " + projectName);

        ProductionModeConfig config = getProductionModeConfigImpl(projectName);
        if (!config.isProductionMode()) {
            return true;
        }

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            boolean isValid = ProductionModeManager.validatePull(git, config);
            if (!isValid) {
                logger.warn("[Production Git Operation] PULL validation failed for user '" + userName + "': " + config.getWarningMessage());
            }
            return isValid;
        }
    }

    @Override
    protected boolean validateProductionModePushImpl(String projectName, String userName, String targetBranch) throws Exception {
        logger.info("[Production Git Operation] Validating PUSH to branch '" + targetBranch + "' for user '" + userName + "', project: " + projectName);

        ProductionModeConfig config = getProductionModeConfigImpl(projectName);
        if (!config.isProductionMode()) {
            return true;
        }

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            boolean isValid = ProductionModeManager.validatePush(git, config, targetBranch);
            if (!isValid) {
                logger.warn("[Production Git Operation] PUSH validation failed for user '" + userName + "' to branch '" + targetBranch + "': " + config.getWarningMessage());
            }
            return isValid;
        }
    }

    @Override
    protected List<String> listRepositoryTagsImpl(String projectName) throws Exception {
        logger.debug("Listing repository tags for project: " + projectName);

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            return ProductionModeManager.listTags(git);
        }
    }

    @Override
    protected boolean backupCurrentTagsImpl(String projectName) throws Exception {
        logger.info("[Production Git Operation] Backing up current tags for project: " + projectName);

        try {
            Path projectPath = getProjectFolderPath(projectName);
            Path backupPath = projectPath.resolve(".git").resolve("tags_backup");

            // Clear previous backup and recreate directory
            if (Files.exists(backupPath)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(backupPath)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
                }
            }
            Files.createDirectories(backupPath);

            try (Git git = getGit(projectPath)) {
                List<Ref> tags = git.tagList().call();

                // Export each tag reference to backup
                for (Ref tag : tags) {
                    String tagName = tag.getName();
                    if (tagName.startsWith("refs/tags/")) {
                        tagName = tagName.substring("refs/tags/".length());
                    }

                    Path tagBackupFile = backupPath.resolve(tagName + ".ref");
                    Files.createDirectories(tagBackupFile.getParent());
                    String objectId = tag.getObjectId().getName();

                    Files.writeString(tagBackupFile, objectId);
                    logger.debug("[Production Git Operation] Backed up tag: " + tagName + " -> " + objectId);
                }

                logger.info("[Production Git Operation] Successfully backed up " + tags.size() + " tags for project: " + projectName + " (backup location: " + backupPath + ")");
                return true;
            }
        } catch (Exception e) {
            logger.error("[Production Git Operation] Error backing up tags for project: " + projectName, e);
            throw new RuntimeException("Failed to backup tags", e);
        }
    }

    @Override
    protected boolean restoreTagsFromBackupImpl(String projectName) throws Exception {
        logger.info("Restoring tags from backup for project: " + projectName);

        try {
            Path projectPath = getProjectFolderPath(projectName);
            Path backupPath = projectPath.resolve(".git").resolve("tags_backup");

            if (!Files.exists(backupPath)) {
                logger.warn("No tag backup found for project: " + projectName);
                return false;
            }

            try (Git git = getGit(projectPath)) {
                int failed = 0;
                // Walk recursively to find .ref files in subdirectories (slash-delimited tags)
                try (java.util.stream.Stream<Path> stream = Files.walk(backupPath)
                        .filter(p -> p.toString().endsWith(".ref") && Files.isRegularFile(p))) {
                    for (Path backupFile : (Iterable<Path>) stream::iterator) {
                        // Reconstruct tag name from relative path (e.g., release/v1.ref -> release/v1)
                        String relativePath = backupPath.relativize(backupFile).toString();
                        String tagName = relativePath.substring(0, relativePath.length() - 4); // Remove .ref

                        String objectId = Files.readString(backupFile).trim();

                        // Restore tag reference directly without type validation
                        // (annotated tags store a tag object ID, not a commit ID)
                        try {
                            RefUpdate refUpdate = git.getRepository().updateRef(Constants.R_TAGS + tagName);
                            refUpdate.setNewObjectId(ObjectId.fromString(objectId));
                            refUpdate.setForceUpdate(true);
                            RefUpdate.Result result = refUpdate.update();

                            if (result == RefUpdate.Result.NEW
                                    || result == RefUpdate.Result.FORCED
                                    || result == RefUpdate.Result.NO_CHANGE
                                    || result == RefUpdate.Result.FAST_FORWARD) {
                                logger.debug("Restored tag: " + tagName + " -> " + objectId);
                            } else {
                                failed++;
                                logger.warn("Unexpected result while restoring tag '" + tagName + "': " + result);
                            }
                        } catch (Exception e) {
                            failed++;
                            logger.warn("Failed to restore tag: " + tagName, e);
                        }
                    }
                }

                if (failed > 0) {
                    logger.warn("Restored tags with " + failed + " failures for project: " + projectName);
                    return false;
                }
                logger.info("Successfully restored tags from backup for project: " + projectName);
                return true;
            }
        } catch (Exception e) {
            logger.error("Error restoring tags from backup for project: " + projectName, e);
            throw new RuntimeException("Failed to restore tags from backup", e);
        }
    }

    // --- Hotfix operations ---

    @Override
    protected HotfixResult executeHotfixImpl(String projectName, String userName,
                                             String hotfixDescription, String commitMessage,
                                             String[] changes) throws Exception {
        logger.info("[Production Hotfix] executeHotfix called for project '" + projectName + "' by user '" + userName + "'");

        GitProjectsConfigRecord configRecord = GitManager.getGitProjectConfigRecord(projectName);
        GitReposUsersRecord userRecord = GitManager.getGitReposUserRecord(configRecord, userName);
        ProductionModeConfig prodConfig = ProductionModeManager.buildConfig(configRecord);

        if (!prodConfig.isProductionMode()) {
            throw new RuntimeException("Hotfix workflow requires production mode to be enabled");
        }

        String repoUri = configRecord.getURI();
        String token = configRecord.isSSHAuthentication() ? null : userRecord.getPassword();
        String gatewayName;
        try {
            gatewayName = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            gatewayName = "ignition-gateway";
        }
        String productionBranch = prodConfig.getProductionBranch();
        String userEmail = userRecord.getEmail();

        if (productionBranch == null || productionBranch.isEmpty()) {
            productionBranch = "main";
        }

        try (Git git = getGit(getProjectFolderPath(projectName))) {
            return HotfixManager.execute(
                git, projectName, userName, hotfixDescription, commitMessage, changes,
                userEmail, repoUri, token, gatewayName, productionBranch,
                configRecord, context.getPersistenceInterface()
            );
        }
    }

    @Override
    protected HotfixResult getHotfixProgressImpl(String projectName) throws Exception {
        HotfixResult active = HotfixManager.getActiveHotfix(projectName);
        if (active != null) {
            return active;
        }
        return getLastHotfixStatusImpl(projectName);
    }

    @Override
    protected HotfixResult getLastHotfixStatusImpl(String projectName) throws Exception {
        GitProjectsConfigRecord config = GitManager.getGitProjectConfigRecord(projectName);
        HotfixResult result = new HotfixResult();
        result.setPipelineComplete(true);

        String status = config.getLastHotfixStatus();
        if (status == null || status.isEmpty()) {
            return result;
        }

        result.setPipelineSuccess("COMPLETED".equals(status));
        result.setHotfixBranch(config.getLastHotfixBranch());
        result.setPrUrl(config.getLastHotfixPRUrl());
        return result;
    }
}
