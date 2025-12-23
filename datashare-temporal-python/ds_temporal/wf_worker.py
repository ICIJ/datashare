import asyncio
import os
import socket

from temporalio.client import Client

from ds_temporal.dag_workflow import DagWorkflow
from ds_temporal.multilingual_workflow import MultilingualWorkflow
from ds_temporal.worker import AppConfig, ConnectConfig, WorkerWithDeps


async def main() -> None:
    target_host = "localhost:7233"
    connect_config = ConnectConfig(target_host=target_host)
    app_config = AppConfig(temporal_connect=connect_config)
    client = await Client.connect(connect_config.target_host)
    identity = f"workflow-worker:{os.getpid()}@{socket.gethostname()}"
    async_worker = WorkerWithDeps(
        identity=identity,
        client=client,
        app_config=app_config,
        task_queue="workflows",
        workflows=[DagWorkflow, MultilingualWorkflow],
    )
    await async_worker.run()


if __name__ == "__main__":
    asyncio.run(main())
