# Quick Start: Release Pipeline

## First-Time Setup

### 1. Add Code Signing Secrets

```bash
# Generate a keystore
keytool -genkeypair \
  -alias ignition-git-module \
  -keyalg RSA -keysize 2048 -validity 3650 \
  -keystore keystore.jks

# Encode to base64
base64 -i keystore.jks -o keystore.txt
```

Add these secrets in GitHub (Settings > Secrets and variables > Actions):
- `KEYSTORE_BASE64` = contents of keystore.txt
- `KEYSTORE_ALIAS` = `ignition-git-module`
- `KEYSTORE_STOREPASS` = your keystore password
- `KEYSTORE_KEYPASS` = your key password

```bash
# Clean up local files
rm keystore.jks keystore.txt
```

### 2. Add Release PAT

Create a fine-grained Personal Access Token with `contents: write` permission, then add it as the `RELEASE_PAT` secret. This is needed so the tag push triggers the release build.

## Creating a Release

### Stable Release

1. Go to **Actions > Create Release > Run workflow**
2. Pick `bump_type`: `patch`, `minor`, or `major`
3. Click **Run workflow**

### Pre-release

1. Go to **Actions > Create Release > Run workflow**
2. Set `bump_type` to `prerelease` (no hyphen in the input value)
3. Pick `prerelease_type`: `alpha`, `beta`, or `rc`
4. Click **Run workflow**

### Promote Pre-release to Stable

1. Run **Create Release** with `bump_type: patch`
2. The pre-release suffix is stripped (e.g., `2.1.0-rc.1` becomes `2.1.0`)

### Dry Run

Check `dry_run` to preview the version change without pushing anything.

## Version Examples

| Current | Bump | Pre-release | Result |
|---------|------|-------------|--------|
| `2.0.0` | patch | - | `2.0.1` |
| `2.0.0` | minor | - | `2.1.0` |
| `2.0.0` | prerelease | beta | `2.1.0-beta.1` |
| `2.1.0-beta.1` | prerelease | beta | `2.1.0-beta.2` |
| `2.1.0-beta.2` | prerelease | rc | `2.1.0-rc.1` |
| `2.1.0-rc.1` | patch | - | `2.1.0` |

## What Happens

1. **Create Release** bumps versions in pom.xml + package.json, commits, tags, pushes
2. **Release** (triggered by the tag) sets the POM version from the tag, builds the module, signs it, creates a GitHub Release
3. Pre-releases get the **Pre-release** badge on GitHub

> **Note:** The Release workflow derives the build version from the git tag, not the POM.
> If the POM version doesn't match the tag, it's automatically updated during the build.
> This means manual tagging (e.g., `git tag v2.1.0 && git push --tags`) also works,
> though the Create Release workflow is preferred since it keeps the POM in sync on the branch.

## Local Builds

```bash
# Unsigned (fast)
mvn clean package -DskipTests

# Signed
mvn clean package -Psign \
  -Dkeystore.path=/path/to/keystore.jks \
  -Dkeystore.alias=ignition-git-module \
  -Dkeystore.storepass=PASSWORD \
  -Dkeystore.keypass=PASSWORD

# Verify
jarsigner -verify -verbose git-build/target/*.modl
```

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Release workflow not triggered | Check `RELEASE_PAT` secret is set and not expired |
| POM version drift after manual tag | Expected — the release build auto-corrects from the tag. Run Create Release next time to keep the branch POM in sync |
| Module not signed | Check all 4 keystore secrets are configured |
| npm build fails | Ensure Node.js 18+ is available |

For complete documentation, see [../RELEASE.md](../RELEASE.md)
