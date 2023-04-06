import logging
import sys

import python_worker
from icij_worker import main as icij_worker_main

_STREAM_HANDLER_FMT = "[%(levelname)s][%(asctime)s.%(msecs)03d][%(name)s]: %(message)s"
_DATE_FMT = "%H:%M:%S"


def _setup_loggers():
    level = logging.DEBUG
    loggers = ["__main__", python_worker.__name__, "icij_worker"]
    stream_handler = logging.StreamHandler(sys.stderr)
    stream_handler.setFormatter(logging.Formatter(_STREAM_HANDLER_FMT, _DATE_FMT))
    stream_handler.setLevel(level)

    for logger in loggers:
        logger = logging.getLogger(logger)
        logger.setLevel(level)
        logger.handlers = []
        logger.addHandler(stream_handler)


if __name__ == "__main__":
    from tasks import app

    _setup_loggers()
    icij_worker_main(app)
