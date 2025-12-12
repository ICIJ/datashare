import asyncio
import logging
import uuid

from icij_common.logging_utils import setup_loggers
from temporalio.client import Client
from temporalio.contrib.pydantic import pydantic_data_converter

from ds_temporal.dag_workflow import DAG_WORKFLOW, DagPayload

logger = logging.getLogger(__name__)


async def main() -> None:
    logger.info("connecting to temporal...")
    client = await Client.connect(
        "localhost:7233", data_converter=pydantic_data_converter
    )
    logger.info("starting workflow execution...")
    payload = DagPayload(max_tasks=20, batch_size=2)
    await client.execute_workflow(
        DAG_WORKFLOW,
        payload,
        id=f"{DAG_WORKFLOW}-{uuid.uuid4()}",
        task_queue=f"{DAG_WORKFLOW}-queue",
    )
    logger.info("done !")


if __name__ == "__main__":
    setup_loggers(["__main__"], level=logging.INFO)
    asyncio.run(main())
