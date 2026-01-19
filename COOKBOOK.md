# Datashare Cookbook

This cookbook is a practical reference for running Datashare in common configurations.

Unless stated otherwise, commands assume:
- user: `dev`
- execution from the `Datashare` repository root
- existing projects: `banana-papers`, `citrus-confidential`, `local-datashare`
- OAuth server available at `http://oauth:3001`

---

## Table of Contents

- [Options and help](#help)
- [CLI](#cli)
  - [Scan and Index](#scan-and-index)
  - [Reports](#reports)
  - [Plugins](#plugins)
  - [NLP](#nlp)
- [LOCAL](#local)
  - [Local Mode](#local-mode)
- [SERVER](#server)
  - [OAuth](#oauth)
  - [BasicAuth](#basicauth)
  - [Embedded](#embedded)
  - [Batch Search](#batch-search)
- [MAINTENANCE](#maintenance)
  - [Elasticsearch Cleanup](#elasticsearch-cleanup)

---
## Help
Shows all options and their default values
```sh
./launchBack.sh --help
```

## CLI

CLI mode (command line interface mode) is mainly used for long running operations in server mode like scan, index, NLP...


### Scan and Index

Runs a full scan and index on a project. This is typically used for initial ingestion
or reindexing after configuration changes.

```sh
./launchBack.sh \
  --mode CLI \
  --dataDir /home/dev/Datashare/Data/ \
  --defaultProject banana-papers \
  --stages "SCAN,INDEX"
```

### Reports

Retrieve a report for a given queue. This is mostly used to inspect or debug
queue processing.

```sh
./launchBack.sh \
  --mode CLI \
  --dataDir /home/dev/Datashare/Data/ \
  --defaultProject cantina \
  --stages "SCANIDX" \
  --reportName <QueueName>
```

### Plugins

Installs a Datashare plugin into the plugins directory.

```sh
./launchBack.sh \
  --mode CLI \
  --pluginInstall datashare-plugin-tour \
  --pluginsDir /home/dev/Datashare/Plugins
```

### NLP

Runs NLP processing using CoreNLP. Parallelism settings can be adjusted depending
on available CPU and memory.

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
---
## LOCAL
### Local Mode

Starts Datashare in LOCAL mode (default mode) with minimal configuration (default options). The datashare instance is a single user instance.

```sh
./launchBack.sh \
  --dataDir /home/dev/Datashare/Data/
```
---

## SERVER

Server mode is meant to be used in a multi user Datashare environment (with authentication).

### OAuth

Standard server mode using OAuth (e.g. Keycloak). This is the recommended setup
for multi-user deployments.

Server mode with plugins:

```sh
./launchBack.sh \
  --mode SERVER \
  --dataDir /home/dev/Datashare/Data/ \
  --oauthClientId datashareuidforseed \
  --oauthClientSecret datasharesecretforseed \
  --pluginDir /home/dev/Datashare/Plugins
```

Server mode with OAuth and PostgreSQL:

```sh
./launchBack.sh \
  --mode SERVER \
  --dataDir /home/dev/Datashare/Data/ \
  --dataSourceUrl "jdbc:postgresql://postgres/datashare?user=dstest&password=test" \
  --oauthClientId datashareuidforseed \
  --oauthClientSecret datasharesecretforseed
```

Full OAuth configuration using a local OAuth server and a SQLite database:

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

Simplified OAuth example with explicit endpoints:

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

### BasicAuth

Basic authentication is mostly intended for testing or constrained environments.

#### BasicAuth with Redis
See: https://icij.gitbook.io/datashare/server-mode/authentication-providers/basic-with-redis

```sh
./launchBack.sh \
  --mode SERVER \
  --authFilter org.icij.datashare.session.BasicAuthAdaptorFilter \
  --redisAddress redis://redis:6379
```

#### BasicAuth with PostgreSQL
See: https://icij.gitbook.io/datashare/server-mode/authentication-providers/basic-with-a-database

```sh
./launchBack.sh \
  --mode SERVER \
  --authFilter org.icij.datashare.session.BasicAuthAdaptorFilter \
  --authUsersProvider org.icij.datashare.session.UsersInDb \
  --dataSourceUrl "jdbc:postgresql://postgres/datashare?user=dstest&password=test"
```

### Embedded

Embedded mode targets low-resource environments (e.g. Raspberry Pi).
Configuration is explicit and everything runs on the same host.
Warning: This command has not been tested since 2020.

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

Batch Search runs as a dedicated daemon consuming extraction queues.
It is typically started alongside a standard server instance.

Server process:

```sh
./launchBack.sh \
  --mode SERVER \
  --dataDir /home/dev/Datashare/Data/ \
  --dataSourceUrl "jdbc:postgresql://postgres/datashare?user=dstest&password=test" \
  --oauthClientId datashareuidforseed \
  --oauthClientSecret datasharesecretforseed
```

Batch search daemon (separate terminal):

```sh
JDWP_TRANSPORT_PORT=8001 ./launchBack.sh \
  --mode BATCH_SEARCH \
  --dataDir /home/dev/Datashare/Data/ \
  --dataSourceUrl "jdbc:postgresql://postgres/datashare?user=dstest&password=test" \
  --batchQueueType org.icij.datashare.extract.RedisBlockingQueue
```

---

## MAINTENANCE

Maintenance commands are destructive by nature and should be used with care.

### Elasticsearch Cleanup

Deletes the Elasticsearch index for a project.

```sh
curl -X DELETE http://localhost:9200/citrus-confidential
```
