import logging
from collections.abc import Generator
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
from logging import DEBUG

from icij_common.logging_utils import setup_loggers
from icij_common.pydantic_utils import (
    icij_config,
    lowercamel_case_config,
    merge_configs,
)
from kestrapy import Configuration, KestraClient, State, StateType, TaskRun
from kestrapy.exceptions import NotFoundException
from kestrapy.models import ExecutionStatusEvent
from pydantic import BaseModel

logger = logging.getLogger(__name__)


class Progress(BaseModel):
    model_config = merge_configs(icij_config(), lowercamel_case_config())

    progress: float
    max_progress: float


def _evt_task_runs(
    client: KestraClient, evt: ExecutionStatusEvent, tenant: str
) -> Generator[TaskRun, None, None]:
    exec = client.executions.execution(execution_id=evt.execution_id, tenant=tenant)
    logger.debug("task runs: %s", [(e.execution_id, e.id) for e in exec.task_run_list])
    if exec.task_run_list:
        yield from exec.task_run_list


def _progress_key(run_id: str) -> str:
    return f"progress-{run_id}"


def _aggregate_progress(progresses: dict[str, Progress]) -> float:
    # logger.info("aggregating: %s", sorted(progresses.items()))
    p, max_p = zip(*((p.progress, p.max_progress) for p in progresses.values()))
    max_p = sum(max_p)
    if max_p == 0:
        return 0
    return sum(p) / max_p


def _progress_from_state(state: State) -> float:
    if state.current is StateType.SUCCESS:
        return 1
    return 0


def _can_poll(last_polled: datetime | None, max_update_period_s: float) -> bool:
    if last_polled is None:
        return True
    return (datetime.now(UTC) - last_polled).total_seconds() > max_update_period_s


def _is_terminated(state: StateType) -> bool:
    match state:
        case (
            StateType.FAILED
            | StateType.WARNING
            | StateType.SUCCESS
            | StateType.KILLED
            | StateType.CANCELLED
            | StateType.RETRIED
            | StateType.KILLED
        ):
            return True
        case _:
            return False


# TODO: ideally the Python client would use asyncio and we would just poll the
#  event stream concurrently
def _iter_with_timeout(iterator, timeout_seconds):
    with ThreadPoolExecutor(max_workers=1) as executor:
        while True:
            future = executor.submit(next, iterator)
            try:
                result = future.result(timeout=timeout_seconds)
                yield result
            except StopIteration:
                break  # Iterator exhausted normally


def stream_progress(
    client: KestraClient,
    execution_id: str,
    *,
    tenant: str = "main",
    max_update_period_s: float = 1.0,
) -> Generator[float, None, None]:
    execution_states = dict()
    run_states = dict()
    progresses = dict()
    should_exit = False

    while not should_exit:
        # TODO: ideally the Python client would use asyncio and we would just poll the
        #  event stream concurrently. Here since events come as a stream when new
        #  execution start or finish, we need pause streaming periodically to detect
        #  new executions...
        events = []
        try:
            for evt in _iter_with_timeout(
                client.executions.follow_dependencies_executions(
                    execution_id, tenant=tenant, destination_only=False, expand_all=True
                ),
                timeout_seconds=max_update_period_s,
            ):
                events.append(ExecutionStatusEvent.model_validate(evt))
        except TimeoutError:
            pass
        else:
            should_exit = True
        # Poll executions
        event_execs = set(e.execution_id for e in events)
        for exec_id in event_execs:
            if _is_terminated(execution_states.get(exec_id, None)):
                continue
            exec = client.executions.execution(
                execution_id=exec_id, tenant=tenant
            )
            execution_states[exec_id] = exec.state.current
            for run in exec.task_run_list:
                if _is_terminated(run_states.get(run.id, None)):
                    continue
                run_states[run.id] = run.state.current
                try:
                    progress = client.kv.key_value(
                        namespace, _progress_key(run.id), tenant=tenant
                    ).value
                    progress = Progress.model_validate(progress["progress"])
                except NotFoundException:
                    progress = Progress(
                        progress=_progress_from_state(run.state), max_progress=1
                    )
                progresses[run.id] = progress
        yield _aggregate_progress(progresses)


if __name__ == "__main__":
    loggers = ["__main__", "kestra_progress_client"]
    setup_loggers(loggers, level=DEBUG)

    exec_id = "28aVFa9NG7wHV98FVqH9vZ"
    namespace = "org.icij.datashare"

    configuration = Configuration()
    configuration.host = "http://localhost:8080"
    configuration.username = "c@icij.com"
    configuration.password = "Testtest0"
    kestra_client = KestraClient(configuration)

    max_update_period_s = 1.0

    for exec_p in stream_progress(
        kestra_client, exec_id, max_update_period_s=max_update_period_s
    ):
        logger.info(f"progress: ({int(exec_p * 100)}/100)")
