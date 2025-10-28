# Ignition Git Module 

[![License](https://img.shields.io/badge/license-Beerware-green.svg)](LICENSE.md)

Integrated git client in free Ignition module.

## Presentation

The Git module is an Ignition module embedding a Git client to make its integration easier into Ignition project development.<br/>
It permits to manage project resources throughout the development process in the Ignition designer. <br/>
Exporting gateway configuration is simplified or even automated.

## Version Compatibility

| Module Version | Ignition Version | Status | Download |
|---------------|------------------|---------|----------|
| v1.x (≤1.0.3) | 8.1.x | ⚠️ End of Life | [v1.0.3-ignition-8.1](https://github.com/WhiskeyHouse/ignition-git-module/releases/tag/v1.0.3-ignition-8.1) |
| v2.x (≥2.0.0) | 8.3.1+ | ✅ Active Development | Build from source |

### ⚠️ Breaking Changes in v2.0.0

**Version 2.0.0 and higher require Ignition 8.3.1 or later** and are NOT backwards compatible with 8.1.x.

**Major Changes:**
- Migrated from Java serialization to **Protobuf RPC** for improved performance and security
- Updated to Ignition 8.3 **React-based web framework** for Gateway configuration pages
- Changed RPC method signatures (Dataset → strongly-typed DTOs for better serialization)
- New module hook patterns following Ignition 8.3 standards
- Requires **Java 17+** (Ignition 8.3 requirement)

**If you're using Ignition 8.1.x**, please use the [v1.0.3-ignition-8.1 release](https://github.com/WhiskeyHouse/ignition-git-module/releases/tag/v1.0.3-ignition-8.1).

## Features

- Link an Ignition project with a remote repo, (Gateway Webpage)
- Link an Ignition user to a git project, with ssh or user/password authentication, (Gateway Webpage)
- Commit resources, (Designer, on project saved or from git toolbar)
- Push & Pull resources, (Designer, from git toolbar)
- Export of the gateway configuration. Tags, images, theme... (Designer, from git toolbar)
- Commit popup :<br/>
![Commit Popup](./img/CommitPopup.png)
- Toolbar :<br/>
![Git Toolbar](./img/GitToolbar.png)
- Status Bar :<br/>
![Git Toolbar](./img/GitStatusBar.png)
- Commissioning configuration file for easy deployment.<br/>

## Module Documentation
You can find the documentation of the module [HERE](https://www.axone-io.com/Files/Modules/GIT/1.0.2/doc/index.html), depending on its version.

You will also find a download link to the signed version of the module.

## Installation for development
### Prerequisites

Before installing and running this project on your local machine, make sure you have installed the following :

- Java (JDK >= 17) - Required for Ignition 8.3+ compatibility
- Maven
- Java IDE (I recommend [Intellij](https://www.jetbrains.com/idea/download/))

If you are using Intellij, Maven is already integrated in the IDE and you can easily download the right Java SDK from your project settings.

### Installation Instructions

To install and run this project on your local machine, follow these steps :

1. Clone the repo to your local machine: `git clone https://github.com/your-username/your-project.git`.
2. Open the project in your preferred IDE.
3. Build the project using Maven by running the following command: mvn clean package.
4. Install the module on your gateway.

That's it ! You're ready to start working with the project on your local machine.

## Roadmap

- Branch management,
- Project options for select which resources export on ExportGatewayConfig :
  - Tags, which tag provider, which folder…
- Timestamp changes in commit popup,
- Status page :
  - List commit,
  - Repos state.
- SideDesignerBar for commit management like VisualStudioCode,
- Find a way to find out who deleted the resources,
- Vision project management :
  - Auto export bin file to xml.
- Make it impossible to create the same ignition user twice for the same project.

## Contributing

We're thrilled that you want to contribute to this project !<br/>
Here are a few steps to get started :
- Fork the repo and clone your fork to your local machine.
- Create a branch for your feature : git checkout -b feature/describe-your-feature.
- Make your changes or add the new feature.
- Commit your changes, clearly explaining what you did : git commit -m "Added a new feature: describe your feature".
- Push your changes to your fork : git push origin feature/describe-your-feature.
- Open a pull request, explaining the changes you made and why they should be included in the project.

We'll review your contribution as soon as possible and provide feedback.<br/>
Thanks for participating !

## Contact

Enzo Sagnelonge - e.sagnelonge@axone-io.com

AXONE-IO - contact@axone-io.com - https://www.axone-io.com/

## License

This project is licensed by Beerware. Please see the LICENSE.md file for more information.

# YAML Automated Commissioning
Patrick Mannion - Whiskey House of Kentucky

We've forked the repo and provides a few bug fixes in addition to support multi-project import and inheritance in
automated commissioning that can be done with this module via Docker Compose (see the [documentation](https://www.axone-io.com/Files/Modules/GIT/1.0.2/doc/index.html)). 

Now, a YAML file can be provided to the `gw-init` dir in the docker compose example:
```yaml
- repo_uri: https://github.com/exampleUser/my-repo-global.git
  repo_branch: development
  ignition_projectName: Global # My base project name to be inherited by child project
  ignition_userName: admin
  ignition_inheritable: true
  ignition_parentName: null
  user_name: my-github-username
  user_email: cooldev@myorg.com
  user_password: abc123
  commissioning_importThemes: true
  commissioning_importTags: true
  commissioning_importImages: true
  initDefaultBranch: main
- repo_uri: https://github.com/exampleUser/my-repo.git
  repo_branch: development
  ignition_projectName: childProject
  ignition_userName: admin
  ignition_inheritable: false
  ignition_parentName: Global
  user_name:  my-github-username
  user_email: cooldev@myorg.com
  user_password: abc123
  commissioning_importThemes: true
  commissioning_importTags: true
  commissioning_importImages: true
```
You can see a sample usage of this in the Docker example in this repo. If you want to grab a .modl from this build
without having to launch an IDE, you may download it (signed) [here](https://whkdev01storage.blob.core.windows.net/plugpackages/Git-signed.modl)