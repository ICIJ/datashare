# Conductor spike readme

## Start Conductor

To start Conductor using the DS ES and Postgres, first launch the dev env docker to start these services.

Then clone the Conductor repo:
```console
git clone https://github.com/conductor-oss/conductor.git
```

add the following `docker-compose-spike.yaml` to the `docker` directory of the repo:
```yaml
services:
  conductor-server:
    environment:
      - CONFIG_PROP=config-spike.properties
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

add this `config-spike.properties` configuration file to the `docker/server/config` file:
```properties
# Database persistence type.
conductor.db.type=postgres
conductor.queue.type=postgres
conductor.external-payload-storage.type=postgres

# Restrict the size of task execution logs. Default is set to 10.
# conductor.app.taskExecLogSizeLimit=10

# postgres
spring.datasource.url=jdbc:postgresql://host.docker.internal:5432/postgres
spring.datasource.username=admin
spring.datasource.password=admin

# logs
logging.level.root=debug
logging.level.org.springframework.web=debug
logging.level.org.hibernate=error

# Elastic search instance indexing is enabled.
conductor.indexing.enabled=true
conductor.app.asyncIndexingEnabled=false
conductor.elasticsearch.url=http://host.docker.internal:9200
conductor.elasticsearch.indexName=conductor
conductor.elasticsearch.version=7
conductor.elasticsearch.clusterHealthColor=yellow

# Restrict the number of task log results that will be returned in the response. Default is set to 10.
# conductor.elasticsearch.taskLogResultLimit=10

# Additional modules for metrics collection exposed to Prometheus (optional)
conductor.metrics-prometheus.enabled=true
management.endpoints.web.exposure.include=prometheus

# Load sample kitchen-sink workflow
loadSample=true
```

Then start conductor:
```console
docker compose -f docker-compose-spike.yaml up --build
```

## Spike 1: pipelined SCAN/INDEX/NLP

After building the branch, run the worker:
```console
JDWP_TRANSPORT_PORT=5005 ./launchBack.sh --mode TASK_WORKER --batchQueueType CONDUCTOR  --conductorAddress http://localhost:9080  --dataSourceUrl "jdbc:postgresql://postgres:5432/?user=admin&password=admin"
```
and queue a sequential (scan/index/nlp) `SCAN_INDEX_NLP` task:
```console
./launchBack.sh --mode CLI --batchQueueType CONDUCTOR --messageBusAddress amqp://guest:guest@localhost:5672 --stage SCAN_INDEX_NLP --conductorAddress http://localhost:9080  --nlpPipeline SPACY --dataSourceUrl "jdbc:postgresql://postgres:5432/?user=admin&password=admin" --dataDir <myDataDir> --nlpp CORENLP
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
 ./launchBack.sh --mode CLI --batchQueueType CONDUCTOR --messageBusAddress amqp://guest:guest@localhost:5672 --stage BATCH_SCAN_INDEX_NLP --conductorAddress http://localhost:9080 --dataSourceUrl "jdbc:postgresql://postgres:5432/?user=admin&password=admin" --dataDir ~/Datashare/toy_dataset --nlpp CORENLP --batchSize 5 --parallelism 3 --defaultProject local-datashare
```
