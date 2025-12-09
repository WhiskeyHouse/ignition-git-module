# Ignition 8.3 Migration - Breaking Changes

## ⚠️ IMPORTANT: Breaking Changes - Requires Ignition 8.3.1+

This PR migrates the Ignition Git Module from version 1.0.3 (Ignition 8.1.x compatible) to version 2.0.0 (Ignition 8.3.1+ required).

**This version is NOT backwards compatible with Ignition 8.1.x.**

---

## Version Information

- **Previous Version**: v1.0.3 (Ignition 8.1.x compatible)
- **New Version**: v2.0.0 (Ignition 8.3.1+ only)
- **Last 8.1.x Compatible Release**: [v1.0.3-ignition-8.1](https://github.com/WhiskeyHouse/ignition-git-module/releases/tag/v1.0.3-ignition-8.1)

---

## Breaking Changes Summary

### 1. **Protobuf RPC Serialization**
- **Changed From**: Java serialization
- **Changed To**: Protobuf serialization (`ProtoRpcSerializer.DEFAULT_INSTANCE`)
- **Impact**: Faster, lighter, more secure communication between Gateway and Designer/Client
- **Why**: Ignition 8.3+ requires Protobuf for RPC; Java serialization is deprecated

### 2. **RPC Method Signature Changes**
- **Changed**: `getUncommitedChanges()` return type
  - **Before**: `Dataset` (caused serialization issues with Protobuf)
  - **After**: `List<UncommittedChange>` (strongly-typed DTO)
- **Changed**: `commit()` parameter type
  - **Before**: `List<String> changes` (not Protobuf-compatible)
  - **After**: `String[] changes` (arrays work with Protobuf)
- **Why**: Following Ignition SDK best practices - "avoid sending datasets directly over RPC"

### 3. **New DTO Classes**
- **Added**: `UncommittedChange` data transfer object for type-safe RPC
- **Benefits**: Compile-time type safety, cleaner serialization, better maintainability

### 4. **Web UI Framework Migration**
- **Changed From**: Legacy servlet-based Gateway config pages
- **Changed To**: React-based SystemJsModule config pages (Ignition 8.3 pattern)
- **Files Updated**:
  - `GitRoutes.java` - New RouteGroup pattern for REST API
  - `GitProjectsConfig.tsx` - React component for Projects config
  - `GitUsersConfig.tsx` - React component for Users config
- **Why**: Ignition 8.3 standardized on React for web UIs

### 5. **Module Hook Updates**
- **Changed**: `GatewayHook.getRpcImplementation()`
  - Now returns `GatewayRpcImplementation.of(ProtoRpcSerializer.DEFAULT_INSTANCE, scriptModule)`
- **Changed**: Web resource mounting
  - Now uses `getMountedResourceFolder()` and `SystemJsModule`
- **Changed**: Route registration
  - Now uses `mountRouteHandlers(RouteGroup)` instead of deprecated methods

### 6. **Java Version Requirement**
- **Minimum Version**: Java 17 (was Java 11)
- **Why**: Ignition 8.3.1 requires Java 17+

---

## Technical Implementation Details

### Gateway Changes
- `GatewayHook.java`: Updated to 8.3 RPC and web patterns
- `GatewayScriptModule.java`:
  - Now implements `GitScriptInterface` explicitly (required for RPC discovery)
  - Changed `getUncommitedChangesImpl()` to return `List<UncommittedChange>`
  - Added comprehensive error handling
- `GitRoutes.java`: Complete CRUD implementation for projects and users

### Designer Changes
- `DesignerHook.java`:
  - Updated RPC initialization to use `context.getGatewayInterface().getRpcInterface()`
  - Added error handling for `setupLocalRepo()` and `isRegisteredUser()`
  - Module now loads gracefully even without Git configuration
- `GitActionManager.java`: Updated to work with `List<UncommittedChange>` instead of Dataset

### Common Changes
- `GitScriptInterface.java`: Updated method signatures (List→Array, Dataset→List)
- `AbstractScriptModule.java`: Updated abstract method signatures
- `UncommittedChange.java`: New DTO class for RPC serialization

### Build Configuration
- `pom.xml`: Updated Ignition SDK dependencies from 8.1.x to 8.3.1

---

## Testing Performed

- ✅ Gateway Web UI: Projects and Users configuration pages load and function correctly
- ✅ Designer Module Loading: Module loads without errors
- ✅ Commit Button: Opens dialog and displays uncommitted changes
- ✅ Git Operations: Commit, push, pull operations work correctly
- ✅ Error Handling: Graceful degradation when Git is not configured

---

## Migration Path for Users

### For Ignition 8.1.x Users
**Stay on v1.0.3-ignition-8.1**: [Download here](https://github.com/WhiskeyHouse/ignition-git-module/releases/tag/v1.0.3-ignition-8.1)

This tagged release will remain available indefinitely for users who cannot upgrade to Ignition 8.3.

### For Ignition 8.3+ Users
**Upgrade to v2.0.0+**: Use this version or later

**Upgrade Steps**:
1. Ensure Ignition is version 8.3.1 or higher
2. Uninstall old Git module (v1.x)
3. Install new Git module (v2.0.0+)
4. Reconfigure projects and users in Gateway config pages
5. Test Git operations in Designer

**Note**: Configuration should be preserved as database schema remains compatible.

---

## Documentation Updates

- ✅ README.md: Added version compatibility matrix
- ✅ README.md: Updated Java prerequisite to JDK 17+
- ✅ README.md: Added warning about breaking changes
- ✅ Tag v1.0.3-ignition-8.1: Created for last 8.1.x compatible version

---

## Related Issues

- Fixes serialization errors with Dataset objects over Protobuf RPC
- Resolves "No RPC interfaces found" error in Designer
- Updates to Ignition 8.3 best practices

---

## Checklist

- [x] Breaking changes clearly documented
- [x] Version bumped from 1.0.3 to 2.0.0 (major version)
- [x] Last 8.1.x version tagged (v1.0.3-ignition-8.1)
- [x] README updated with compatibility information
- [x] All RPC methods tested with Protobuf serialization
- [x] Gateway web UI tested
- [x] Designer integration tested
- [x] Error handling implemented

---

## References

- [Ignition 8.1 to 8.3 Upgrade Guide](https://docs.inductiveautomation.com/docs/8.3/getting-started/installing-and-upgrading/ignition-8-upgrade-guide/81to83-upgrade-guide)
- [Ignition SDK Programmer's Guide - RPC](https://sdk-docs.inductiveautomation.com/docs/programming-for-the-designer/designer-to-gateway-communication-rpc/)
- [ModuleRPCFactory to ProtoBuf Forum Discussion](https://forum.inductiveautomation.com/t/modulerpcfactory-to-protobuf/109936)
