"""POST /screenshot/scenario — 다단계 시나리오 캡처.

ADR 0011 (2026-05-01 개정) 다단계 모드.
spec docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md §6 시그니처.
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Annotated, Literal, Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from app.screenshot.scenario import ScenarioRequest, ScenarioRunner
from app.screenshot.storage import StorageBackend

log = logging.getLogger(__name__)
router = APIRouter(prefix="/screenshot", tags=["screenshot"])


class Viewport(BaseModel):
    width: int = Field(default=1440, ge=320, le=4096)
    height: int = Field(default=900, ge=240, le=4096)


class ScenarioRequestBody(BaseModel):
    specCode: str
    issueId: str
    attempt: Literal["before", "after"]
    retry: int = 1
    viewport: Viewport = Field(default_factory=Viewport)
    requestedBy: Optional[str] = None


class StepResponse(BaseModel):
    index: int
    name: str
    status: str
    durationMs: int
    storageKey: str
    presignedUrl: Optional[str]
    error: Optional[str] = None


class ScenarioResponse(BaseModel):
    issueId: str
    attempt: str
    retry: int
    runTimestamp: str
    steps: list[StepResponse]
    failedAtStep: Optional[int]
    totalDurationMs: int


def _scenario_runner(request: Request) -> ScenarioRunner:
    r = getattr(request.app.state, "scenario_runner", None)
    if r is None:
        raise HTTPException(status_code=503, detail="scenario_runner unavailable")
    return r


def _storage(request: Request) -> StorageBackend:
    s = getattr(request.app.state, "storage", None)
    if s is None:
        raise HTTPException(status_code=503, detail="storage unavailable")
    return s


@router.post(
    "/scenario",
    response_model=ScenarioResponse,
    summary="다단계 Playwright 시나리오 캡처 (ADR 0011, 2026-05-01)",
)
async def run_scenario(
    body: ScenarioRequestBody,
    runner: Annotated[ScenarioRunner, Depends(_scenario_runner)],
    storage: Annotated[StorageBackend, Depends(_storage)],
) -> ScenarioResponse:
    # 1) 시나리오 실행 (Playwright in-process)
    sreq = ScenarioRequest(
        spec_code=body.specCode,
        viewport_w=body.viewport.width,
        viewport_h=body.viewport.height,
    )
    sresult = await runner.run(sreq)

    # 2) 단계별 PNG → MinIO 업로드 + presigned URL
    run_ts = datetime.now(tz=timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    base = f"issues/{body.issueId}/{body.attempt}/{run_ts}"
    step_responses: list[StepResponse] = []

    for s in sresult.steps:
        suffix = "-FAIL" if s.status == "fail" else ""
        key = f"{base}/{s.name}{suffix}.png"
        put = await storage.put(key, s.image_bytes, "image/png")
        step_responses.append(
            StepResponse(
                index=s.index,
                name=s.name,
                status=s.status,
                durationMs=s.duration_ms,
                storageKey=put.key,
                presignedUrl=put.public_url,
                error=s.error,
            )
        )

    return ScenarioResponse(
        issueId=body.issueId,
        attempt=body.attempt,
        retry=body.retry,
        runTimestamp=run_ts,
        steps=step_responses,
        failedAtStep=sresult.failed_at_step,
        totalDurationMs=sresult.total_duration_ms,
    )
