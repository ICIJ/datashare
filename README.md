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

<!-- omit from toc -->
## Table of Contents

- [Main Features](#main-features)
- [Developer Guide](#developer-guide)
  - [Requirements](#requirements)
  - [Development Environment Configuration](#development-environment-configuration)
  - [Build](#build)
  - [Run Tests](#run-tests)
  - [Database Migrations](#database-migrations)
- [Frontend](#frontend)
- [Devcontainer](#devcontainer)
  - [Prerequisites](#prerequisites)
  - [Starting the Devcontainer](#starting-the-devcontainer)
  - [Build and project initialization](#build-and-project-initialization)
- [Cookbook](#cookbook)
- [License](#license)
- [About ICIJ](#about-icij)

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

* **JDK 21**
* **Apache Maven 3.8+** - primary build tool for the backend
* **GNU Make** (optional) - convenient shortcuts (run `make help` to see available targets)
* **PostgreSQL 13+** - two DBs expected: `datashare` (dev) and `test` (tests)
* **Elasticsearch 8.x** - 7.x server is still supported
* **Redis 5+** - session storage and async task orchestration

A `docker-compose.yml` is provided to start all required services:

```bash
docker compose up -d
```

### Development Environment Configuration

Datashare uses a properties file to configure service URIs for local development and testing. This allows you to run tests against services running on different hosts (e.g., in Docker containers or on localhost). To bootstrap the devenv configuration file, simply run:

```bash
make devenv
```

This creates a gitignored `datashare-devenv.properties` from the template file. The default configuration expects services to be available at these URIs:

| Service       | Property           | Default URI                                                   |
|---------------|--------------------|---------------------------------------------------------------|
| AMQP          | `amqpUri`          | `amqp://guest:guest@localhost:5673`                           |
| Elasticsearch | `elasticsearchUri` | `http://localhost:9200`                                       |
| PostgreSQL    | `postgresUri`      | `jdbc:postgresql://localhost/dstest?user=dstest&password=test`|
| Redis         | `redisUri`         | `redis://localhost:6379`                                      |
| S3 Mock       | `s3mockUri`        | `http://localhost:9090`                                       |

The properties file is loaded automatically when running tests via the `-Ddevenv.file` system property.

### Build

The project is modular. Using Make:

```bash
# Build and install all modules (runs migrations first, then jOOQ codegen)
make install

# Or build distribution JARs only
make build
```

The `install` and `build` targets automatically run database migrations before building, ensuring jOOQ sources are generated from the current schema.

**Elasticsearch setup:**

Datashare can automatically download and install Elasticsearch locally:

```bash
# Download and install Elasticsearch (done automatically during 'make dist')
make elasticsearch
```

The distribution package (`make dist`) automatically downloads Elasticsearch 8.x to `~/.local/share/datashare/elasticsearch/` if not already present. The installation script detects your platform (Linux/macOS, x86_64/aarch64) and downloads the appropriate version.

### Run Tests

Datashare has both unit and integration tests. Integration tests expect Postgres, Elasticsearch, and Redis to be reachable.

```bash
# Run the whole test suite
make test

# Or run a single module
mvn -pl datashare-api test

# Or a single test class
mvn -pl datashare-api -Dtest=org.icij.datashare.PropertiesProviderTest test
```

### Database Migrations

Datashare uses **Liquibase** for schema migrations and **jOOQ** for type-safe SQL.

```bash
# Apply pending migrations
make migrate

# Regenerate jOOQ sources from DB schema
make generate

# Reset DB and reapply all migrations (DESTRUCTIVE)
make reset-db
```

**Adding a new changeset:**

1. Create a new YAML changeset under `datashare-db/src/main/resources/liquibase/changelog/changes/`
2. Reference it in `datashare-db/src/main/resources/liquibase/changelog/db.changelog.yml`
3. Run `make migrate` locally to verify
4. Commit both the changeset and updated master file

## Frontend

The web UI is built with Vue 3 and maintained in a [separate repository](https://github.com/ICIJ/datashare-client). When building the backend, you must also build the client and copy its compiled files into the `./app` directory. The backend bundles these static assets using [FluentHTTP](https://github.com/CodeStory/fluent-http), which serves resources from `./app` (relative to the repo root). If this folder is missing or empty, only the API will be available, no UI.

The easiest way to get the frontend is to download a pre-built release:

```bash
make app
# Or a specific version of the front
make app VERSION=20.8.1 
```

This downloads the frontend release matching the backend VERSION (from `pom.xml`) and extracts it to the `app/` directory. If the matching version doesn't exist, it falls back to the latest release.

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
   (or use *View* → *Command Palette* → *Dev Containers: Reopen in Container*).

VS Code will build the container image and start the development environment.  
This step may take several minutes on first run.

### Build and project initialization

Once VS Code is connected to the devcontainer:

1. **Run all commands from the VS Code integrated terminal**, inside the container.

2. Initialize the project:
   ```bash
   make install   # Build all modules (runs migrations + jOOQ codegen)
   make test      # Run tests
   ```

3. Reload Java projects so that VS Code correctly picks up generated sources (jOOQ) and dependencies:
   - Go to *View* → *Command Palette* → *Java: Reload Projects*


## Cookbook

For practical command-line examples covering CLI mode, server mode, OAuth setup, and more, see the [Cookbook](COOKBOOK.md).

## License

Datashare is distributed under the [GNU Affero General Public License v3.0](LICENSE.txt).

## About ICIJ

The **International Consortium of Investigative Journalists (ICIJ)** is a global network of reporters and media organizations collaborating on cross‑border investigations (e.g., *Panama Papers*, *Luanda Leaks*, *Uber Files*, *Pandora Papers*). The tech team at ICIJ builds tools like Datashare to empower investigative journalism at scale, handling millions of documents securely and efficiently. We open‑sourced Datashare to empower solo reporters and small newsrooms with advanced investigative tools, enable larger organizations to audit, extend, and self‑host the platform, and foster collaboration within the investigative community to continually improve the software.

**Contact & Community**

* Issues & feature requests: [GitHub Issues](https://github.com/ICIJ/datashare/issues)
* Email: `datashare@icij.org`
* Security reports: please email us and avoid filing public issues for vulnerabilities.
