"""다단계 Playwright 시나리오 runner.

ADR 0011 (2026-05-01 개정) 다단계 시나리오 endpoint를 위한 코어.

흐름:
- specCode 문자열 inline 받아 임시 .spec.ts 파일에 기록
- PlaywrightLike 구현체에 위임해 실행 (실 구현 = scenario_pw.RealPlaywrightRunner)
- 단계별 PNG 캡처 결과를 StepResult로 변환
- runtime error 발생 단계 = fail, 시각 디펙 자동 판정은 비범위
"""
from __future__ import annotations

import logging
import re
import tempfile
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Protocol

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ScenarioRequest:
    spec_code: str
    viewport_w: int = 1440
    viewport_h: int = 900
    full_page: bool = False
    timeout_ms: int = 30_000


@dataclass
class StepResult:
    index: int
    name: str
    status: str  # "pass" | "fail"
    duration_ms: int
    image_bytes: bytes
    error: Optional[str] = None


@dataclass
class ScenarioResult:
    steps: list[StepResult] = field(default_factory=list)
    failed_at_step: Optional[int] = None
    total_duration_ms: int = 0


@dataclass
class PlaywrightRunResult:
    """PlaywrightLike 구현체의 raw 결과 — 단계 추적용."""

    captures: list[tuple[str, bytes, int]]  # (filename, png_bytes, duration_ms)
    failed_step_name: Optional[str] = None
    error_message: Optional[str] = None


class PlaywrightLike(Protocol):
    """테스트용 추상화 — 실제는 RealPlaywrightRunner (scenario_pw.py).

    `run_spec`은 spec_path 파일을 받아 Playwright 실행 후 단계별 캡처를
    PlaywrightRunResult로 반환한다. 단계 식별은 `page.screenshot({path})` 의
    파일명으로 한다.
    """

    async def run_spec(
        self, spec_path: Path, viewport: dict
    ) -> PlaywrightRunResult: ...


_STEP_NAME_RE = re.compile(r"^(\d+)-(.+?)\.png$", re.IGNORECASE)


class ScenarioRunner:
    """specCode → 임시 파일 → PlaywrightLike 위임 → 단계별 PNG."""

    def __init__(self, *, playwright: PlaywrightLike) -> None:
        self._pw = playwright

    async def run(self, req: ScenarioRequest) -> ScenarioResult:
        # 1) specCode → 임시 파일
        tmp_dir = Path(tempfile.mkdtemp(prefix="qa-scenario-"))
        spec_path = tmp_dir / f"scenario-{uuid.uuid4().hex[:8]}.spec.ts"
        spec_path.write_text(req.spec_code, encoding="utf-8")

        try:
            raw = await self._pw.run_spec(
                spec_path,
                viewport={"width": req.viewport_w, "height": req.viewport_h},
            )
        finally:
            # 항상 임시 파일 폐기
            try:
                spec_path.unlink(missing_ok=True)
                tmp_dir.rmdir()
            except OSError:
                log.warning("temp scenario dir cleanup failed: %s", tmp_dir)

        # 2) raw.captures를 StepResult로 변환
        steps: list[StepResult] = []
        failed_idx: Optional[int] = None
        for i, (filename, png_bytes, dur) in enumerate(raw.captures):
            name = _normalize_step_name(filename, fallback_index=i)
            is_failed = raw.failed_step_name == filename
            steps.append(
                StepResult(
                    index=i,
                    name=name,
                    status="fail" if is_failed else "pass",
                    duration_ms=dur,
                    image_bytes=png_bytes,
                    error=raw.error_message if is_failed else None,
                )
            )
            if is_failed:
                failed_idx = i

        total = sum(s.duration_ms for s in steps)
        return ScenarioResult(
            steps=steps, failed_at_step=failed_idx, total_duration_ms=total
        )


def _normalize_step_name(filename: str, fallback_index: int) -> str:
    """`01-goto-symbol-page.png` → `01-goto-symbol-page`."""
    m = _STEP_NAME_RE.match(filename)
    if m:
        return f"{m.group(1)}-{m.group(2)}"
    return f"{fallback_index:02d}-unnamed"
