import asyncio

from temporalio.client import Client
from temporalio.worker import Worker

from ds_temporal.dag_workflow import (
    DAG_WORKFLOW,
    DagWorkflow,
    async_activities,
    sync_activities,
)


async def main() -> None:
    client = await Client.connect("localhost:7233")
    # TODO: here we could group sync activities with workflow in a worker and async
    #  activities in another worker for optimized performances
    #  (see https://docs.temporal.io/develop/python/python-sdk-sync-vs-async)
    from concurrent.futures import ThreadPoolExecutor

    worker = Worker(
        client,
        task_queue=f"{DAG_WORKFLOW}-queue",
        workflows=[DagWorkflow],
        activities=sync_activities + async_activities,
        activity_executor=ThreadPoolExecutor(),
    )
    await worker.run()


if __name__ == "__main__":
    asyncio.run(main())
