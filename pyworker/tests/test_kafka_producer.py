"""KafkaProducer + publishers — fake aiokafka producer로 검증.

Kotlin domain event JSON 호환:
- camelCase 필드 유지
- __TypeId__ 헤더에 Kotlin FQN
- Instant ISO-8601 UTC
"""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone

import pytest

from app.messaging.kafka_producer import (
    DartDisclosurePublisher,
    NewsRawPublisher,
    ScreenshotCapturedPublisher,
    serialize_for_test,
)
from app.messaging.schemas import (
    DartDisclosureEvent,
    NewsRawEvent,
    ScreenshotCapturedEvent,
)


@pytest.mark.asyncio
async def test_news_raw_publisher_sends_camel_case_json(
    kafka_producer, fake_producer
) -> None:
    publisher = NewsRawPublisher(kafka_producer, "news.raw.v1")
    event = NewsRawEvent(
        newsId=uuid.UUID("11111111-1111-1111-1111-111111111111"),
        source="naver",
        publishedAt=datetime(2026, 4, 27, 9, 30, tzinfo=timezone.utc),
        title="삼성전자 호실적",
        body="요약",
        instruments=["005930"],
    )
    await publisher.publish(event)

    assert len(fake_producer.records) == 1
    rec = fake_producer.records[0]
    assert rec["topic"] == "news.raw.v1"
    assert rec["key"] == "11111111-1111-1111-1111-111111111111"
    body = json.loads(rec["value"])
    # Kotlin data class 필드명 매칭
    assert body == {
        "newsId": "11111111-1111-1111-1111-111111111111",
        "source": "naver",
        "publishedAt": "2026-04-27T09:30:00Z",
        "title": "삼성전자 호실적",
        "body": "요약",
        "instruments": ["005930"],
    }
    assert (
        rec["headers"]["__TypeId__"].decode("utf-8")
        == "com.mj.domain.news.event.NewsRawEvent"
    )


@pytest.mark.asyncio
async def test_dart_publisher_sends_correct_topic_and_type_id(
    kafka_producer, fake_producer
) -> None:
    publisher = DartDisclosurePublisher(kafka_producer, "dart.disclosure.v1")
    event = DartDisclosureEvent(
        disclosureId=uuid.UUID("22222222-2222-2222-2222-222222222222"),
        code="00126380",
        companyName="삼성전자",
        disclosureType="주요사항보고서",
        title="유상증자 결정",
        filedAt=datetime(2026, 4, 27, 0, 0, tzinfo=timezone.utc),
        url="https://dart.fss.or.kr/...",
    )
    await publisher.publish(event)

    rec = fake_producer.records[0]
    assert rec["topic"] == "dart.disclosure.v1"
    assert (
        rec["headers"]["__TypeId__"].decode("utf-8")
        == "com.mj.domain.news.event.DartDisclosureEvent"
    )


@pytest.mark.asyncio
async def test_screenshot_publisher_camel_case_payload(
    kafka_producer, fake_producer
) -> None:
    publisher = ScreenshotCapturedPublisher(kafka_producer, "screenshot.captured.v1")
    event = ScreenshotCapturedEvent(
        snapshotId=uuid.UUID("33333333-3333-3333-3333-333333333333"),
        sourceUrl="https://example.com/chart",
        storageKey="screenshots/abc.png",
        publicUrl="http://minio:9000/magicjar-screenshots/abc.png",
        contentType="image/png",
        width=1280,
        height=720,
        capturedAt=datetime(2026, 4, 27, 12, 0, tzinfo=timezone.utc),
        requestedBy="journal:42",
    )
    await publisher.publish(event)

    body = json.loads(fake_producer.records[0]["value"])
    assert body["snapshotId"] == "33333333-3333-3333-3333-333333333333"
    assert body["capturedAt"] == "2026-04-27T12:00:00Z"
    assert body["width"] == 1280


def test_serialize_for_test_matches_kotlin_naming() -> None:
    event = NewsRawEvent(
        newsId=uuid.uuid4(),
        source="dart",
        publishedAt=datetime.now(tz=timezone.utc),
        title="t",
        body="b",
        instruments=[],
    )
    payload = serialize_for_test(event)
    assert set(payload.keys()) == {
        "newsId",
        "source",
        "publishedAt",
        "title",
        "body",
        "instruments",
    }
