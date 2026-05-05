"""pytest fixtures.

테스트에서는 Kafka·MinIO·Playwright를 모두 in-memory/stub로 교체.
"""

from __future__ import annotations

from typing import Any, AsyncIterator

import httpx
import pytest
import pytest_asyncio
from fastapi import FastAPI

from app.backfill.service import UserCandleEventPublisher, YahooBackfillService
from app.config import reset_settings_for_test
from app.main import create_app
from app.messaging.kafka_producer import (
    DartDisclosurePublisher,
    KafkaProducer,
    NewsRawPublisher,
    ScreenshotCapturedPublisher,
)
from app.screenshot.capture import StubCapturer
from app.screenshot.storage import InMemoryStorage


class FakeAioProducer:
    """aiokafka send_and_wait 를 capture 만 하는 fake."""

    def __init__(self) -> None:
        self.records: list[dict[str, Any]] = []

    async def send_and_wait(
        self,
        topic: str,
        value: bytes,
        key: bytes | None = None,
        headers: list | None = None,
    ) -> None:
        self.records.append(
            {
                "topic": topic,
                "key": key.decode("utf-8") if key else None,
                "value": value,
                "headers": dict(headers or []),
            }
        )


@pytest.fixture(autouse=True)
def _reset_settings() -> None:
    reset_settings_for_test()
    yield
    reset_settings_for_test()


@pytest.fixture
def fake_producer() -> FakeAioProducer:
    return FakeAioProducer()


@pytest.fixture
def storage_with_public_host():
    """ADR 0011 (2026-05-01) — MINIO_PUBLIC_HOST 기반 InMemoryStorage."""
    return InMemoryStorage(
        bucket="magicjar-screenshots",
        public_host="localhost:9000",
        secure=False,
    )


@pytest.fixture
def storage_with_public_host_secure():
    return InMemoryStorage(
        bucket="magicjar-screenshots",
        public_host="localhost:9000",
        secure=True,
    )


@pytest.fixture
def fake_playwright():
    """모든 단계 PASS하는 fake — Task 5/7 단위 테스트용."""
    from app.screenshot.scenario import PlaywrightRunResult

    class FakePW:
        async def run_spec(self, spec_path, viewport):
            return PlaywrightRunResult(
                captures=[
                    ("01-goto.png", b"\x89PNG\r\n\x1a\nfake", 850),
                    ("02-final.png", b"\x89PNG\r\n\x1a\nfake", 320),
                ],
                failed_step_name=None,
                error_message=None,
            )

    return FakePW()


@pytest.fixture
def fake_playwright_failing_at_step_2():
    """2단계에서 selector not found로 fail하는 fake."""
    from app.screenshot.scenario import PlaywrightRunResult

    class FakePW:
        async def run_spec(self, spec_path, viewport):
            return PlaywrightRunResult(
                captures=[
                    ("01-goto.png", b"\x89PNG\r\n\x1a\nok", 800),
                    ("02-click.png", b"\x89PNG\r\n\x1a\nfail-shot", 300),
                ],
                failed_step_name="02-click.png",
                error_message="selector .candle-series not visible (timeout 30000ms)",
            )

    return FakePW()


@pytest.fixture
def kafka_producer(fake_producer: FakeAioProducer) -> KafkaProducer:
    p = KafkaProducer(
        bootstrap_servers="fake:9092", client_id="test", enabled=True
    )
    p._inject_for_test(fake_producer)
    return p


@pytest_asyncio.fixture
async def app(monkeypatch, kafka_producer: KafkaProducer, fake_producer: FakeAioProducer) -> AsyncIterator[FastAPI]:
    """테스트용 app — 외부 의존(BOOTSTRAP_EXTERNAL=false)으로 lifespan 통과."""
    monkeypatch.setenv("BOOTSTRAP_EXTERNAL", "false")
    monkeypatch.setenv("SCHEDULER_ENABLED", "false")
    fastapi_app = create_app()

    # lifespan을 진입해 state 초기화
    async with fastapi_app.router.lifespan_context(fastapi_app):
        # 테스트 격리를 위해 state의 producer를 fake로 교체.
        fastapi_app.state.kafka = kafka_producer
        fastapi_app.state.news_raw_publisher = NewsRawPublisher(
            kafka_producer, "news.raw.v1"
        )
        fastapi_app.state.dart_publisher = DartDisclosurePublisher(
            kafka_producer, "dart.disclosure.v1"
        )
        fastapi_app.state.screenshot_publisher = ScreenshotCapturedPublisher(
            kafka_producer, "screenshot.captured.v1"
        )
        user_candle_pub = UserCandleEventPublisher(
            kafka_producer, "market.candle.user.v1"
        )
        fastapi_app.state.user_candle_publisher = user_candle_pub
        fastapi_app.state.yahoo_backfill_service = YahooBackfillService(user_candle_pub)
        fastapi_app.state.storage = InMemoryStorage(
            bucket="magicjar-screenshots",
            public_host="localhost:9000",
        )
        fastapi_app.state.capturer = StubCapturer()
        yield fastapi_app


@pytest.fixture
def fake_scenario_runner_3pass(app):
    """3단계 모두 PASS — Task 7 통합 테스트용."""
    from app.screenshot.scenario import ScenarioResult, StepResult

    class FakeRunner:
        async def run(self, req):
            return ScenarioResult(
                steps=[
                    StepResult(0, "01-blank", "pass", 800, b"\x89PNG\r\n\x1a\nfake1"),
                    StepResult(1, "02-still", "pass", 350, b"\x89PNG\r\n\x1a\nfake2"),
                    StepResult(2, "03-final", "pass", 200, b"\x89PNG\r\n\x1a\nfake3"),
                ],
                failed_at_step=None,
                total_duration_ms=1350,
            )

    app.state.scenario_runner = FakeRunner()
    return app


@pytest.fixture
def fake_scenario_runner_step2_fail(app):
    from app.screenshot.scenario import ScenarioResult, StepResult

    class FakeRunner:
        async def run(self, req):
            return ScenarioResult(
                steps=[
                    StepResult(0, "01-goto", "pass", 800, b"\x89PNG\r\n\x1a\nok"),
                    StepResult(
                        1, "02-error", "fail", 300, b"\x89PNG\r\n\x1a\nfail",
                        error="TimeoutError: selector .candle-series",
                    ),
                ],
                failed_at_step=1,
                total_duration_ms=1100,
            )

    app.state.scenario_runner = FakeRunner()
    return app


@pytest_asyncio.fixture
async def client(app: FastAPI) -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
