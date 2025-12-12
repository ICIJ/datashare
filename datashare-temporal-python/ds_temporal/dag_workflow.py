import asyncio
import random
from datetime import timedelta

from icij_common.pydantic_utils import icij_config
from pydantic import BaseModel
from temporalio import activity, workflow

DAG_WORKFLOW = "dag_workflow"

GLOBAL_ACTIVITE_TIMEOUT = timedelta(hours=1)


class DagPayload(BaseModel):
    model_config = icij_config()

    max_tasks: int
    batch_size: int


@activity.defn
def produce_act(max_tasks: int) -> int:
    return random.randint(1, max_tasks)


@activity.defn
def create_batches_act(n: int, batch_size: int) -> list[list[int]]:
    return create_batches(n, batch_size=batch_size)


def create_batches(n: int, *, batch_size: int) -> list[list[int]]:
    return [list(range(i, min(i + batch_size, n))) for i in range(0, n, batch_size)]


@activity.defn
def reduce(partial_sums: list[int]) -> int:
    return sum(partial_sums)


@activity.defn
async def process_batch_act(batch: list[int]) -> int:
    total = 0
    for i in batch:
        total += i
        activity.logger.info("going to sleep...")
        await asyncio.sleep(1)
    activity.logger.info("done sleeping !")
    return total


@workflow.defn(name=DAG_WORKFLOW)
class DagWorkflow:
    @workflow.run
    async def run(self, payload: DagPayload) -> int:
        workflow.logger.info("producing %s tasks", payload.max_tasks)
        n = await workflow.execute_activity(
            produce_act,
            payload.max_tasks,
            start_to_close_timeout=GLOBAL_ACTIVITE_TIMEOUT,
        )
        workflow.logger.info("creating data batches...")
        args = [n, payload.batch_size]
        # TODO: find a nice way to use kwargs here...
        batches = await workflow.execute_activity(
            create_batches_act,
            args=args,
            start_to_close_timeout=GLOBAL_ACTIVITE_TIMEOUT,
        )
        workflow.logger.info("processing batches...")
        partial_sums = await asyncio.gather(
            *[
                workflow.execute_activity(
                    process_batch_act,
                    batch,
                    start_to_close_timeout=GLOBAL_ACTIVITE_TIMEOUT,
                )
                for batch in batches
            ]
        )
        workflow.logger.info("aggregating batches...")
        return await workflow.execute_activity(
            reduce, partial_sums, start_to_close_timeout=GLOBAL_ACTIVITE_TIMEOUT
        )


sync_activities = [produce_act, create_batches_act, reduce]
async_activities = [process_batch_act]
