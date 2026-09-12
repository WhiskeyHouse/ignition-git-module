# Install the Git module

The maintained v2 release line requires Ignition 8.3.1+ and Java 17+. The old v1 release line for Ignition 8.1 is end of life.

Download the `.modl` asset for your selected version from [WhiskeyHouse releases](https://github.com/WhiskeyHouse/ignition-git-module/releases). Read its release notes, then install the module through the development gateway's module management page. Reopen Designer after installation.

The module adds Git configuration pages to the gateway and Git operations to Designer. Continue with [linking a first project](quickstart.md).

## Build from source

Use JDK 17+, Maven, and Node.js 18+:

```sh
git clone https://github.com/WhiskeyHouse/ignition-git-module.git
cd ignition-git-module
mvn clean package
```

The development build produces `git-build/target/Git-unsigned.modl`. It is unsigned; follow the gateway's module installation requirements for development builds.

See the [release documentation](https://github.com/WhiskeyHouse/ignition-git-module/blob/main/RELEASE.md) for signing and release procedures.
