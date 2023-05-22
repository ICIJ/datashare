import functools
import json
import logging
import uuid
from collections import defaultdict
from contextlib import contextmanager
from copy import copy
from enum import Enum, unique
from inspect import signature
from typing import Any, Callable, Dict, Generator, List, Optional, Type

import pydantic
import stomp
from pydantic import BaseModel
from stomp.__main__ import do_nothing_loop
from stomp.constants import HDR_ID
from stomp.utils import Frame

ANY_CAST = "ANYCAST"

logger = logging.getLogger(__name__)

_STATUS_TOPIC = "tasks.status"
JOB_QUEUE_PREFIX = "icij-job"
_MAX_JOB_DURATION = 1000 * 10


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


def _update_task(update: StatusUpdate, conn: stomp.Connection, destination: str):
    conn.send(destination, json.dumps(update.dict()).encode())


def _set_progress(
    progress: float, *, t: Task, conn: stomp.Connection, destination: str
):
    update = StatusUpdate(name=t.name, progress=progress, state=Status.RUNNING)
    conn.send(destination, json.dumps(update.dict()).encode())


@contextmanager
def _make_activemq_conn(app: ICIJApp) -> Generator[stomp.Connection11, None, None]:
    # TODO: if we want worker to process several tasks at the same time configure it
    #  here
    conn = None
    try:
        # TODO: set the heartbeat here
        conn = stomp.Connection11([("127.0.0.1", 61616)], timeout=None)
        consumer_name = f"worker-{uuid.uuid4().hex}"
        progress_updater = functools.partial(
            _set_progress, conn=conn, destination=_STATUS_TOPIC
        )
        conn.set_listener(
            "tasks",
            TaskListener(app, consumer_name, progress__fn=progress_updater, conn=conn),
        )
        conn.connect(wait=False, with_connect_command=True, headers={HDR_ID: consumer_name, "client-id": consumer_name})
        # TODO: set timeouts and so on...
        for queue in app.registry:
            conn.subscribe(
                destination=f"{JOB_QUEUE_PREFIX}/{queue}",
                id=consumer_name,
                ack="client-individual",
                headers={"durable-subscription-name": consumer_name}
            )
        yield conn
    finally:
        if conn is not None:
            conn.disconnect()


class TaskListener(stomp.ConnectionListener):
    def __init__(
        self,
        app: ICIJApp,
        worker_name: str,
        progress__fn: Callable[[float, Task], None],
        conn: stomp.Connection,
    ):
        self._app = app
        self._progress_fn = progress__fn
        self._worker_name = worker_name
        self._conn = conn

    def on_error(self, frame: Frame):
        pass

    def on_message(self, frame: Frame):
        logger.info("received message")
        t = pydantic.parse_raw_as(Task, frame.body)
        registered = self._app.registry.get(t.type)
        if registered is None:
            # If the task is unknown, we mark it as failed and do not requeue it
            e = UnknownTaskError(f"Unknown task {t.type}, available tasks: {list()}")
            update = StatusUpdate(name=t.name, error=str(e), state=Status.ERROR)
            _update_task(update=update, conn=self._conn, destination=_STATUS_TOPIC)
            self._conn.ack(
                id=frame.headers["message-id"], subscription=self._worker_name
            )
            return
        task_fn = registered["task"]
        if any(
            param.name == "progress_handler"
            for param in signature(task_fn).parameters.values()
        ):
            task_status_updater = functools.partial(self._progress_fn, t=t)
            task_fn = functools.partial(task_fn, progress_handler=task_status_updater)
        task_res = task_fn(**t.inputs)
        logger.info("%s succeeded for task %s", self._worker_name, t.name)
        update = StatusUpdate(
            name=t.name,
            state=Status.DONE,
            result=task_res,
            progress=100,
            error="",  # Ideally we would set it to null
        )
        _update_task(update=update, conn=self._conn, destination=_STATUS_TOPIC)
        self._conn.ack(id=frame.headers["message-id"], subscription=self._worker_name)


def main(app: ICIJApp):
    with _make_activemq_conn(app) as conn:
        # task = Task(
        #     name="some-id",
        #     type="count",
        #     retries=-1,
        #     max_retries=10,
        #     inputs={"url": "https://www.icij.org/"},
        # )
        # str_task = task.json().encode()
        # conn.send("icij-job/count", str_task)
        # time.sleep()
        do_nothing_loop()
