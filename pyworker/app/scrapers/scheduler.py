"""APScheduler — 정기 크롤링 잡.

FastAPI lifespan 안에서 시작/종료. SCHEDULER_ENABLED=false 면 아예 등록 안 함.
"""

from __future__ import annotations

import logging
from typing import Awaitable, Callable

from apscheduler.schedulers.asyncio import AsyncIOScheduler

log = logging.getLogger(__name__)


class ScrapeScheduler:
    """async 잡 등록 wrapper."""

    def __init__(self) -> None:
        self._scheduler = AsyncIOScheduler(timezone="UTC")
        self._started = False

    def add_periodic(
        self,
        name: str,
        interval_sec: int,
        func: Callable[[], Awaitable[None]],
    ) -> None:
        self._scheduler.add_job(
            func,
            trigger="interval",
            seconds=interval_sec,
            id=name,
            replace_existing=True,
            max_instances=1,
        )
        log.info("scheduler.registered job=%s interval=%ds", name, interval_sec)

    def start(self) -> None:
        if self._started:
            return
        self._scheduler.start()
        self._started = True
        log.info("scheduler.started")

    def shutdown(self) -> None:
        if not self._started:
            return
        self._scheduler.shutdown(wait=False)
        self._started = False
        log.info("scheduler.shutdown")

    @property
    def running(self) -> bool:
        return self._started
