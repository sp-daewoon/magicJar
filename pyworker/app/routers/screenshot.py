"""POST /screenshot — 페이지 캡처 후 S3 업로드 + Kafka 발행.

요청 body: {url, viewport: {width, height}, fullPage, requestedBy}
응답: {snapshotId, storageKey, publicUrl, width, height, contentType}
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from app.messaging.kafka_producer import ScreenshotCapturedPublisher
from app.messaging.schemas import ScreenshotCapturedEvent
from app.screenshot.capture import CaptureRequest
from app.screenshot.storage import StorageBackend, screenshot_key

log = logging.getLogger(__name__)

router = APIRouter(prefix="/screenshot", tags=["screenshot"])


class Viewport(BaseModel):
    width: int = Field(default=1280, ge=320, le=4096)
    height: int = Field(default=720, ge=240, le=4096)


class ScreenshotRequest(BaseModel):
    url: str
    viewport: Viewport = Field(default_factory=Viewport)
    fullPage: bool = False
    requestedBy: str | None = None


class ScreenshotResponse(BaseModel):
    snapshotId: uuid.UUID
    storageKey: str
    publicUrl: str | None
    width: int
    height: int
    contentType: str
    capturedAt: datetime


def _capturer(request: Request):
    cap = getattr(request.app.state, "capturer", None)
    if cap is None:
        raise HTTPException(status_code=503, detail="capturer unavailable")
    return cap


def _storage(request: Request) -> StorageBackend:
    st = getattr(request.app.state, "storage", None)
    if st is None:
        raise HTTPException(status_code=503, detail="storage unavailable")
    return st


def _screenshot_publisher(request: Request) -> ScreenshotCapturedPublisher:
    pub = getattr(request.app.state, "screenshot_publisher", None)
    if pub is None:
        raise HTTPException(status_code=503, detail="screenshot_publisher unavailable")
    return pub


@router.post("", response_model=ScreenshotResponse, summary="헤드리스 페이지 캡처")
async def capture_screenshot(
    body: ScreenshotRequest,
    capturer: Annotated[object, Depends(_capturer)],
    storage: Annotated[StorageBackend, Depends(_storage)],
    publisher: Annotated[ScreenshotCapturedPublisher, Depends(_screenshot_publisher)],
) -> ScreenshotResponse:
    snapshot_id = uuid.uuid4()
    cap_req = CaptureRequest(
        url=body.url,
        width=body.viewport.width,
        height=body.viewport.height,
        full_page=body.fullPage,
    )
    cap_result = await capturer.capture(cap_req)  # type: ignore[attr-defined]
    key = screenshot_key(snapshot_id)
    store_result = await storage.put(key, cap_result.image_bytes, cap_result.content_type)
    captured_at = datetime.now(tz=timezone.utc)

    event = ScreenshotCapturedEvent(
        snapshotId=snapshot_id,
        sourceUrl=body.url,
        storageKey=store_result.key,
        publicUrl=store_result.public_url,
        contentType="image/png",
        width=cap_result.width,
        height=cap_result.height,
        capturedAt=captured_at,
        requestedBy=body.requestedBy,
    )
    await publisher.publish(event)

    return ScreenshotResponse(
        snapshotId=snapshot_id,
        storageKey=store_result.key,
        publicUrl=store_result.public_url,
        width=cap_result.width,
        height=cap_result.height,
        contentType="image/png",
        capturedAt=captured_at,
    )
