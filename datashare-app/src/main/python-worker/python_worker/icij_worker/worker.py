import functools
import json
import logging
import uuid
from collections import defaultdict
from contextlib import contextmanager
from copy import copy
from enum import Enum, unique
from inspect import signature
from typing import Any, Callable, Dict, Generator, List, Optional, Tuple, Type

import pulsar
import pydantic
from pydantic import BaseModel

logger = logging.getLogger(__name__)

_STATUS_TOPIC = "task-status"


class UnknownTaskError(ValueError):
    pass


class MaxRetryExceeded(RuntimeError):
    pass


class ICIJModel(BaseModel):
    class Config:
        allow_mutation = False
        extra = "forbid"
        allow_population_by_field_name = True
        use_enum_values = True

    def dict(self, **kwargs):
        kwargs = copy(kwargs)
        if "by_alias" in kwargs:
            by_alias = kwargs.pop("by_alias")
            if not by_alias:
                raise f"Can't serialize a {ICIJModel} without using alias"
        return super().dict(by_alias=True, **kwargs)


def to_lower_camel(field: str) -> str:
    return "".join(
        w.capitalize() if i > 0 else w for i, w in enumerate(field.split("_"))
    )


class LowerCamelCaseModel(ICIJModel):
    class Config:
        alias_generator = to_lower_camel


class IgnoreExtraModel(ICIJModel):
    class Config:
        extra = "ignore"


class Task(LowerCamelCaseModel, IgnoreExtraModel):
    name: str
    type: str
    inputs: Dict[str, Any]
    retries: int
    max_retries: int


@unique
class Status(str, Enum):
    CREATED = "CREATED"
    QUEUING = "QUEUING"
    RUNNING = "RUNNING"
    RETRY = "RETRY"
    ERROR = "ERROR"
    DONE = "DONE"
    CANCELLED = "CANCELLED"


class StatusUpdate(LowerCamelCaseModel, IgnoreExtraModel):
    name: str
    state: Optional[Status] = None
    progress: Optional[float] = None
    result: Optional[Any] = None
    error: Optional[str] = None
    retries: Optional[int] = None
    max_retries: Optional[int] = None


class ICIJApp:
    def __init__(self, name: str):
        self._name = name
        self._registry = dict()
        self._retries = defaultdict(int)

    @property
    def registry(self) -> Dict:
        return self._registry

    @property
    def name(self) -> str:
        return self._name

    def task(
        self,
        name: str,
        recover_from: Optional[List[Type]] = None,
        max_retries: int = 10,
    ) -> Callable:
        return functools.partial(
            self._register_task,
            name=name,
            recover_from=recover_from,
            max_retries=max_retries,
        )

    def _register_task(
        self,
        f: Callable,
        *,
        name: str,
        recover_from: Optional[List[Type]],
        max_retries=10,
    ) -> Callable:
        registered = self._registry.get(name)
        if registered is not None:
            raise ValueError(f'A task "{name}" was already registered: {registered}')
        registered = {"task": f, "max_retries": max_retries}
        if recover_from is not None:
            registered["recover_from"] = recover_from
        self._registry[name] = registered

        @functools.wraps(f)
        def wrapped(*args, **kwargs):
            return f(*args, **kwargs)

        return wrapped

    def check_max_retries(self, t: Task) -> StatusUpdate:
        max_retries = self.registry[t.type]["max_retries"]
        retries = self._retries[t.name]
        logger.info("task %s run %s/%s", t.name, retries, max_retries)
        if retries >= max_retries:
            raise MaxRetryExceeded(f"max retries exceeded > {max_retries} for {t.name}")
        update = StatusUpdate(
            name=t.name, state=Status.RUNNING, retries=retries, max_retries=max_retries
        )
        self._retries[t.name] += 1
        return update


def _update_task(update: StatusUpdate, status_updater: pulsar.Producer):
    status_updater.send(json.dumps(update.dict()).encode())


def _set_progress(progress: float, *, t: Task, status_updater: pulsar.Producer):
    update = StatusUpdate(name=t.name, progress=progress, state=Status.RUNNING)
    status_updater.send(json.dumps(update.dict()).encode())


@contextmanager
def _make_pulsar(
    app: ICIJApp,
) -> Generator[Tuple[pulsar.Consumer, pulsar.Producer], None, None]:
    # TODO: if we want worker to process several tasks at the same time configure it
    #  here
    client = None
    try:
        client = pulsar.Client("pulsar://localhost:6650")
        topics = list(app.registry)
        consumer_name = f"worker-{uuid.uuid4().hex}"
        task_listener = client.subscribe(
            topics,
            subscription_name=app.name,
            consumer_name=consumer_name,
            receiver_queue_size=1,
            consumer_type=pulsar.ConsumerType.Shared,
            negative_ack_redelivery_delay_ms=1 * 1000,
        )
        status_updater = client.create_producer(
            topic=_STATUS_TOPIC,
            producer_name=consumer_name,
        )
        yield task_listener, status_updater
    finally:
        if client is not None:
            client.shutdown()


def main(app: ICIJApp):
    with _make_pulsar(app) as (task_listener, status_updater):
        worker_name = status_updater.producer_name()
        progress_updater = functools.partial(
            _set_progress, status_updater=status_updater
        )
        while True:
            logger.info("%s waiting for work...", worker_name)
            msg: pulsar.Message = task_listener.receive()
            try:
                t = pydantic.parse_raw_as(Task, msg.data())
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
                    task_listener.negative_acknowledge(msg)
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
                task_listener.acknowledge(msg)
            except Exception as e:
                logger.error("error in %s for task %s: %s", worker_name, t.name, e)
                update = StatusUpdate(name=t.name, error=str(e), state=Status.ERROR)
                _update_task(update=update, status_updater=status_updater)
