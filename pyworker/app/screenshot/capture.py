"""Playwright async API 기반 페이지 스크린샷.

매 요청마다 새 browser context 생성 → goto → 스크린샷 → close.
P3 단계는 chromium 단일 브라우저 (이미지 크기 절감).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Protocol

log = logging.getLogger(__name__)


@dataclass
class CaptureRequest:
    url: str
    width: int = 1280
    height: int = 720
    full_page: bool = False
    wait_until: str = "networkidle"  # 또는 'load', 'domcontentloaded'


@dataclass
class CaptureResult:
    image_bytes: bytes
    width: int
    height: int
    content_type: str = "image/png"


class _Capturer(Protocol):
    async def capture(self, req: CaptureRequest) -> CaptureResult: ...


class PlaywrightCapturer:
    """실제 Playwright 구현 — chromium headless."""

    def __init__(self, headless: bool = True, default_timeout_ms: int = 15_000) -> None:
        self.headless = headless
        self.default_timeout_ms = default_timeout_ms

    async def capture(self, req: CaptureRequest) -> CaptureResult:
        # 지연 import — Playwright 미설치 환경(테스트)에서도 import error 안 남게
        from playwright.async_api import async_playwright  # type: ignore[import-not-found]

        async with async_playwright() as p:
            browser = await p.chromium.launch(headless=self.headless)
            try:
                context = await browser.new_context(
                    viewport={"width": req.width, "height": req.height}
                )
                page = await context.new_page()
                page.set_default_timeout(self.default_timeout_ms)
                await page.goto(req.url, wait_until=req.wait_until)
                image = await page.screenshot(full_page=req.full_page, type="png")
                return CaptureResult(
                    image_bytes=image,
                    width=req.width,
                    height=req.height,
                    content_type="image/png",
                )
            finally:
                await browser.close()


class StubCapturer:
    """테스트용 구현 — 실제 Playwright 호출 없이 1x1 PNG 반환."""

    # 1x1 transparent PNG
    _PNG = (
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01"
        b"\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\xff"
        b"\xff?\x00\x05\xfe\x02\xfe\xa3\x35\x81\x84\x00\x00\x00\x00IEND\xaeB`\x82"
    )

    async def capture(self, req: CaptureRequest) -> CaptureResult:
        log.debug("StubCapturer.capture url=%s", req.url)
        return CaptureResult(
            image_bytes=self._PNG, width=req.width, height=req.height, content_type="image/png"
        )
