# Conductor spike readme

## Start Conductor

To start Conductor using the DS ES and Postgres, first launch the dev env docker to start these services.

Then clone the Conductor repo:
```console
git clone https://github.com/conductor-oss/conductor.git
```

add the following `docker-compose-spike.yaml` to the `docker` directory of the repo. 

To avoid re-building an image for each config change, we set the config as an Spring runtime env var below:
```yaml
services:
  conductor-server:
    environment:
      - |
        SPRING_APPLICATION_JSON={
            "spring":
            {
                "datasource":
                {
                    "url": "jdbc:postgresql://host.docker.internal:5432/postgres",
                    "username": "admin",
                    "password": "admin"
                }
            },
            "workflow":
            {
                "dyno":
                {
                    "queue":
                    {
                        "sharding":
                        {
                            "strategy": "localOnly"
                        }
                    }
                }
            },
            "conductor":
            {
                "external-payload-storage":
                {
                    "type": "postgres",
                    "postgres":
                    {
                        "conductor-url": "http://localhost:9080",
                        "url": "http://host.docker.internal:5432",
                        "username": "admin",
                        "password": "admin"
                    }
                },
                "db":
                {
                    "type": "postgres"
                },
                "queue":
                {
                    "type": "postgres"
                },
                "elasticsearch": {
                  "version": 7,
                  "url": "http://host.docker.internal:9200",
                  "indexName": "conductor",
                  "clusterHealthColor": "yellow"
                },
                "workflow-execution-lock":
                {
                    "type": "postgres"
                },
                "app":
                {
                    "workflowExecutionLockEnabled": false,
                    "lockTimeToTry": "50ms",
                    "lockLeaseTime": "10s",
                    "ownerEmailMandatory": false,
                    "systemTaskWorkerCallbackDuration": "1s",
                    "maxPostponeDurationSeconds": "10s",
                    "executorServiceMaxThreadCount": 100,
                    "systemTaskWorkerThreadCount": 40,
                    "systemTaskMaxPollCount": 40,
                    "maxTaskInputPayloadSizeThreshold": 10485760,
                    "maxTaskOutputPayloadSizeThreshold": 10485760
                },
                "indexing":
                {
                    "enabled": true 
                },
                "metrics-prometheus":
                {
                    "enabled": true
                }
            },
            "logging":
            {
                "level":
                {
                    "root": "warn",
                    "org":
                    {
                        "springframework":
                        {
                            "web": "info"
                        },
                        "hibernate": "error"
                    },
                    "com":
                    {
                        "netflix":
                        {
                            "conductor": "info"
                        }
                    }
                }
            },
            "management":
            {
                "endpoints":
                {
                    "web":
                    {
                        "exposure":
                        {
                            "include": "prometheus"
                        }
                    }
                }
            },
            "loadSample": false
        }
    image: conductor:server
    container_name: conductor-server
    extra_hosts:
      - "host.docker.internal:host-gateway"
    build:
      context: ../
      dockerfile: docker/server/Dockerfile
      args:
        YARN_OPTS: ${YARN_OPTS}
    networks:
      - internal
    ports:
      - 9080:8080
      - 8127:5000
    healthcheck:
      test: [ "CMD", "curl","-I" ,"-XGET", "http://localhost:8080/health" ]
      interval: 60s
      timeout: 30s
      retries: 12
    logging:
      driver: "json-file"
      options:
        max-size: "1k"
        max-file: "3"

networks:
  internal:

```

Then start conductor:
```console
docker compose -f docker-compose-spike.yaml up --build
```

## Spike 1: pipelined SCAN/INDEX/NLP

`<conductor-address>` is `http://localhost:9080` if you're running outside of the devenv, otherwise you have to find
the address of the docker host running conductor and use `<your-conductor-docker-host:9080>`

After building the branch, run the worker:
```console
JDWP_TRANSPORT_PORT=5005 ./launchBack.sh --mode TASK_WORKER --batchQueueType CONDUCTOR  --conductorAddress <conductor-address>  --dataSourceUrl "jdbc:postgresql://postgres:5432/?user=admin&password=admin"
```
and queue a sequential (scan/index/nlp) `SCAN_INDEX_NLP` task:
```console
./launchBack.sh --mode CLI --batchQueueType CONDUCTOR --messageBusAddress amqp://guest:guest@localhost:5672 --stage SCAN_INDEX_NLP --conductorAddress <conductor-address>  --nlpPipeline SPACY --dataSourceUrl "jdbc:postgresql://postgres:5432/?user=admin&password=admin" --dataDir <myDataDir> --nlpp CORENLP
```

You can monitor the progress at [http://localhost:8127/](http://localhost:8127/).

### Dynamically batched SCAN/INDEX/NLP
Delete and recreate index:
```
curl -X DELETE http://localhost:9200/test-project
./launchBack.sh -m CLI --createIndex test-project
```


Start the TM and create a piped batched SCAN/INDEX/NLP task.
Batches are created **and processed dynamically (1 INDEX and NLP subtask per batch)** and distributed across workers
(here a single one with 3 threads), we use
conductor to handle the batches rather than posting docs on queue:
```
 ./launchBack.sh --mode CLI --batchQueueType CONDUCTOR --messageBusAddress amqp://guest:guest@localhost:5672 --stage BATCH_SCAN_INDEX_NLP --conductorAddress <conductor-address> --dataSourceUrl "jdbc:postgresql://postgres:5432/?user=admin&password=admin" --dataDir ~/Datashare/toy_dataset --nlpp CORENLP --batchSize 5 --parallelism 3 --defaultProject local-datashare
```
