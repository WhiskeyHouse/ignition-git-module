# Quick Start: Release Pipeline

## First-Time Setup

### 1. Generate a Keystore

```bash
keytool -genkeypair \
  -alias ignition-git-module \
  -keyalg RSA \
  -keysize 2048 \
  -validity 3650 \
  -keystore keystore.jks
```

Follow the prompts to set:
- Keystore password (keep secure!)
- Key password (keep secure!)
- Your organization details

### 2. Set GitHub Secrets

```bash
# Encode keystore to base64
base64 -i keystore.jks -o keystore.txt
```

Add these secrets in GitHub (Settings → Secrets and variables → Actions):
- `KEYSTORE_BASE64` = contents of keystore.txt
- `KEYSTORE_ALIAS` = `ignition-git-module` (or your chosen alias)
- `KEYSTORE_STOREPASS` = your keystore password
- `KEYSTORE_KEYPASS` = your key password

### 3. Delete the keystore files locally

```bash
rm keystore.jks keystore.txt
```

## Creating a Release

### Quick Method (Recommended)

```bash
# 1. Update version in pom.xml (e.g., change to 2.1.0)
# 2. Commit and push
git add pom.xml
git commit -m "chore: bump version to 2.1.0"
git push origin main

# 3. Tag and push
git tag v2.1.0
git push origin v2.1.0
```

GitHub Actions will automatically:
- ✅ Build the module
- ✅ Sign the module
- ✅ Create a GitHub release
- ✅ Upload signed and unsigned .modl files

### Manual Trigger

1. Go to GitHub → Actions → "Build and Release"
2. Click "Run workflow"
3. Select branch and release type
4. Click "Run workflow"

## Local Development Builds

### Unsigned Build (Fast)
```bash
mvn clean package
```

### Signed Build (Local Testing)
```bash
mvn clean package -Psign \
  -Dkeystore.path=/path/to/keystore.jks \
  -Dkeystore.alias=ignition-git-module \
  -Dkeystore.storepass=YOUR_PASSWORD \
  -Dkeystore.keypass=YOUR_PASSWORD
```

### Verify Signature
```bash
jarsigner -verify -verbose git-build/target/*.modl
```

## Troubleshooting

### "KEYSTORE_BASE64 secret not found"
→ Set up GitHub secrets (see step 2 above)

### "jar verify failed"
→ Check keystore passwords in GitHub secrets

### "Module not found in release"
→ Check GitHub Actions logs for build errors

## Where to Find Files

- Built module: `git-build/target/*.modl`
- GitHub releases: https://github.com/WHK01/ignition-git-module/releases
- Workflow logs: https://github.com/WHK01/ignition-git-module/actions

## Version Numbering

Follow semantic versioning:
- **Major** (X.0.0): Breaking changes
- **Minor** (0.X.0): New features
- **Patch** (0.0.X): Bug fixes

Examples:
- `2.0.0` → `2.1.0` (added new feature)
- `2.1.0` → `2.1.1` (bug fix)
- `2.1.1` → `3.0.0` (breaking change)

For complete documentation, see [../RELEASE.md](../RELEASE.md)
