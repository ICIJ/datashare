# Datashare Cookbook

This cookbook provides practical command-line examples for running Datashare in common configurations.

**Assumptions** (unless stated otherwise):
- User: `dev`
- Commands run from the Datashare repository root
- Example projects: `banana-papers`, `citrus-confidential`, `local-datashare`
- OAuth server available at `http://oauth:3001`

## Table of Contents

- [Help](#help)
- [CLI Mode](#cli-mode)
  - [Scan and Index](#scan-and-index)
  - [Reports](#reports)
  - [Plugins](#plugins)
  - [NLP](#nlp)
- [Local Mode](#local-mode)
- [Server Mode](#server-mode)
  - [OAuth](#oauth)
  - [Basic Auth](#basic-auth)
  - [Embedded](#embedded)
  - [Batch Search](#batch-search)
- [Maintenance](#maintenance)
  - [Elasticsearch Cleanup](#elasticsearch-cleanup)

## Help

Show all available options and their default values:

```sh
./launchBack.sh --help
```

## CLI Mode

CLI mode is used for long-running operations such as scanning, indexing, and NLP processing.

### Scan and Index

Run a full scan and index on a project. Typically used for initial ingestion or reindexing after configuration changes.

```sh
./launchBack.sh \
  --mode CLI \
  --dataDir /home/dev/Datashare/Data/ \
  --defaultProject banana-papers \
  --stages "SCAN,INDEX"
```

### Reports

Retrieve a report for a given queue. Useful for inspecting or debugging queue processing.

```sh
./launchBack.sh \
  --mode CLI \
  --dataDir /home/dev/Datashare/Data/ \
  --defaultProject cantina \
  --stages "SCANIDX" \
  --reportName <QueueName>
```

### Plugins

Install a Datashare plugin into the plugins directory:

```sh
./launchBack.sh \
  --mode CLI \
  --pluginInstall datashare-plugin-tour \
  --pluginsDir /home/dev/Datashare/Plugins
```

### NLP

Run NLP processing using CoreNLP. Adjust parallelism settings based on available CPU and memory.

```sh
./launchBack.sh \
  --mode CLI \
  --stages NLP \
  --nlpp CORENLP \
  --nlpParallelism 2 \
  --parallelism 2 \
  --parserParallelism 2 \
  --dataDir /vault/citrus-confidential \
  --defaultProject citrus-confidential
```

## Local Mode

Start Datashare in LOCAL mode (the default) with minimal configuration. This creates a single-user instance.

```sh
./launchBack.sh \
  --dataDir /home/dev/Datashare/Data/
```

## Server Mode

Server mode is designed for multi-user deployments with authentication.

### OAuth

Standard server mode using OAuth (e.g., Keycloak). This is the recommended setup for multi-user deployments.

**Server mode with plugins:**

```sh
./launchBack.sh \
  --mode SERVER \
  --dataDir /home/dev/Datashare/Data/ \
  --oauthClientId datashareuidforseed \
  --oauthClientSecret datasharesecretforseed \
  --pluginDir /home/dev/Datashare/Plugins
```

**Server mode with OAuth and PostgreSQL:**

```sh
./launchBack.sh \
  --mode SERVER \
  --dataDir /home/dev/Datashare/Data/ \
  --dataSourceUrl "jdbc:postgresql://postgres/datashare?user=dstest&password=test" \
  --oauthClientId datashareuidforseed \
  --oauthClientSecret datasharesecretforseed
```

**Full OAuth configuration with local OAuth server and SQLite:**

```sh
./launchBack.sh \
  --mode SERVER \
  --dataDir /home/dev/Datashare/Data/ \
  --defaultProject local-datashare \
  --dataSourceUrl "jdbc:sqlite:file:$HOME/datashare.db" \
  --cors "*" \
  --oauthClientId datashareuidforseed \
  --oauthClientSecret datasharesecretforseed \
  --oauthAuthorizeUrl http://oauth:3001/oauth/authorize \
  --oauthTokenUrl http://oauth:3001/oauth/token \
  --oauthApiUrl http://oauth:3001/api/v1/me.json \
  --oauthCallbackPath /auth/callback \
  --busType MEMORY \
  --queueType MEMORY \
  --sessionStoreType MEMORY
```

**Simplified OAuth example with explicit endpoints:**

```sh
./launchBack.sh \
  --mode SERVER \
  --dataDir /home/dev/Datashare/Data/ \
  --defaultProject local-datashare \
  --oauthClientId datashareuidforseed \
  --oauthClientSecret datasharesecretforseed \
  --oauthAuthorizeUrl http://oauth:3001/oauth/authorize \
  --oauthTokenUrl http://oauth:3001/oauth/token \
  --oauthApiUrl http://oauth:3001/api/v1/me.json
```

### Basic Auth

Basic authentication is intended for testing or constrained environments.

**Basic Auth with Redis:**

See: [Basic with Redis documentation](https://icij.gitbook.io/datashare/server-mode/authentication-providers/basic-with-redis)

```sh
./launchBack.sh \
  --mode SERVER \
  --authFilter org.icij.datashare.session.BasicAuthAdaptorFilter \
  --redisAddress redis://redis:6379
```

**Basic Auth with PostgreSQL:**

See: [Basic with Database documentation](https://icij.gitbook.io/datashare/server-mode/authentication-providers/basic-with-a-database)

```sh
./launchBack.sh \
  --mode SERVER \
  --authFilter org.icij.datashare.session.BasicAuthAdaptorFilter \
  --authUsersProvider org.icij.datashare.session.UsersInDb \
  --dataSourceUrl "jdbc:postgresql://postgres/datashare?user=dstest&password=test"
```

### Embedded

Embedded mode targets low-resource environments (e.g., Raspberry Pi). All services run on the same host.

> **Warning:** This configuration has not been tested since 2020.

```sh
./launchBack.sh \
  --mode EMBEDDED \
  --dataDir /home/pi/data \
  --dataSourceUrl jdbc:sqlite:/home/pi/dist/datashare.sqlite \
  --elasticsearchAddress http://localhost:9200 \
  --elasticsearchDataPath /home/pi/es \
  --redisAddress redis://localhost:6379 \
  --messageBusAddress localhost \
  --tcpListenPort 80
```

### Batch Search

Batch Search runs as a dedicated daemon consuming extraction queues. It is typically started alongside a standard server instance.

**Server process:**

```sh
./launchBack.sh \
  --mode SERVER \
  --dataDir /home/dev/Datashare/Data/ \
  --dataSourceUrl "jdbc:postgresql://postgres/datashare?user=dstest&password=test" \
  --oauthClientId datashareuidforseed \
  --oauthClientSecret datasharesecretforseed
```

**Batch search daemon (separate terminal):**

```sh
JDWP_TRANSPORT_PORT=8001 ./launchBack.sh \
  --mode BATCH_SEARCH \
  --dataDir /home/dev/Datashare/Data/ \
  --dataSourceUrl "jdbc:postgresql://postgres/datashare?user=dstest&password=test" \
  --batchQueueType org.icij.datashare.extract.RedisBlockingQueue
```

## Maintenance

> **Caution:** Maintenance commands are destructive and should be used with care.

### Elasticsearch Cleanup

Delete the Elasticsearch index for a project:

```sh
curl -X DELETE http://localhost:9200/citrus-confidential
```
