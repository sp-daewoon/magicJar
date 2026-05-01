"""실제 Playwright 어댑터 — in-process `playwright.async_api`.

ADR 0011 (2026-05-01 개정) — Python async 시나리오, Node.js 의존 0.

흐름:
1. spec_code 안의 `async def scenario(page):` 함수를 exec로 로드
2. playwright.async_api.async_playwright()로 chromium 띄움
3. page.screenshot을 monkey-patch — path/duration/png_bytes 누적
4. scenario(page) 실행
5. 예외 발생 시 마지막 단계 다음에 합성 fail 캡처 추가
6. PlaywrightRunResult로 반환

보안: exec(spec_code)은 architect 권한자 작성만 허용 (ADR 0017).
"""
from __future__ import annotations

import logging
import time
from pathlib import Path
from typing import Any

from app.screenshot.scenario import PlaywrightLike, PlaywrightRunResult

log = logging.getLogger(__name__)

_REQUIRED_FN_NAME = "scenario"
_FAIL_STEP_TEMPLATE = "{:02d}-error.png"


class RealPlaywrightRunner(PlaywrightLike):
    """in-process Python playwright async API."""

    def __init__(self, headless: bool = True, default_timeout_ms: int = 30_000) -> None:
        self.headless = headless
        self.default_timeout_ms = default_timeout_ms

    async def run_spec(
        self, spec_path: Path, viewport: dict
    ) -> PlaywrightRunResult:
        # 1) spec 코드 로드 — async def scenario(page) 함수 추출
        scenario_fn = _load_scenario(spec_path)

        # 2) playwright session
        from playwright.async_api import async_playwright  # type: ignore[import-not-found]

        captures: list[tuple[str, bytes, int]] = []
        failed_step_name: str | None = None
        error_message: str | None = None

        async with async_playwright() as pw:
            browser = await pw.chromium.launch(headless=self.headless)
            try:
                context = await browser.new_context(viewport=viewport)
                page = await context.new_page()
                page.set_default_timeout(self.default_timeout_ms)

                # 3) page.screenshot monkey-patch — 단계 자동 추적
                original_screenshot = page.screenshot

                async def wrapped_screenshot(**kwargs: Any) -> bytes:
                    path = kwargs.pop("path", None)
                    # path는 우리가 추적하므로 playwright에는 전달 안 함 (디스크 저장 X)
                    start = time.monotonic()
                    png = await original_screenshot(type="png", **kwargs)
                    dur_ms = int((time.monotonic() - start) * 1000)
                    name = path or f"step-{len(captures) + 1:02d}.png"
                    captures.append((str(name), png, dur_ms))
                    return png

                page.screenshot = wrapped_screenshot  # type: ignore[method-assign]

                # 4) scenario 실행
                try:
                    await scenario_fn(page)
                except Exception as e:  # noqa: BLE001 — runtime error 모두 fail로
                    error_message = f"{type(e).__name__}: {e}"
                    log.warning(
                        "scenario raised — marking next step as fail: %s",
                        error_message,
                    )
                    # 5) 합성 fail 캡처 (page가 살아있으면 시도)
                    fail_idx = len(captures) + 1
                    fail_name = _FAIL_STEP_TEMPLATE.format(fail_idx)
                    fail_png: bytes = b""
                    try:
                        fail_png = await original_screenshot(type="png")
                    except Exception:  # noqa: BLE001 — page도 죽었을 수 있음
                        log.debug("fail-capture failed too, using empty bytes")
                    captures.append((fail_name, fail_png, 0))
                    failed_step_name = fail_name

            finally:
                await browser.close()

        return PlaywrightRunResult(
            captures=captures,
            failed_step_name=failed_step_name,
            error_message=error_message,
        )


def _load_scenario(spec_path: Path):  # noqa: ANN202 — return is async callable
    """spec 파일에서 `async def scenario(page):` 함수 추출.

    Raises:
        ValueError: 함수가 없거나 callable 아닐 때.
    """
    code = spec_path.read_text(encoding="utf-8")
    namespace: dict[str, Any] = {}
    try:
        exec(  # noqa: S102 — architect 권한자 작성 (ADR 0017)
            compile(code, str(spec_path), "exec"),
            namespace,
        )
    except SyntaxError as e:
        raise ValueError(f"scenario spec_code 문법 오류: {e}") from e

    fn = namespace.get(_REQUIRED_FN_NAME)
    if not callable(fn):
        raise ValueError(
            f"spec_code 안에 `async def {_REQUIRED_FN_NAME}(page):` 함수가 필요"
        )
    return fn
