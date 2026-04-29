# Release Pipeline Documentation

This document describes the automated release pipeline for the Ignition Git Module.

## Overview

The project uses two release-related GitHub Actions workflows:

1. **Create Release** (`create-release.yml`) - Bumps versions, commits, tags, and pushes
2. **Release** (`release.yml`) - Builds, signs, and publishes when a tag is pushed

Releases are triggered via the **Create Release** workflow dispatch in GitHub Actions. It handles version calculation, updates all version files, commits, tags, and pushes. The tag push then triggers the **Release** workflow which builds the module, signs it, and creates the GitHub Release.

## Prerequisites

### Code Signing Keystore

Ignition `.modl` files are signed using Inductive Automation's [module-signer](https://github.com/inductiveautomation/module-signer) tool (the standard `jarsigner` does **not** produce a gateway-valid signature). The Release workflow builds module-signer in CI and runs it against the unsigned `.modl`.

To produce signed releases, configure these GitHub Secrets:

1. **Generate a keystore** (if you don't have one):
   ```bash
   keytool -genkeypair \
     -alias ignition-git-module \
     -keyalg RSA \
     -keysize 2048 \
     -validity 3650 \
     -keystore keystore.jks \
     -dname "CN=Your Name, OU=Your Org, O=Your Company, L=City, ST=State, C=US"
   ```

2. **Export the certificate chain** (module-signer requires a `.p7b` chain file):
   ```bash
   keytool -export -rfc \
     -alias ignition-git-module \
     -keystore keystore.jks \
     -file chain.p7b
   ```

3. **Encode both files to base64**:
   ```bash
   base64 -i keystore.jks -o keystore.txt
   base64 -i chain.p7b   -o chain.txt
   ```

4. **Add GitHub Secrets** (Settings > Secrets and variables > Actions):
   - `KEYSTORE_BASE64` - Contents of keystore.txt
   - `KEYSTORE_CHAIN_BASE64` - Contents of chain.txt
   - `KEYSTORE_ALIAS` - Alias used when creating the keystore
   - `KEYSTORE_STOREPASS` - Keystore password
   - `KEYSTORE_KEYPASS` - Key password

5. **Delete local files**:
   ```bash
   rm keystore.jks keystore.txt chain.p7b chain.txt
   ```

### Release PAT

The **Create Release** workflow needs a Personal Access Token so that its tag push triggers the **Release** workflow (pushes using `GITHUB_TOKEN` don't trigger other workflows).

1. Create a fine-grained PAT with `contents: write` permission for this repository
2. Add it as the `RELEASE_PAT` secret in GitHub

## Creating a Release

### Standard Release (patch / minor / major)

1. Go to **Actions > Create Release > Run workflow**
2. Set `bump_type` to `patch`, `minor`, or `major`
3. Leave `dry_run` unchecked
4. Click **Run workflow**

The workflow will:
- Read the current version from pom.xml
- Calculate the new version
- Update all pom.xml files and package.json
- Commit, tag (`vX.Y.Z`), and push
- The tag push triggers the Release workflow which builds, signs, and publishes

### Pre-release

1. Go to **Actions > Create Release > Run workflow**
2. Set `bump_type` to `prerelease` (no hyphen in the input value)
3. Set `prerelease_type` to `alpha`, `beta`, or `rc`
4. Click **Run workflow**

Pre-release versions follow the pattern `X.Y.Z-typeN` (e.g., `2.1.0-beta1`).

The GitHub Release will be marked with the **Pre-release** badge.

### Promoting a Pre-release to Stable

To promote a pre-release (e.g., `2.1.0-rc2`) to a stable release:

1. Run **Create Release** with `bump_type: patch`
2. This strips the pre-release suffix: `2.1.0-rc2` becomes `2.1.0`

### Dry Run

Set `dry_run: true` to preview what the workflow would do without making any changes.

### Version Calculation Examples

| Current | Bump Type | Pre-release Type | Result |
|---------|-----------|------------------|--------|
| `2.0.0` | patch | - | `2.0.1` |
| `2.0.0` | minor | - | `2.1.0` |
| `2.0.0` | major | - | `3.0.0` |
| `2.0.0` | prerelease | beta | `2.1.0-beta1` |
| `2.1.0-beta1` | prerelease | beta | `2.1.0-beta2` |
| `2.1.0-beta2` | prerelease | rc | `2.1.0-rc1` |
| `2.1.0-rc1` | patch | - | `2.1.0` |

## Ignition Module Versioning

Per the [Ignition SDK docs](https://www.sdk-docs.inductiveautomation.com/docs/getting-started/anatomy-of-a-module/the-modulexml-file), module versions use the format `major.minor.revision[-rcX][-betaX]` (e.g., `2.1.0`, `2.1.0-rc1`, `2.1.0-beta2`).

The Release workflow passes the tag version to Maven via `-Dmodule.version`, which sets the `<moduleVersion>` in the built module.

## Build Artifacts

Each release produces one of:
- `Git-{version}-signed.modl` - Signed module (when keystore secrets are configured)
- `Git-{version}-unsigned.modl` - Unsigned module (fallback when signing secrets are missing)

Artifacts are retained for 90 days and attached to the GitHub Release.

## Local Development

### Unsigned build
```bash
mvn clean package -DskipTests
```

### Signed build (local)
Maven only produces the unsigned `.modl`. To sign locally, build [module-signer](https://github.com/inductiveautomation/module-signer) once, then run it against the maven output:
```bash
git clone https://github.com/inductiveautomation/module-signer.git /tmp/module-signer
(cd /tmp/module-signer && mvn package)

java -jar /tmp/module-signer/target/module-signer-*-jar-with-dependencies.jar \
  -keystore=/path/to/keystore.jks \
  -keystore-pwd=your-store-password \
  -alias=your-alias \
  -alias-pwd=your-key-password \
  -chain=/path/to/chain.p7b \
  -module-in=git-build/target/Git-unsigned.modl \
  -module-out=git-build/target/Git-signed.modl
```

### Verify signature
A signed `.modl` contains `signatures.properties` and `certificates.p7b` at the zip root:
```bash
unzip -l git-build/target/Git-signed.modl | grep -E "signatures.properties|certificates.p7b"
```

## Troubleshooting

### Release workflow not triggered after Create Release
The `RELEASE_PAT` secret may be missing or expired. Pushes using `GITHUB_TOKEN` don't trigger other workflows.

### POM version mismatch error
The Release workflow verifies that the POM version matches the tag. If they don't match, the Create Release workflow likely didn't run correctly. Check its logs.

### Module not signed
Verify all five keystore secrets are set: `KEYSTORE_BASE64`, `KEYSTORE_CHAIN_BASE64`, `KEYSTORE_ALIAS`, `KEYSTORE_STOREPASS`, `KEYSTORE_KEYPASS`. The `Decode signing materials` step is gated on `KEYSTORE_BASE64` only — if that one is set but the others are missing, signing will fail at the `Sign module` step instead of being skipped.

### npm build fails
Ensure Node.js 18+ is installed. The workflow sets this up automatically.

## Security Notes

- Never commit the keystore file to the repository
- Keep keystore passwords secure in GitHub Secrets
- Use a fine-grained PAT with minimal permissions for `RELEASE_PAT`
- Rotate credentials periodically
