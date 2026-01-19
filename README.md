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

- [Table of Contents](#table-of-contents)
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

**Languages & tooling**

* **JDK 17**
* **Apache Maven 3.8+**: primary build tool for the backend
* **GNU Make** (optional but recommended): convenient shortcuts (run `make help` to see available targets)

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

### Development Environment Configuration

Datashare uses a properties file to configure service URIs for local development and testing. This allows you to run tests against services running on different hosts (e.g., in Docker containers or on localhost).

**Setup:**

```bash
make devenv
```

This creates a gitignored `datashare-devenv.properties` from the template file. The default configuration expects services to be available at these URIs:

| Service       | Property           | Default URI                                              |
|---------------|--------------------|----------------------------------------------------------|
| AMQP          | `amqpUri`          | `amqp://guest:guest@amqp`                                |
| Elasticsearch | `elasticsearchUri` | `http://elasticsearch:9200`                              |
| PostgreSQL    | `postgresUri`      | `jdbc:postgresql://postgres/dstest?user=dstest&password=test` |
| Redis         | `redisUri`         | `redis://redis:6379`                                     |
| S3 Mock       | `s3mockUri`        | `http://s3mock:9090`                                     |

The properties file is loaded automatically when running tests via the `-Ddevenv.file` system property.

### Build

The project is modular. Using Make:

```bash
# Setup development environment
make devenv

# Apply database migrations
make migrate

# Build distribution JARs
make build

# Or do everything with Maven directly
mvn clean install -DskipTests
```

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

1. Create a new XML/YAML changeset under `datashare-db/src/main/resources/db/changelog/`
2. Reference it in the master changelog file
3. Run `make migrate` locally to verify
4. Commit both the changeset and updated master file

## Frontend

The web UI is built with Vue 3 and maintained in a [separate repository](https://github.com/ICIJ/datashare-client). When building the backend, you must also build the client and copy its compiled files into the `./app` directory. The backend bundles these static assets using [FluentHTTP](https://github.com/CodeStory/fluent-http), which serves resources from `./app` (relative to the repo root). If this folder is missing or empty, only the API will be available, no UI.

The easiest way to get the frontend is to download a pre-built release:

```bash
make app
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
   make devenv    # Setup environment
   make migrate   # Apply database migrations
   make build     # Build JARs
   make test      # Run tests
   ```

3. Reload Java projects so that VS Code correctly picks up generated sources (jOOQ) and dependencies:
   - Go to *View* → *Command Palette* → *Java: Reload Projects*


## License

Datashare is distributed under the [GNU Affero General Public License v3.0](LICENSE.txt).

## About ICIJ

The **International Consortium of Investigative Journalists (ICIJ)** is a global network of reporters and media organizations collaborating on cross‑border investigations (e.g., *Panama Papers*, *Luanda Leaks*, *Uber Files*, *Pandora Papers*). The tech team at ICIJ builds tools like Datashare to empower investigative journalism at scale, handling millions of documents securely and efficiently. We open‑sourced Datashare to empower solo reporters and small newsrooms with advanced investigative tools, enable larger organizations to audit, extend, and self‑host the platform, and foster collaboration within the investigative community to continually improve the software.

**Contact & Community**

* Issues & feature requests: [GitHub Issues](https://github.com/ICIJ/datashare/issues)
* Email: `datashare@icij.org`
* Security reports: please email us and avoid filing public issues for vulnerabilities.
