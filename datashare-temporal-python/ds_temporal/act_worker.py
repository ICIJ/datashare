import asyncio
from concurrent.futures import ThreadPoolExecutor

from temporalio.client import Client

from ds_temporal.dag_workflow import (
    DAG_WORKFLOW,
    async_activities,
    sync_activities,
)
from ds_temporal.worker import AppConfig, ConnectConfig, WorkerWithDeps


async def main() -> None:
    from ds_temporal.dependencies import WORKER_APP_DEPENDENCIES

    target_host = "localhost:7233"
    connect_config = ConnectConfig(target_host=target_host)
    app_config = AppConfig(temporal_connect=connect_config)
    client = await Client.connect(connect_config.target_host)
    # client = await Client.connect(target_host)
    # TODO: here we could group sync activities with workflow in a worker and async
    #  activities in another worker for optimized performances
    #  (see https://docs.temporal.io/develop/python/python-sdk-sync-vs-async)
    sync_worker = WorkerWithDeps(
        client=client,
        dependencies=WORKER_APP_DEPENDENCIES,
        app_config=app_config,
        task_queue=f"{DAG_WORKFLOW}-queue",
        activities=sync_activities + async_activities,
        activity_executor=ThreadPoolExecutor(),
    )
    await sync_worker.run()


if __name__ == "__main__":
    asyncio.run(main())
