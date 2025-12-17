import asyncio
import logging
from asyncio import AbstractEventLoop
from typing import cast

from temporalio.client import Client

from ds_temporal.exceptions import DependencyInjectionError
from ds_temporal.worker import AppConfig

_EVENT_LOOP: asyncio.AbstractEventLoop | None = None
_TEMPORAL_CLIENT: Client | None = None

logger = logging.getLogger(__name__)


async def event_loop_setup(event_loop: AbstractEventLoop, **_) -> None:
    global _EVENT_LOOP
    _EVENT_LOOP = event_loop


def lifespan_event_loop() -> AbstractEventLoop:
    if _EVENT_LOOP is None:
        raise DependencyInjectionError("event loop")
    return cast(AbstractEventLoop, _EVENT_LOOP)


async def temporal_client_setup(config: AppConfig, **_) -> None:
    global _TEMPORAL_CLIENT
    _TEMPORAL_CLIENT = await Client.connect(**config.temporal_connect.as_kwargs())


def lifespan_temporal_client() -> Client:
    if _TEMPORAL_CLIENT is None:
        raise DependencyInjectionError("temporal client")
    return cast(Client, _TEMPORAL_CLIENT)


WORKER_APP_DEPENDENCIES = [
    ("event_loop", event_loop_setup, None),
    ("temporal client", temporal_client_setup, None),
]
