"""POST /backfill/yahoo — Yahoo Finance 분봉 backfill.

ADR 0048 §C. KIS 분봉 API의 당일 한도를 우회하기 위한 외부 source.

요청·응답 schema는 ai-engineer 작업 지시 그대로:
    POST /backfill/yahoo
    {
      "code": "005930",
      "interval": "1m",
      "days": 30,
      "kospiSuffix": ".KS",
      "kosdaqFallbackSuffix": ".KQ"
    }
    → 200 OK
    {
      "code": "005930",
      "interval": "1m",
      "fetched": N,
      "published": N,
      "timeWindow": {"start":"...", "end":"..."}
    }

동작:
1) 정규화 → yahoo fetch → Kafka publish (synchronous; pyworker process 1 thread 사용)
2) 1m × 30days는 ~60s 내 끝나는 양 — BackgroundTask 사용 안 함.
3) 응답 직접 반환 (사용자가 동기 결과 확인 가능).
"""

from __future__ import annotations

import logging
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from app.backfill.service import YahooBackfillService, normalize_request
from app.backfill.yahoo_client import YahooFetchError

log = logging.getLogger(__name__)

router = APIRouter(prefix="/backfill", tags=["backfill"])


class YahooBackfillRequestBody(BaseModel):
    """REST request body."""

    code: str = Field(..., description="6자리 한국 종목코드", examples=["005930"])
    interval: str = Field(
        ...,
        description="봉 단위 (1m·5m·15m·30m·60m 만 1차 지원, 1d는 KIS 영구 영속이라 비범위)",
        examples=["1m"],
    )
    days: int = Field(..., gt=0, description="거꾸로 fetch할 일수 (1m은 30일 한도)", examples=[30])
    kospiSuffix: str = Field(default=".KS", description="KOSPI suffix")
    kosdaqFallbackSuffix: str = Field(
        default=".KQ", description="`.KS` 빈 결과 시 fallback suffix"
    )


class YahooBackfillResponse(BaseModel):
    code: str
    interval: str
    fetched: int
    published: int
    timeWindow: dict[str, str | None]


def _service(request: Request) -> YahooBackfillService:
    svc = getattr(request.app.state, "yahoo_backfill_service", None)
    if svc is None:
        raise HTTPException(
            status_code=503,
            detail="yahoo_backfill_service unavailable (Kafka producer not started)",
        )
    return svc


@router.post(
    "/yahoo",
    summary="Yahoo Finance 분봉 backfill (Kafka publish)",
    response_model=YahooBackfillResponse,
)
async def trigger_yahoo_backfill(
    body: YahooBackfillRequestBody,
    service: Annotated[YahooBackfillService, Depends(_service)],
) -> YahooBackfillResponse:
    """yfinance.history 호출 → CandleMinuteAggregatedEvent로 변환 → Kafka publish."""
    try:
        normalized = normalize_request(
            code=body.code,
            interval=body.interval,
            days=body.days,
            kospi_suffix=body.kospiSuffix,
            kosdaq_fallback_suffix=body.kosdaqFallbackSuffix,
        )
    except YahooFetchError as ex:
        raise HTTPException(status_code=400, detail=str(ex)) from ex

    try:
        result = await service.run(normalized)
    except YahooFetchError as ex:
        # 외부 서비스(yfinance) 실패 → 502 Bad Gateway
        log.warning("backfill.yahoo: yfinance failed code=%s err=%s", body.code, ex)
        raise HTTPException(status_code=502, detail=f"yahoo upstream error: {ex}") from ex

    return YahooBackfillResponse(
        code=result.code,
        interval=result.interval,
        fetched=result.fetched,
        published=result.published,
        timeWindow=result.time_window,
    )
