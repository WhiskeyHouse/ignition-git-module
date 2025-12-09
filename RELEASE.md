# Release Pipeline Documentation

This document describes the automated release pipeline for the Ignition Git Module.

## Overview

The project includes two GitHub Actions workflows:

1. **Continuous Integration (CI)** - Runs on PRs and pushes to main
2. **Build and Release** - Creates releases with signed modules

## Continuous Integration

The CI workflow (`ci.yml`) automatically runs on:
- Pull requests to main
- Pushes to main branch

It performs:
- Maven build verification
- Module compilation
- Artifact creation (7-day retention)

## Release Process

### Prerequisites

To create signed releases, you need to set up GitHub Secrets with your code signing keystore:

1. **Generate a Keystore** (if you don't have one):
   ```bash
   keytool -genkeypair \
     -alias ignition-git-module \
     -keyalg RSA \
     -keysize 2048 \
     -validity 3650 \
     -keystore keystore.jks \
     -dname "CN=Your Name, OU=Your Organization, O=Your Company, L=City, ST=State, C=US"
   ```

2. **Encode the Keystore to Base64**:
   ```bash
   base64 -i keystore.jks -o keystore.txt
   ```

3. **Set up GitHub Secrets**:

   Navigate to your repository on GitHub:
   Settings → Secrets and variables → Actions → New repository secret

   Add the following secrets:
   - `KEYSTORE_BASE64` - Contents of keystore.txt (base64 encoded keystore)
   - `KEYSTORE_ALIAS` - The alias used when creating the keystore (e.g., "ignition-git-module")
   - `KEYSTORE_STOREPASS` - The keystore password
   - `KEYSTORE_KEYPASS` - The key password

### Creating a Release

#### Method 1: Tag-based Release (Recommended)

1. **Update the version in pom.xml**:
   ```xml
   <version>2.1.0</version>
   ```

2. **Commit and push your changes**:
   ```bash
   git add pom.xml
   git commit -m "chore: bump version to 2.1.0"
   git push origin main
   ```

3. **Create and push a version tag**:
   ```bash
   git tag v2.1.0
   git push origin v2.1.0
   ```

4. **The workflow will automatically**:
   - Build the unsigned module
   - Build and sign the module (if secrets are configured)
   - Create a GitHub release
   - Upload both signed and unsigned .modl files
   - Generate release notes

#### Method 2: Manual Workflow Dispatch

1. Go to Actions → Build and Release → Run workflow
2. Select the branch
3. Choose release type (snapshot/release)
4. Click "Run workflow"

This creates a snapshot build without creating a GitHub release.

## Build Artifacts

After a successful release, the following artifacts are created:

- `Git-{version}-signed.modl` - Signed module (recommended for production)
- `Git-{version}-unsigned.modl` - Unsigned module (for testing)

## Local Development

### Building Unsigned Module

```bash
mvn clean package
```

The .modl file will be in `git-build/target/`

### Building Signed Module

```bash
mvn clean package -Psign \
  -Dkeystore.path=/path/to/keystore.jks \
  -Dkeystore.alias=your-alias \
  -Dkeystore.storepass=your-store-password \
  -Dkeystore.keypass=your-key-password
```

### Verifying Signature

```bash
jarsigner -verify -verbose -certs git-build/target/*.modl
```

Expected output should include:
```
jar verified.
```

## Versioning Strategy

This project follows [Semantic Versioning](https://semver.org/):

- **MAJOR** version (X.0.0): Incompatible API changes
- **MINOR** version (0.X.0): New functionality, backwards compatible
- **PATCH** version (0.0.X): Bug fixes, backwards compatible

### Version Format

- Release builds: `X.Y.Z` (e.g., 2.0.0)
- Snapshot builds: `X.Y.Z-SNAPSHOT` (e.g., 2.1.0-SNAPSHOT)

The Maven build also appends a timestamp to the module version:
```
moduleVersion: 2.0.0.2025120910
```

## Troubleshooting

### Build Fails with "npm: not found"

The workflow installs Node.js automatically. If building locally, ensure Node.js 18+ is installed.

### Signing Fails

Check that all four keystore secrets are properly set in GitHub:
- KEYSTORE_BASE64
- KEYSTORE_ALIAS
- KEYSTORE_STOREPASS
- KEYSTORE_KEYPASS

### Module Not Signed in Release

If the release workflow runs but the module isn't signed:
1. Verify all GitHub secrets are set correctly
2. Check the workflow logs for signing errors
3. Ensure the keystore is valid and not expired

### Local Build Succeeds but CI Fails

Common causes:
1. Dependencies not in public Maven repositories
2. Different Java versions (CI uses Java 17)
3. Missing Node.js dependencies

## Release Checklist

Before creating a release:

- [ ] Update version in `pom.xml`
- [ ] Update CHANGELOG or release notes if applicable
- [ ] Ensure all tests pass locally
- [ ] Ensure CI is passing on main branch
- [ ] Review and merge all pending PRs
- [ ] Create and push version tag
- [ ] Verify release artifacts are created
- [ ] Test the signed .modl file in an Ignition gateway
- [ ] Update documentation if needed

## Security Notes

- Never commit the keystore file to the repository
- Keep keystore passwords secure in GitHub Secrets
- Rotate keystore passwords periodically
- Use different keystores for development and production
- Limit access to repository secrets to trusted maintainers

## Support

For issues with the release pipeline:
1. Check GitHub Actions logs for detailed error messages
2. Review this documentation
3. Open an issue on GitHub with relevant logs
