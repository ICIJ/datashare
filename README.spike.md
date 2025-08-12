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

## Run the worker and create a task

After building the branch, start the worker:
```console
JDWP_TRANSPORT_PORT=5005 ./launchBack.sh --mode TASK_WORKER --batchQueueType CONDUCTOR  --conductorAddress http://localhost:9080  --dataSourceUrl "jdbc:postgresql://postgres:5432/?user=admin&password=admin"
```

And create a `SCAN_INDEX_NLP` workflow:
```console
./launchBack.sh --mode CLI --batchQueueType CONDUCTOR --messageBusAddress amqp://guest:guest@localhost:5672 --stage SCAN_INDEX_NLP --conductorAddress http://localhost:9080  --nlpPipeline SPACY --dataSourceUrl "jdbc:postgresql://postgres:5432/?user=admin&password=admin" --dataDir <myDataDir> --nlpp CORENLP
```

You can monitor the progress at [http://localhost:8127/](http://localhost:8127/).