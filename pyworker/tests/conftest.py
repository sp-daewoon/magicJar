"""pytest fixtures.

테스트에서는 Kafka·MinIO·Playwright를 모두 in-memory/stub로 교체.
"""

from __future__ import annotations

from typing import Any, AsyncIterator

import httpx
import pytest
import pytest_asyncio
from fastapi import FastAPI

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
        fastapi_app.state.storage = InMemoryStorage(bucket="test-bucket")
        fastapi_app.state.capturer = StubCapturer()
        yield fastapi_app


@pytest_asyncio.fixture
async def client(app: FastAPI) -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
