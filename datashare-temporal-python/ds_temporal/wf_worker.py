import asyncio

from temporalio.client import Client

from ds_temporal.dag_workflow import DAG_WORKFLOW, DagWorkflow
from ds_temporal.worker import AppConfig, ConnectConfig, WorkerWithDeps


async def main() -> None:
    from ds_temporal.dependencies import WORKER_APP_DEPENDENCIES

    target_host = "localhost:7233"
    connect_config = ConnectConfig(target_host=target_host)
    app_config = AppConfig(temporal_connect=connect_config)
    client = await Client.connect(connect_config.target_host)
    async_worker = WorkerWithDeps(
        client=client,
        dependencies=WORKER_APP_DEPENDENCIES,
        app_config=app_config,
        task_queue=f"{DAG_WORKFLOW}-queue",
        workflows=[DagWorkflow],
    )
    await async_worker.run()


if __name__ == "__main__":
    asyncio.run(main())
