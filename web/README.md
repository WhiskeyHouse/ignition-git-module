# Gateway Web UI - React Components

This directory contains the React-based gateway configuration pages for the Ignition Git Module (8.3+).

## Overview

Ignition 8.3 replaced the old Wicket-based gateway config pages with a React-based approach. This module uses React components for the configuration UI that interact with REST endpoints on the backend.

## Structure

```
web/
└── packages/
    └── gateway/
        ├── package.json           # NPM dependencies
        ├── tsconfig.json          # TypeScript configuration
        ├── webpack.config.js      # Webpack bundling configuration
        └── src/
            ├── GitProjectsConfig.tsx  # Git Projects configuration page
            ├── GitUsersConfig.tsx     # Git Users configuration page
            └── config.css             # Shared styles
```

## Build Process

The React components are automatically built during the Maven build process:

1. **npm install** - Installs React, webpack, and build dependencies
2. **webpack build** - Compiles TypeScript/React into bundled JavaScript
3. **Output** - Bundled JS files are placed in `git-gateway/src/main/resources/mounted/`

These steps are configured in `git-build/pom.xml` using the `exec-maven-plugin`.

## Configuration Pages

### Git Projects Config
- **File**: `GitProjectsConfig.tsx`
- **URL**: Gateway Config → Git → Git Projects
- **Purpose**: Manage Git repository connections for Ignition projects

Features:
- Add/Edit/Delete Git project connections
- Configure project name and repository URI
- Supports both HTTPS and SSH authentication

### Git Users Config
- **File**: `GitUsersConfig.tsx`
- **URL**: Gateway Config → Git → Git Users
- **Purpose**: Manage Git user credentials per project

Features:
- Add/Edit/Delete user credentials
- Link Ignition users to Git users
- Configure passwords (HTTPS) or SSH keys
- Set Git username and email per user

## REST API

The React components communicate with the backend via REST endpoints:

### Projects API
- `GET /system/git/projects` - List all projects
- `POST /system/git/projects` - Create project
- `PUT /system/git/projects/{id}` - Update project
- `DELETE /system/git/projects/{id}` - Delete project

### Users API
- `GET /system/git/users` - List all users
- `POST /system/git/users` - Create user
- `PUT /system/git/users/{id}` - Update user
- `DELETE /system/git/users/{id}` - Delete user

**Backend Implementation**: See `git-gateway/src/main/java/com/axone_io/ignition/git/web/api/`

## Development

### Prerequisites
- Node.js 18+ and npm
- Maven 3.x
- Java 17

### Local Development Workflow

1. **Install dependencies**:
   ```bash
   cd web/packages/gateway
   npm install
   ```

2. **Build React components**:
   ```bash
   npm run build        # Production build
   npm run build:dev    # Development build
   npm run watch        # Watch mode for development
   ```

3. **Build full module**:
   ```bash
   cd ../../../
   mvn clean package
   ```

### Making Changes

1. Edit React components in `web/packages/gateway/src/`
2. Run `npm run watch` for live recompilation
3. Rebuild and reinstall module in Ignition to test
4. Changes to REST endpoints require full Maven rebuild

## Technology Stack

- **React 18** - UI framework
- **TypeScript** - Type-safe JavaScript
- **Webpack 5** - Module bundler
- **Babel** - JavaScript/TypeScript transpiler
- **CSS** - Styling (no CSS preprocessor needed)

## Migration from Wicket

The old Wicket-based pages have been replaced:

| Old (8.1) | New (8.3) |
|-----------|-----------|
| `RecordEditForm` | React forms with REST API calls |
| `RecordActionTable` | React tables with action buttons |
| `IConfigPage` | `BasicReactPanel` mounting React components |
| Wicket form metadata | State management in React components |
| Server-side rendering | Client-side React rendering |

## Troubleshooting

### Build Fails - npm not found
- Ensure Node.js and npm are installed and in your PATH
- Maven `exec-maven-plugin` needs access to npm executable

### Webpack compilation errors
- Check TypeScript errors in React components
- Verify all imports are correct
- Run `npm run build:dev` for more verbose error messages

### Config pages don't appear
- Verify webpack output is in `git-gateway/src/main/resources/mounted/`
- Check browser console for JavaScript errors
- Ensure servlets are registered in `GatewayHook.setup()`

### REST API errors
- Check gateway logs for servlet errors
- Verify database schema is up to date
- Test API endpoints directly with curl or Postman

## References

- [Ignition 8.3 SDK Upgrade Guide](https://www.sdk-docs.inductiveautomation.com/docs/8.3/to-83-upgrade-guide/)
- [React Documentation](https://react.dev/)
- [Webpack Documentation](https://webpack.js.org/)
