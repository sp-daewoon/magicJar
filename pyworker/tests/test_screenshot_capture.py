"""StubCapturer 동작 + storage_key 명명 규칙."""

from __future__ import annotations

import uuid

import pytest

from app.screenshot.capture import CaptureRequest, StubCapturer
from app.screenshot.storage import InMemoryStorage, screenshot_key


@pytest.mark.asyncio
async def test_stub_capturer_returns_png_bytes() -> None:
    cap = StubCapturer()
    result = await cap.capture(CaptureRequest(url="https://example.com"))
    assert result.image_bytes.startswith(b"\x89PNG")
    assert result.content_type == "image/png"
    assert result.width == 1280
    assert result.height == 720


def test_screenshot_key_uses_uuid() -> None:
    sid = uuid.uuid4()
    key = screenshot_key(sid)
    assert key == f"screenshots/{sid}.png"


@pytest.mark.asyncio
async def test_in_memory_storage_persists_object() -> None:
    storage = InMemoryStorage(bucket="t")
    result = await storage.put("k.png", b"abc", "image/png")
    assert result.size == 3
    assert storage.objects["k.png"] == b"abc"
    assert result.public_url == "memory://t/k.png"
