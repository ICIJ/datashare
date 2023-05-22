import functools
import json
import logging
import uuid
from contextlib import contextmanager
from inspect import signature
from typing import Generator, Tuple

import pydantic
from proton import Message, symbol
from proton._reactor import DurableSubscription, DynamicNodeProperties, ReceiverOption
from proton._utils import BlockingReceiver, BlockingSender
from proton.utils import BlockingConnection

from python_worker.icij_worker.worker import (
    ICIJApp,
    Status,
    StatusUpdate,
    Task,
    UnknownTaskError,
)

logger = logging.getLogger(__name__)

_STATUS_TOPIC = "tasks.status"
_MAX_JOB_DURATION = 1000 * 10


def _update_task(update: StatusUpdate, status_updater: BlockingSender):
    status_updater.send(Message(json.dumps(update.dict()).encode()))


def _set_progress(progress: float, *, t: Task, status_updater: BlockingSender):
    update = StatusUpdate(name=t.name, progress=progress, state=Status.RUNNING)
    status_updater.send(Message(json.dumps(update.dict()).encode()))


class CreateQueueOption(ReceiverOption):
    def apply(self, receiver):
        receiver.source.capabilities.put_object(symbol("queue"))


@contextmanager
def _make_activemq_conn(
    app: ICIJApp,
) -> Generator[Tuple[BlockingReceiver, BlockingSender], None, None]:
    # TODO: if we want worker to process several tasks at the same time configure it
    #  here
    conn = None
    try:
        conn = BlockingConnection("127.0.0.1:5672", timeout=None)
        worker_name = f"worker-{uuid.uuid4().hex}"
        status_updater = conn.create_sender(address=_STATUS_TOPIC, name=worker_name)
        task_listener = conn.create_receiver(
            address="icij-job/count",
            name=worker_name,
            options=[DurableSubscription(), CreateQueueOption()],
        )
        yield task_listener, status_updater
    finally:
        if conn is not None:
            conn.close()


def main_amqp(app: ICIJApp):
    with _make_activemq_conn(app) as (task_listener, status_updater):
        worker_name = status_updater.name
        progress_updater = functools.partial(
            _set_progress, status_updater=status_updater
        )
        while True:
            logger.info("%s waiting for work...", worker_name)
            msg: Message = task_listener.receive()
            try:
                t = pydantic.parse_raw_as(Task, msg.body)
                registered = app.registry.get(t.type)
                if registered is None:
                    # If the task is unknown, we mark it as failed and do not requeue it
                    e = UnknownTaskError(
                        f"Unknown task {t.type}, available tasks: {list()}"
                    )
                    update = StatusUpdate(name=t.name, error=str(e), state=Status.ERROR)
                    _update_task(update=update, status_updater=status_updater)
                    task_listener.acknowledge(msg)
                    continue
                recoverable = tuple(registered.get("recover_from", []))
                task_fn = registered["task"]
                if any(
                    param.name == "progress_handler"
                    for param in signature(task_fn).parameters.values()
                ):
                    task_status_updater = functools.partial(progress_updater, t=t)
                    task_fn = functools.partial(
                        task_fn, progress_handler=task_status_updater
                    )
                _update_task(
                    update=app.check_max_retries(t), status_updater=status_updater
                )
                try:
                    task_res = task_fn(**t.inputs)
                except recoverable as e:
                    logger.error("%s - task %s, error: %s", worker_name, t.name, e)
                    update = StatusUpdate(
                        name=t.name,
                        error=str(e),
                        state=Status.RETRY,
                        retries=t.retries + 1,
                    )
                    _update_task(update=update, status_updater=status_updater)
                    task_listener.reject()
                    continue
                logger.info("%s succeeded for task %s", worker_name, t.name)
                update = StatusUpdate(
                    name=t.name,
                    state=Status.DONE,
                    result=task_res,
                    progress=100,
                    error="",  # Ideally we would set it to null
                )
                _update_task(update=update, status_updater=status_updater)
                task_listener.accept()
            except Exception as e:
                logger.error("error in %s for task %s: %s", worker_name, t.name, e)
                update = StatusUpdate(name=t.name, error=str(e), state=Status.ERROR)
                _update_task(update=update, status_updater=status_updater)
