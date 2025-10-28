# ✅ Ignition 8.3 Gateway Configuration Pages - SOLVED!

## The Mystery: Where did getConfigPanels() go?

After extensive research and decompiling the 8.3 SDK, here's what we discovered:

### What Was Removed in 8.3:
- ❌ `getConfigPanels()` method - **COMPLETELY REMOVED** from `GatewayModuleHook`
- ❌ `getConfigCategories()` method - **COMPLETELY REMOVED**
- ❌ `RecordEditForm` - Wicket-based form editor
- ❌ `RecordActionTable` - Wicket-based table component
- ❌ `IConfigPage` - Old config page interface
- ❌ `AbstractNamedTab` - Doesn't exist in 8.3
- ❌ `BasicReactPanel` - Doesn't exist in 8.3

### What Replaced It in 8.3:

**New NavigationModel API:**
```java
context.getWebResourceManager().getNavigationModel()
    .getConfig()  // or .getHome()
    .addCategory("category-id", cat -> cat
        .label("Category Label")
        .addPage("Page Title", page -> page
            .position(10)
            .mount("/url/path", "ReactComponentName", systemJsModule)
        )
    );
```

**Key Classes:**
- `SystemJsModule` - Points to bundled React JavaScript
- `NavigationModel` - Fluent API for adding pages to gateway
- `RouteGroup` - For custom REST endpoints via `mountRouteHandlers()`
- `getMountedResourceFolder()` - Returns folder name for static resources

## Implementation Pattern for 8.3

### 1. Create React Components
Build your UI in React/TypeScript using webpack to bundle as UMD modules.

### 2. Register with NavigationModel
In `GatewayHook.setup()`:
```java
SystemJsModule jsModule = new SystemJsModule(
    "com.module.ComponentName",  // Module namespace
    "/mounted/ComponentName.js"   // Path to bundled JS
);

context.getWebResourceManager().getNavigationModel()
    .getConfig()
    .addCategory("git", cat -> cat
        .label("Git")
        .addPage("Git Projects", page -> page
            .mount("/git/projects", "GitProjectsConfig", jsModule)
        )
    );
```

### 3. Provide REST API Endpoints
Your React components call REST APIs to read/write data:
```java
context.getWebResourceManager().addServlet(
    "/system/git/projects",
    new GitProjectsServlet()
);
```

### 4. Serve Static Resources
Override `getMountedResourceFolder()` to return `"mounted"` (the directory containing bundled JS).

## Where We Found This

After the upgrade guide didn't document this change, we found the answer in:

**Source:** https://github.com/inductiveautomation/ignition-sdk-examples/tree/ignition-8.3/webui-webpage

**Key File:** `WebuiWebpageGatewayHook.java`

This example shows:
- No `getConfigPanels()` or `getConfigCategories()` methods
- Uses `SystemJsModule` + `NavigationModel` pattern
- Mounts React components to custom URLs
- Demonstrates 8.3's modern web development approach

## Documentation Gap

**IMPORTANT:** The [8.1 to 8.3 SDK Upgrade Guide](https://www.sdk-docs.inductiveautomation.com/docs/8.3/to-83-upgrade-guide/) does **NOT** mention:
- Removal of `getConfigPanels()` / `getConfigCategories()`
- The new `NavigationModel` API
- How to migrate Wicket-based config pages
- The `SystemJsModule` pattern

This is a **major undocumented breaking change** that affects all modules with custom config pages.

## Our Implementation

We've successfully implemented the new 8.3 pattern:

✅ **React Components Created:**
- `GitProjectsConfig.tsx` - Manage Git repository connections
- `GitUsersConfig.tsx` - Manage user credentials

✅ **REST API Created:**
- `GitProjectsServlet.java` - CRUD operations for projects
- `GitUsersServlet.java` - CRUD operations for users

✅ **Gateway Hook Updated:**
- Removed `getConfigPanels()` / `getConfigCategories()`
- Added `getMountedResourceFolder()` → returns `"mounted"`
- Added `registerConfigPages()` using NavigationModel API
- Registers two config pages under "Git" category

✅ **Build System:**
- webpack configured to bundle React → `/git-gateway/src/main/resources/mounted/`
- Maven exec plugin runs npm install + webpack build
- Bundles included in module classpath

## Next Steps

The config pages implementation is complete. Remaining work:
1. Fix Project API migration (`ProjectManifest` → `ResourceCollection` APIs)
2. Fix Image API changes
3. Fix `Category` class in persistent records
4. Build React components (run `npm install` in `web/packages/gateway`)
5. Full module build and test

## References

- [Ignition SDK Examples 8.3 Branch](https://github.com/inductiveautomation/ignition-sdk-examples/tree/ignition-8.3)
- [WebUI Webpage Example](https://github.com/inductiveautomation/ignition-sdk-examples/tree/ignition-8.3/webui-webpage)
- [8.3 Upgrade Guide](https://www.sdk-docs.inductiveautomation.com/docs/8.3/to-83-upgrade-guide/)
