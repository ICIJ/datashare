import asyncio
from abc import ABC, abstractmethod
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import ClassVar, TypeVar

from icij_common.pydantic_utils import icij_config
from pydantic import BaseModel
from temporalio.client import (
    Client,
)
from temporalio.client import (
    ConnectConfig as _ConnectConfig,
)
from temporalio.client import (
    KeepAliveConfig as _KeepAliveConfig,
)
from temporalio.client import (
    RetryConfig as _RetryConfig,
)
from temporalio.worker import Worker

from ds_temporal.dag_workflow import (
    DAG_WORKFLOW,
    DagWorkflow,
    async_activities,
    sync_activities,
)
from ds_temporal.utils import Dependency, run_deps

T = TypeVar("T", bound=dataclass)


class TemporalModel[T](BaseModel, ABC):
    model_config = icij_config()

    temporal_cls: ClassVar[type[T]]

    @abstractmethod
    def temporal_cls(self) -> type[T]: ...

    def to_temporal(self) -> T:
        return self.temporal_cls(**self.model_dump())


class RetryConfig(TemporalModel[_RetryConfig]):
    temporal_cls: ClassVar[_RetryConfig] = _RetryConfig

    initial_interval_millis: int = 100
    randomization_factor: float = 0.2
    multiplier: float = 1.5
    max_interval_millis: int = 5000
    max_elapsed_time_millis: int | None = 10000
    max_retries: int = 10


class KeepAliveConfig(TemporalModel[_KeepAliveConfig]):
    temporal_cls: ClassVar[_KeepAliveConfig] = _KeepAliveConfig

    interval_millis: int = 30000
    timeout_millis: int = 15000


class ConnectConfig(TemporalModel[_ConnectConfig]):
    temporal_cls: ClassVar[_ConnectConfig] = _ConnectConfig

    target_host: str
    retry_config: RetryConfig = RetryConfig()
    keep_alive_config: KeepAliveConfig = KeepAliveConfig()
    lazy: bool = False

    def as_kwargs(self) -> dict:
        kwargs = self.model_dump()
        kwargs["retry_config"] = self.retry_config.to_temporal()
        kwargs["keep_alive_config"] = self.keep_alive_config.to_temporal()
        return kwargs


class AppConfig(BaseModel):
    model_config = icij_config()

    temporal_connect: ConnectConfig = ConnectConfig(target_host="localhost:7233")


class WorkerWithDeps(Worker):
    def __init__(
        self,
        client: Client,
        app_config: AppConfig,
        dependencies: list[Dependency] | None = None,
        **kwargs,
    ):
        super().__init__(client, **kwargs)
        self._app_config = app_config
        self._deps = dependencies or []
        self._loop = asyncio.get_running_loop()
        self._deps_cm = run_deps(
            self._deps,
            "worker dependencies",
            config=self._app_config,
            event_loop=self._loop,
        )

    async def run(self) -> None:
        await self._deps_cm.__aenter__()
        await super().run()

    async def shutdown(self) -> None:
        # TODO: should we pass something in here.... ?
        await self._deps_cm.__aexit__(None, None, None)
        await super().shutdown()


async def main() -> None:
    from ds_temporal.dependencies import WORKER_APP_DEPENDENCIES

    target_host = "localhost:7233"
    connect_config = ConnectConfig(target_host=target_host)
    app_config = AppConfig(temporal_connect=connect_config)
    client = await Client.connect(connect_config.target_host)
    # client = await Client.connect(target_host)
    # TODO: here we could group sync activities with workflow in a worker and async
    #  activities in another worker for optimized performances
    #  (see https://docs.temporal.io/develop/python/python-sdk-sync-vs-async)
    async_worker = WorkerWithDeps(
        client=client,
        dependencies=WORKER_APP_DEPENDENCIES,
        app_config=app_config,
        task_queue=f"{DAG_WORKFLOW}-queue",
        activities=async_activities,
    )
    sync_worker = WorkerWithDeps(
        client=client,
        dependencies=WORKER_APP_DEPENDENCIES,
        app_config=app_config,
        task_queue=f"{DAG_WORKFLOW}-queue",
        workflows=[DagWorkflow],
        activities=sync_activities,
        activity_executor=ThreadPoolExecutor(),
    )
    await asyncio.gather(sync_worker.run(), async_worker.run())


if __name__ == "__main__":
    asyncio.run(main())
