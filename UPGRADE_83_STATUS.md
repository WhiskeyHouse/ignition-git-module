# Ignition 8.3.x Upgrade Status

## Overview
This document tracks the progress of upgrading the Ignition Git Module from 8.1.0 to 8.3.1.

## ✅ Completed Upgrades

### 1. Core Platform & Dependencies
- ✅ Updated `ignition-platform-version`: 8.1.0 → 8.3.1
- ✅ Updated Java version: 11 → 17
- ✅ Updated module version: 1.0.3 → 2.0.0
- ✅ Updated `maven-compiler-plugin`: 3.2 → 3.11.0
- ✅ Updated `ignition-maven-plugin`: 1.1.0 → 1.3.0

### 2. Third-Party Dependencies
- ✅ SnakeYAML: 1.29 → 2.2 (security fixes)
- ✅ JUnit: 4.12 → 4.13.2
- ✅ Lombok: 1.18.30 → 1.18.34
- ✅ JGit: 6.5.0 → 7.1.0

### 3. RPC Pattern Migration (8.3+ Pattern)
- ✅ Added `@RpcInterface(packageId = "com.axone_io.ignition.git")` to `GitScriptInterface`
- ✅ Updated `GatewayHook.java:50` to register RPC with `context.getRPCManager().registerHandler()`
- ✅ Updated `GatewayHook.shutdown()` to unregister RPC interface
- ✅ Updated `DesignerHook.java:49` to use `context.getGatewayInterface().getRpcInterface()`
- ✅ Removed deprecated `getRPCHandler()` method
- ✅ Removed deprecated `@ScriptFunction` and `@ScriptArg` annotations

### 4. Secrets Management Migration
- ✅ Changed `EncodedStringField` → `SecretConfig` in `GitReposUsersRecord.java:32`
- ⚠️ Temporarily using `Password.getValue(this)` / `Password.setValue(this, password)`
  - **TODO**: Implement proper 8.3 Secret API pattern with `Secret.create()`

### 5. Gateway Web UI (Temporarily Disabled)
- ✅ Commented out `getConfigPanels()` and `getConfigCategories()` in `GatewayHook`
- ✅ Disabled all web page classes (renamed `.java` → `.java.disabled`):
  - `GitProjectsConfigPage.java`
  - `GitProjectsConfigEditPage.java`
  - `GitReposUsersPage.java`
  - `GitReposUsersEditPage.java`
  - `CustomRecordListModel.java`
  - `ProjectListEditorSource.java`
  - `ProjectSourceEditor.java`
- ✅ Commented out form metadata static blocks in:
  - `GitReposUsersRecord.java`
  - `GitProjectsConfigRecord.java`

## ⚠️ Known Issues / Remaining Work

### High Priority

#### 1. Project Management API Changes
The Project API has changed significantly in 8.3. Affected files:
- `GitProjectManager.java` - Uses old `ProjectManifest`, `ProjectSnapshot`, `ProjectResource` APIs
- Need to migrate to new 8.3 Project API

#### 2. Persistent Record Category API
- `Category` class references in record definitions cause compilation errors
- May need to be removed or replaced with 8.3 equivalent

#### 3. Commissioning Configuration
- `GitCommissioningConfig.java` - Has Lombok-related compilation errors
- May need to regenerate or fix Lombok annotations for Java 17

#### 4. Image Manager API
- `GitImageManager.java` - Uses deprecated Image API
- `ImageFormat`, `ImageRecord` types have changed

### Medium Priority

#### 5. Secrets API Implementation
Current implementation in `GitReposUsersRecord`:
```java
// Temporary - needs proper Secret.create() pattern
return Password.getValue(this);
```

Proper 8.3 pattern (from upgrade guide):
```java
Secret secret = Secret.create(context, Password);
String value = secret.getValue();
// Promptly clear from memory
secret.clear();
```

#### 6. Web UI Migration to React
The gateway config pages need to be completely rewritten using:
- React-based panels (`BasicReactPanel`)
- Modern web stack (Jakarta EE 10 instead of javax)
- New 8.3 configuration page APIs

Files to migrate:
- All `.java.disabled` files in `git-gateway/src/main/java/com/axone_io/ignition/git/web/`

## Configuration Without Web UI

Since the web UI is disabled, use these alternatives:

### Option 1: YAML Commissioning (Recommended)
The module already supports YAML configuration via `GitCommissioningUtils`.

Place a `git.yaml` file in the `gw-init` directory:
```yaml
- repo_uri: https://github.com/user/repo.git
  repo_branch: main
  ignition_projectName: MyProject
  ignition_userName: admin
  ignition_inheritable: false
  ignition_parentName: null
  user_name: git-username
  user_email: dev@example.com
  user_password: password
  commissioning_importThemes: true
  commissioning_importTags: true
  commissioning_importImages: true
  initDefaultBranch: main
```

### Option 2: Direct Database Configuration
Insert records directly into the internal database:
```sql
-- Insert into GitProjectsConfigRecord table
INSERT INTO GitProjectsConfigRecord (ProjectName, URI)
VALUES ('MyProject', 'https://github.com/user/repo.git');

-- Insert into GitReposUsersRecord table
INSERT INTO GitReposUsersRecord (ProjectId, IgnitionUser, UserName, Email, Password)
VALUES (1, 'admin', 'gituser', 'user@example.com', 'encryptedpass');
```

### Option 3: Gateway Scripting API (Future Enhancement)
Add configuration methods to `GatewayScriptModule` that can be called from startup scripts.

## Next Steps

1. **Fix Project Management API** - Migrate to 8.3 Project API
2. **Fix Persistent Record Category** - Update or remove Category usage
3. **Fix Commissioning Config** - Resolve Lombok/Java 17 issues
4. **Implement Proper Secrets API** - Use `Secret.create()` pattern
5. **Test Core Functionality** - Verify commit/push/pull operations work
6. **Migrate Web UI** - Rewrite config pages using React/8.3 APIs

## Testing Checklist

Once compilation succeeds:
- [ ] Module installs on Ignition 8.3.1 gateway
- [ ] YAML commissioning loads configuration
- [ ] Designer can connect and initialize repo
- [ ] Git commit functionality works
- [ ] Git push/pull operations work
- [ ] Tag/image/theme export works
- [ ] RPC communication between Designer and Gateway works

## Resources

- [Ignition 8.3 Upgrade Guide](https://www.sdk-docs.inductiveautomation.com/docs/8.3/to-83-upgrade-guide/)
- [Ignition SDK Examples](https://github.com/inductiveautomation/ignition-sdk-examples)
- [Inductive Automation Forum - Module Development](https://forum.inductiveautomation.com/c/module-development)

## Notes

- This module now requires **Java 17** and will only work on **Ignition 8.3.0+**
- Configuration must be done via YAML or database until web UI is migrated
- The 8.3 SDK documentation is still being updated by Inductive Automation
- Consider posting on the IA Forum for guidance on specific API migrations
