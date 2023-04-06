import logging
import math
import time
from typing import Callable

import requests
from requests.exceptions import ConnectionError

from python_worker.icij_worker import ICIJApp

logger = logging.getLogger(__name__)

ProgressHandler = Callable[[float], None]

app = ICIJApp()


@app.task(name="count", recover_from=[ConnectionError], max_retries=5)
def count(url: str) -> int:
    logger.info("Counting characters at %s", url)
    resp = requests.get(url)
    return len(resp.text.split())


@app.task(name="sleep")
def sleep(duration_s: float, progress_handler: ProgressHandler):
    logger.info("Sleeping for %s secs", duration_s)
    seconds = math.floor(duration_s)
    remaining = duration_s - seconds
    for i in range(seconds):
        progress_handler(i / seconds)
        time.sleep(1)
    time.sleep(remaining)
