<p align="center">
  <a href="https://datashare.icij.org/">
    <img src="https://datashare.icij.org/android-chrome-512x512.png" width="158px">
  </a>
</p>

<h3 align="center">Datashare</h3>

<div align="center">
<p>A self-hosted search engine to find stories in any files.</p>
  
| | Status |
| --: | :-- |
| **Download** | [![Download](https://img.shields.io/badge/datashare.icij.org-FFF?style=flat)](https://datashare.icij.org)|
| **CI checks** | [![CircleCI](https://img.shields.io/circleci/build/gh/ICIJ/datashare.svg?style=flat)](https://circleci.com/gh/ICIJ/datashare) |
| **Translations** | [![Crowdin](https://badges.crowdin.net/datashare/localized.svg)](https://crowdin.com/project/datashare) |
| **Latest version** | [![Latest version](https://img.shields.io/github/v/tag/icij/datashare?style=flat)](https://github.com/ICIJ/datashare/releases/latest) |
| **Release date** | [![Release date](https://img.shields.io/github/release-date/icij/datashare?style=flat)](https://github.com/ICIJ/datashare/releases/latest) |
| **Open issues** | [![Open issues](https://img.shields.io/github/issues/icij/datashare?style=flat&color=success)](https://github.com/ICIJ/datashare/issues/) |
| **Documentation** | [![User Guide](https://img.shields.io/badge/User%20Guide-193D87?style=flat)](https://icij.gitbook.io/datashare/developers/frontend/api) [![Storybook](https://img.shields.io/badge/Storybook-FA4070?style=flat)](https://icij.github.io/datashare-client/) |

</div>

# Datashare

**Datashare** is an open‑source, self‑hosted document search and analysis platform built by the International Consortium of Investigative Journalists (ICIJ). It ingests heterogeneous data (PDFs, emails, spreadsheets, images, archives, etc.), extracts text (including via OCR), enriches it with metadata and named entities, and exposes everything through a powerful search UI and REST API. Because Datashare runs on your own machines, you keep full control over sensitive material—no external cloud services required.

> 📣 **Help us improve Datashare!** What do you think about the new design of Datashare? Tell us your feedback through our [survey](https://forms.gle/85faVA7VVoWkohuH7), it will directly influences our roadmap, and lets you opt‑in for early previews/beta testing.

## Table of Contents

* [Main Features](#main-features)
* [Developer Guide](#developer-guide)
  * [Requirements](#requirements)
  * [Build](#build)
  * [Run Tests](#run-tests)
  * [Database Migrations](#database-migrations)
* [Frontend](#frontend)
* [License](#license)
* [About ICIJ](#about-icij)

## Main Features

* 🔍 **Full‑text search**: Index & query PDFs, emails, office docs, images, archives, and more.
* 🖼️ **OCR on scans & images**: Turn visual text into searchable text.
* 🧠 **Named‑entity extraction**: Auto-detect people, orgs, locations, emails, etc.
* ⭐ **Stars & tags**: Mark and organize key documents.
* 🧰 **Advanced filters & operators**: Combine facets with boolean, wildcard, and fuzzy queries.
* 🤝 **Team/server mode**: Multi-user deployment with shared tags and recommendations.
* 🔌 **Plugin architecture**: Extend Datashare with custom modules.

## Developer Guide

This section explains how to set up a development environment, build the project, run tests, and manage database migrations. It assumes you are comfortable with Java/Maven projects and basic service orchestration.

### Requirements

**Languages & tooling**

* **JDK 17**
* **Apache Maven 3.8+**: primary build tool for the backend
* **GNU Make** (optional but recommended): convenient shortcuts (`make dist`, `make update-db`, etc.)

**Services**

Those services must be running to have a complete developer environement. You might want 

* **PostgreSQL 13+**
  * Available on host `postgres:5432`
  * Two DBs expected by default: `datashare` (dev) and `test` (tests)
  * A role with privileges, e.g. user: `test`, password: `test`
* **Elasticsearch 7.x** 
  * Available on host `elasticsearch:9200`
  * 8.x is not officially supported 
* **Redis 5+**
  * Available on host `redis:6379`
  * Used to store session and orchestrate async tasks.

### Build

The project is modular. Typical steps:

```bash
# 1. Validate the build and resolve deps
mvn validate

# 2. Build shared testing utilities (some modules depend on these)
mvn -pl commons-test -am install

# 3. Apply DB migrations so your dev DB schema matches the code
mvn -pl datashare-db liquibase:update

# 4. Build everything (excluding tests)
mvn package -Dmaven.test.skip=true
```

### Run Tests

Datashare has both unit and integration tests. Integration tests expect Postgres, Elasticsearch, and Redis to be reachable.

```bash
# Run the whole test suite
mvn test

# Or run a single module
mvn -pl datashare-api test

# Or a single test class
mvn -pl datashare-api -Dtest=org.icij.datashare.PropertiesProviderTest test
```

### Database Migrations

Datashare uses **Liquibase** to version and apply schema changes.

**Apply latest migrations:**

```bash
make update-db
```

**Start from scratch (danger: drops data):**

```bash
make reset-db
```

**Adding a new changeset:**

1. Create a new XML/YAML changeset under `datashare-db/src/main/resources/db/changelog/`
2. Reference it in the master changelog file
3. Run `make update-db` locally to verify
4. Commit both the changeset and updated master file

## Frontend

The web UI is built with Vue 3 and maintained in a [separate repository](https://github.com/ICIJ/datashare-client). When building the backend, you must also build the client and copy its compiled files into the `./app` directory. The backend bundles these static assets using [FluentHTTP](https://github.com/CodeStory/fluent-http), which serves resources from `./app` (relative to the repo root). If this folder is missing or empty, only the API will be available, no UI.

### Prerequisites for Frontend Dev

* **Node.js 20.19+**
* **Yarn 1**

### Build workflow

1. **Clone & enter the client repo**

   ```bash
   git clone https://github.com/ICIJ/datashare-client.git
   cd datashare-client
   ```
2. **Install and build**

   ```bash
   yarn
   yarn build
   ```

   The build outputs a production bundle into `dist/`.
3. **Copy (or symlink) into backend**

   ```bash
   rm -rf ../datashare/app
   mkdir -p ../datashare/app
   cp -r dist/* ../datashare/app/
   ```

## Devcontainer

Datashare can also be developed using a **VS Code Devcontainer**, which provides a reproducible development environment with all required dependencies (JDK, Maven, PostgreSQL, Elasticsearch, Redis, etc.) running in Docker.

This approach helps avoid local environment inconsistencies and ensures a setup closer to CI and production-like conditions.

### Prerequisites

* **Docker** (with Docker Compose support)
* **Visual Studio Code**
* **VS Code Dev Containers extension** (`ms-vscode-remote.remote-containers`)

### Starting the Devcontainer

1. Clone the Datashare repository if not already done.
2. Open the repository root in **VS Code**.
3. When prompted, select **“Reopen in Container”**  
   (or use *Command Palette* → `Dev Containers: Reopen in Container`).

VS Code will build the container image and start the development environment.  
This step may take several minutes on first run.

### Build and project initialization

Once VS Code is connected to the devcontainer:

1. **Run all Maven commands from the VS Code integrated terminal**, inside the container.  
   This ensures builds run with the correct Java version, tools, and permissions.

2. Follow the steps described in the [Build](#build) section to initialize the project:
   - validate the build,
   - build shared modules,
   - apply database migrations,
   - package the project as needed.

3. After the build completes, reload Java projects so that VS Code correctly picks up:
   - generated sources (e.g. jOOQ),
   - updated dependencies,
   - Maven module configuration.
4. Reload Java projects so that VS Code correctly picks up generated sources and dependencies:
   - Go to `View` → *Command Palette* (Ctrl+Shift+A) → `Java: Reload Projects`
This reload step is important, especially:
   - after the first container startup,
   - after running Maven builds that generate sources,
   - or after modifying Maven configuration.


## License

Datashare is distributed under the [GNU Affero General Public License v3.0](LICENSE.txt).

## About ICIJ

The **International Consortium of Investigative Journalists (ICIJ)** is a global network of reporters and media organizations collaborating on cross‑border investigations (e.g., *Panama Papers*, *Luanda Leaks*, *Uber Files*, *Pandora Papers*). The tech team at ICIJ builds tools like Datashare to empower investigative journalism at scale, handling millions of documents securely and efficiently. We open‑sourced Datashare to empower solo reporters and small newsrooms with advanced investigative tools, enable larger organizations to audit, extend, and self‑host the platform, and foster collaboration within the investigative community to continually improve the software.

**Contact & Community**

* Issues & feature requests: [GitHub Issues](https://github.com/ICIJ/datashare/issues)
* Email: `datashare@icij.org`
* Security reports: please email us and avoid filing public issues for vulnerabilities.
