import asyncio
import inspect
import logging
import sys
from collections.abc import AsyncGenerator, Coroutine, Generator
from contextlib import asynccontextmanager, contextmanager
from functools import partial, wraps
from inspect import signature
from types import TracebackType
from typing import (
    Callable,
    ParamSpec,
    Protocol,
    TypeVar,
)

import nest_asyncio
from pydantic import BaseModel, computed_field
from temporalio import activity, workflow
from temporalio.client import Client, WorkflowHandle

logger = logging.getLogger(__name__)
DependencyLabel = str | None
DependencySetup = Callable[..., None]
DependencyAsyncSetup = Callable[..., Coroutine[None, None, None]]

PROGRESS_HANDLER_ARG = "progress"


# TODO: use a protocol here
class ProgressHandler(Protocol):
    async def __call__(self, progress: float) -> None:
        pass


P = ParamSpec("P")
T = TypeVar("T")


class DependencyTeardown(Protocol):
    def __call__(
        self,
        exc_type: type[Exception] | None,
        exc_value: Exception | None,
        traceback: TracebackType | None,
    ) -> None: ...


class DependencyAsyncTeardown(Protocol):
    async def __call__(
        self,
        exc_type: type[Exception] | None,
        exc_value: Exception | None,
        traceback: TracebackType | None,
    ) -> None: ...


Dependency = tuple[
    DependencyLabel,
    DependencySetup | DependencyAsyncSetup,
    DependencyTeardown | DependencyAsyncTeardown | None,
]


class Progress(BaseModel):
    current: float = 0.0
    max_progress: float

    @computed_field
    @property
    def progress(self) -> float:
        if self.max_progress == 0.0:
            return 0.0
        return self.current / self.max_progress


class ProgressSignal(BaseModel):
    run_id: str
    activity_id: str
    progress: float = 0.0  # TODO: add validation that it should be between 0..1
    weight: float = 1.0

    def to_progress(self) -> Progress:
        return Progress(current=self.progress * self.weight, max_progress=self.weight)


class ProgressMixin:
    def __init__(self):
        self._progress = dict()
        self._update_lock = asyncio.Lock()

    @workflow.signal
    async def update_progress(self, signal: ProgressSignal) -> None:
        async with self._update_lock:
            key = (signal.run_id, signal.activity_id)
            self._progress[key] = signal.to_progress()

    @workflow.query
    def get_progress(self, run_id: str) -> Progress:
        values = (v for k, v in self._progress.items() if k[0] == run_id)
        return _sum_progresses(*values)


async def progress_handler(
    progress: float,
    handle: WorkflowHandle,
    *,
    activity_id: str,
    run_id: str,
    weight: float = 1.0,
) -> None:
    signal = ProgressSignal(
        activity_id=activity_id, run_id=run_id, progress=progress, weight=weight
    )
    await handle.signal("update_progress", signal)


def get_activity_progress_handler_async(
    client: Client, weight: float
) -> ProgressHandler:
    info = activity.info()
    run_id = info.workflow_run_id
    workflow_id = info.workflow_id
    activity_id = activity.info().activity_id
    workflow_handle = client.get_workflow_handle(workflow_id, run_id=run_id)
    handler = partial(
        progress_handler,
        handle=workflow_handle,
        run_id=run_id,
        activity_id=activity_id,
        weight=weight,
    )
    return handler


def supports_progress(task_fn: Callable) -> bool:
    return any(
        param.name == PROGRESS_HANDLER_ARG
        for param in signature(task_fn).parameters.values()
    )


def make_activity_with_progress(
    activity_fn: Callable[P, T], weight: float = 1.0
) -> Callable[P, T]:
    # TODO: handle the fact activities should have only positional args...
    # TODO: check if function supports progress, skip otherwise
    # TODO: check that progress is the last arg
    if asyncio.iscoroutinefunction(activity_fn):

        @wraps(activity_fn)
        async def wrapper(*args: P.args) -> T:
            handler = get_activity_progress_handler_async(
                client=activity.client(), weight=weight
            )
            await handler(0.0)
            if supports_progress(activity_fn):
                res = await activity_fn(*args, progress=handler)
            else:
                res = await activity_fn(*args)
            await handler(1.0)
            return res

    else:

        @wraps(activity_fn)
        def wrapper(*args: P.args) -> T:
            from ds_temporal.dependencies import (
                lifespan_event_loop,
                lifespan_temporal_client,
            )

            handler = get_activity_progress_handler_async(
                client=lifespan_temporal_client(), weight=weight
            )
            event_loop = lifespan_event_loop()
            # TODO: remove this and use a separate thread to run progress ?
            nest_asyncio.apply()

            event_loop.run_until_complete(handler(0.0))
            if supports_progress(activity_fn):
                res = activity_fn(*args, progress=handler)
            else:
                res = activity_fn(*args)
            event_loop.run_until_complete(handler(1.0))
            return res

    return activity.defn(wrapper)


@contextmanager
def _log_exception_and_continue() -> Generator[None, None, None]:
    try:
        yield
    except Exception as exc:  # noqa: BLE001
        logger.exception("Exception %s occurred ", exc)


@asynccontextmanager
async def run_deps(
    dependencies: list[Dependency], ctx: str, **kwargs
) -> AsyncGenerator[None, None]:
    to_close = []
    original_ex = None
    try:
        logger.info("Setting up dependencies for %s...", ctx)
        for name, enter_fn, exit_fn in dependencies:
            if enter_fn is not None:
                if name is not None:
                    logger.debug("Setup up dependency: %s", name)
                if inspect.iscoroutinefunction(enter_fn):
                    await enter_fn(**kwargs)
                else:
                    enter_fn(**kwargs)
            to_close.append((name, exit_fn))
        yield
    except Exception as e:  # noqa: BLE001
        original_ex = e
    finally:
        to_raise = []
        if original_ex is not None:
            to_raise.append(original_ex)
        logger.info("Rolling back dependencies for %s...", ctx)
        for name, exit_fn in to_close[::-1]:
            if exit_fn is None:
                continue
            try:
                if name is not None:
                    logger.debug("rolling back %s", name)
                exc_info = sys.exc_info()
                with _log_exception_and_continue():
                    if inspect.iscoroutinefunction(exit_fn):
                        await exit_fn(*exc_info)
                    else:
                        exit_fn(*exc_info)
            except Exception as e:  # noqa: BLE001
                to_raise.append(e)
        logger.debug("Rolled back all dependencies for %s !", ctx)
        if to_raise:
            for e in to_raise:
                logger.exception("Error while handling dependencies %s!", e)
            raise RuntimeError(to_raise) from to_raise[0]


def _sum_progresses(*progress: Progress) -> Progress:
    max_progress = sum(p.max_progress for p in progress)
    current = sum(p.current for p in progress)
    return Progress(max_progress=max_progress, current=current)
