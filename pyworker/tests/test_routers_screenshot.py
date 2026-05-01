"""POST /screenshot 통합 — capturer/storage/publisher 모두 fake/stub."""

from __future__ import annotations

import json

import pytest


@pytest.mark.asyncio
async def test_post_screenshot_returns_response_and_publishes_event(
    client, app, fake_producer
) -> None:
    resp = await client.post(
        "/screenshot",
        json={
            "url": "https://example.com/page",
            "viewport": {"width": 1280, "height": 720},
            "fullPage": False,
            "requestedBy": "test:case",
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["width"] == 1280
    assert body["height"] == 720
    assert body["contentType"] == "image/png"
    assert body["storageKey"].startswith("screenshots/")
    # ADR 0011 (2026-05-01 개정) — InMemoryStorage도 public_host 기반 URL 발급
    assert body["publicUrl"].startswith("http://localhost:9000/")

    # storage 에 저장됐는지
    storage = app.state.storage
    assert body["storageKey"] in storage.objects

    # Kafka 발행됐는지
    assert len(fake_producer.records) == 1
    kafka_payload = json.loads(fake_producer.records[0]["value"])
    assert kafka_payload["sourceUrl"] == "https://example.com/page"
    assert kafka_payload["requestedBy"] == "test:case"
    assert (
        fake_producer.records[0]["headers"]["__TypeId__"].decode("utf-8")
        == "com.mj.domain.market.event.ScreenshotCapturedEvent"
    )


@pytest.mark.asyncio
async def test_post_screenshot_validates_viewport_bounds(client) -> None:
    resp = await client.post(
        "/screenshot",
        json={"url": "https://x", "viewport": {"width": 100, "height": 100}},
    )
    assert resp.status_code == 422
