import asyncio
import random
from datetime import timedelta

from icij_common.pydantic_utils import icij_config
from pydantic import BaseModel
from temporalio import activity, workflow

from ds_temporal.utils import (
    ProgressHandler,
    ProgressMixin,
    make_activity_with_progress,
)

DAG_WORKFLOW = "dag_workflow"

GLOBAL_ACTIVITY_TIMEOUT = timedelta(hours=1)


class DagPayload(BaseModel):
    model_config = icij_config()

    max_tasks: int
    batch_size: int


def produce_act(max_tasks: int) -> int:
    return random.randint(1, max_tasks)


produce_act = make_activity_with_progress(produce_act, weight=1.0)


def create_batches(
    n: int, *, batch_size: int, progress: ProgressHandler | None = None
) -> list[list[int]]:
    from ds_temporal.dependencies import lifespan_event_loop

    batches = []
    for i in range(0, n, batch_size):
        batches.append(list(range(i, min(i + batch_size, n))))
        if progress is not None:
            lifespan_event_loop().run_until_complete(progress((i + 1) / n))
    return batches


# TODO: make an helper for positional args
def create_batches_act(
    n: int, batch_size: int, progress: ProgressHandler | None = None
) -> list[list[int]]:
    return create_batches(n, batch_size=batch_size, progress=progress)


create_batches_act = make_activity_with_progress(create_batches_act, weight=2.0)


def reduce_act(partial_sums: list[int]) -> int:
    return sum(partial_sums)


reduce_act = make_activity_with_progress(reduce_act, weight=2.0)


async def process_batch(
    batch: list[int], *, progress: ProgressHandler | None = None
) -> int:
    total = 0
    for n, batch_item in enumerate(batch):
        total += batch_item
        activity.logger.info("going to sleep...")
        await asyncio.sleep(1)
        if progress is not None:
            await progress((n + 1) / len(batch))
    activity.logger.info("done sleeping !")
    return total


async def process_batch_act(
    batch: list[int], progress: ProgressHandler | None = None
) -> int:
    return await process_batch(batch, progress=progress)


process_batch_act = make_activity_with_progress(process_batch_act, weight=5.0)


@workflow.defn(name=DAG_WORKFLOW)
class DagWorkflow(ProgressMixin):
    @workflow.run
    async def run(self, payload: DagPayload) -> int:
        workflow.logger.info("producing %s tasks", payload.max_tasks)
        n = await workflow.execute_activity(
            "produce_act",
            payload.max_tasks,
            start_to_close_timeout=GLOBAL_ACTIVITY_TIMEOUT,
        )
        workflow.logger.info("creating data batches...")
        # TODO: find a nice way to use kwargs here...
        batches = await workflow.execute_activity(
            "create_batches_act",
            args=[n, payload.batch_size],
            start_to_close_timeout=GLOBAL_ACTIVITY_TIMEOUT,
        )
        workflow.logger.info("processing batches...")
        partial_sums = await asyncio.gather(
            *[
                workflow.execute_activity(
                    "process_batch_act",
                    batch,
                    start_to_close_timeout=GLOBAL_ACTIVITY_TIMEOUT,
                )
                for batch in batches
            ]
        )
        workflow.logger.info("aggregating batches...")
        return await workflow.execute_activity(
            "reduce_act",
            partial_sums,
            start_to_close_timeout=GLOBAL_ACTIVITY_TIMEOUT,
        )


sync_activities = [produce_act, create_batches_act, reduce_act]
async_activities = [process_batch_act]
