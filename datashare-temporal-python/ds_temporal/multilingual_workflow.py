import asyncio
from datetime import timedelta

from temporalio import workflow

from ds_temporal.dag_workflow import DagPayload
from ds_temporal.utils import ProgressMixin, TaskQueueMixin

MULTILINGUAL_WORKFLOW = "multilingual_workflow"

GLOBAL_ACTIVITY_TIMEOUT = timedelta(hours=1)


@workflow.defn(name=MULTILINGUAL_WORKFLOW)
class MultilingualWorkflow(ProgressMixin, TaskQueueMixin):
    @workflow.run
    async def run(self, payload: DagPayload) -> int:
        workflow.logger.info("producing %s tasks", payload.max_tasks)
        n = await workflow.execute_activity(
            "produce_act",
            payload.max_tasks,
            start_to_close_timeout=GLOBAL_ACTIVITY_TIMEOUT,
            task_queue=self.java_queue(),
        )
        workflow.logger.info("creating data batches...")
        # TODO: find a nice way to use kwargs here...
        batches = await workflow.execute_activity(
            "create_batches_act",
            args=[n, payload.batch_size],
            start_to_close_timeout=GLOBAL_ACTIVITY_TIMEOUT,
            task_queue=self.python_queue(),
        )
        workflow.logger.info("processing batches...")
        partial_sums = await asyncio.gather(
            *[
                workflow.execute_activity(
                    "process_batch_act",
                    batch,
                    start_to_close_timeout=GLOBAL_ACTIVITY_TIMEOUT,
                    task_queue=self.python_queue(),
                )
                for batch in batches
            ]
        )
        workflow.logger.info("aggregating batches...")
        return await workflow.execute_activity(
            "reduce_act",
            partial_sums,
            start_to_close_timeout=GLOBAL_ACTIVITY_TIMEOUT,
            task_queue=self.python_queue(),
        )
