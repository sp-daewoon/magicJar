"""FastAPI 진입.

lifespan 안에서:
- httpx.AsyncClient 시작
- KafkaProducer 시작 + 토픽별 publisher 인스턴스화
- StorageBackend (MinIO 또는 InMemory) 준비 + bucket ensure
- PlaywrightCapturer 인스턴스화 (실제 launch는 요청 시점)
- APScheduler 등록 + (옵션) 시작

shutdown 시 역순으로 정리.

env BOOTSTRAP_EXTERNAL=false 인 경우 Kafka·MinIO connect 안 함 (로컬 dev 편의).
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import AsyncIterator

import httpx
from fastapi import FastAPI

from app.config import get_settings
from app.messaging.kafka_producer import (
    DartDisclosurePublisher,
    KafkaProducer,
    NewsRawPublisher,
    ScreenshotCapturedPublisher,
)
from app.routers import health, scrape, screenshot
from app.scrapers.dart import fetch_dart_disclosures
from app.scrapers.naver_news import fetch_naver_news
from app.scrapers.scheduler import ScrapeScheduler
from app.screenshot.capture import PlaywrightCapturer, StubCapturer
from app.screenshot.storage import InMemoryStorage, MinioStorage, StorageBackend

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    log.info(
        "pyworker.startup bootstrap_external=%s scheduler_enabled=%s",
        settings.bootstrap_external,
        settings.scheduler_enabled,
    )

    # 1) HTTP client
    http_client = httpx.AsyncClient()
    app.state.http_client = http_client

    # 2) Kafka
    producer = KafkaProducer(
        bootstrap_servers=settings.kafka_bootstrap,
        client_id=settings.kafka_client_id,
        enabled=settings.bootstrap_external,
    )
    await producer.start()
    app.state.kafka = producer
    app.state.news_raw_publisher = NewsRawPublisher(producer, settings.kafka_topic_news_raw)
    app.state.dart_publisher = DartDisclosurePublisher(producer, settings.kafka_topic_dart)
    app.state.screenshot_publisher = ScreenshotCapturedPublisher(
        producer, settings.kafka_topic_screenshot
    )

    # 3) Storage
    storage: StorageBackend
    if settings.bootstrap_external:
        minio = MinioStorage(
            endpoint=settings.s3_endpoint,
            access_key=settings.s3_access_key,
            secret_key=settings.s3_secret_key,
            bucket=settings.s3_bucket_screenshot,
            secure=settings.s3_secure,
            region=settings.s3_region,
        )
        try:
            await minio.ensure_bucket()
        except Exception as ex:
            log.warning("MinIO ensure_bucket failed: %s — falling back to InMemoryStorage", ex)
            storage = InMemoryStorage(bucket=settings.s3_bucket_screenshot)
        else:
            storage = minio
    else:
        storage = InMemoryStorage(bucket=settings.s3_bucket_screenshot)
    app.state.storage = storage

    # 4) Capturer
    if settings.bootstrap_external:
        app.state.capturer = PlaywrightCapturer(
            headless=settings.playwright_headless,
            default_timeout_ms=settings.playwright_default_timeout_ms,
        )
    else:
        app.state.capturer = StubCapturer()

    # 5) Scheduler
    scheduler = ScrapeScheduler()
    app.state.scheduler = scheduler

    if settings.scheduler_enabled and settings.bootstrap_external:

        async def _periodic_naver() -> None:
            try:
                events = await fetch_naver_news(
                    http_client,
                    settings.naver_news_list_url,
                    settings.naver_news_user_agent,
                )
                for ev in events:
                    await app.state.news_raw_publisher.publish(ev)
            except Exception as ex:  # noqa: BLE001
                log.warning("scheduler.naver failed: %s", ex)

        async def _periodic_dart() -> None:
            try:
                events = await fetch_dart_disclosures(
                    http_client, settings.dart_base_url, settings.dart_api_key
                )
                for ev in events:
                    await app.state.dart_publisher.publish(ev)
            except Exception as ex:  # noqa: BLE001
                log.warning("scheduler.dart failed: %s", ex)

        scheduler.add_periodic(
            "naver_news", settings.scrape_interval_news_sec, _periodic_naver
        )
        scheduler.add_periodic(
            "dart_disclosures", settings.scrape_interval_dart_sec, _periodic_dart
        )
        scheduler.start()

    try:
        yield
    finally:
        log.info("pyworker.shutdown")
        if scheduler.running:
            scheduler.shutdown()
        await producer.stop()
        await http_client.aclose()


def create_app() -> FastAPI:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s — %(message)s",
    )
    app = FastAPI(
        title="magicJar pyworker",
        version="0.1.0",
        description="Python service: scrape (naver/DART) + screenshot (Playwright). Kafka·HTTP·MinIO only.",
        lifespan=lifespan,
    )
    app.include_router(health.router)
    app.include_router(scrape.router)
    app.include_router(screenshot.router)
    return app


app = create_app()
