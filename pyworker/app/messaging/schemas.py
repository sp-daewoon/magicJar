"""Pydantic 모델 — Kotlin domain event JSON과 호환.

호환 규약 (spec §4 + domain/news, domain/market 참고):
- 필드명: camelCase (Kotlin data class 그대로)
- UUID: 문자열로 직렬화
- Instant: `2026-04-27T10:30:00Z` (ISO-8601 UTC, milliseconds 허용)
- BigDecimal: 문자열 직렬화 (Spring Jackson default가 plain string 처리하므로 우리도 string)

Kotlin 측 클래스 (스키마 정의 단일 진실):
- com.mj.domain.news.event.NewsRawEvent
- com.mj.domain.news.event.DartDisclosureEvent
- com.mj.domain.market.event.CandleEvent  (참고용, pyworker는 publish 안 함)
- com.mj.domain.market.event.TickEvent     (참고용)
- com.mj.domain.market.event.CandleMinuteAggregatedEvent  (ADR 0048, pyworker yahoo backfill publish)

ScreenshotCapturedEvent: Kotlin domain class는 P3 시점에 미정의 (consumer 토픽 설정만 존재).
pyworker가 정의하는 스키마가 사실상 단일 진실 — Kotlin 측은 P4에서 동일 필드로 추가 정의 예정.
"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from typing import Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


def _to_iso_utc(value: datetime) -> str:
    """Kotlin Instant 호환 ISO-8601 UTC 문자열."""
    if value.tzinfo is None:
        # naive datetime은 UTC로 가정 (caller 책임)
        from datetime import timezone

        value = value.replace(tzinfo=timezone.utc)
    return value.isoformat().replace("+00:00", "Z")


class _KafkaModel(BaseModel):
    """공통 Pydantic 설정.

    - alias 적용 (혹시 이후 snake_case 호환이 필요하면 by_alias 옵션 사용)
    - Instant 직렬화 = ISO-8601 UTC
    - UUID 직렬화 = 문자열
    """

    model_config = ConfigDict(
        populate_by_name=True,
        json_encoders={
            datetime: _to_iso_utc,
            UUID: str,
            Decimal: str,
        },
    )


class NewsRawEvent(_KafkaModel):
    """Topic: `news.raw.v1` — Kotlin com.mj.domain.news.event.NewsRawEvent."""

    newsId: UUID
    source: str
    publishedAt: datetime
    title: str
    body: str
    instruments: list[str] = Field(default_factory=list)


class DartDisclosureEvent(_KafkaModel):
    """Topic: `dart.disclosure.v1` — Kotlin com.mj.domain.news.event.DartDisclosureEvent."""

    disclosureId: UUID
    code: str
    companyName: str
    disclosureType: str
    title: str
    filedAt: datetime
    url: str | None = None


class ScreenshotCapturedEvent(_KafkaModel):
    """Topic: `screenshot.captured.v1`.

    pyworker가 헤드리스 캡처 후 MinIO 업로드하면 발행. Kotlin 측 domain class는
    P4 시점에 매칭하여 추가 (필드명·타입 동일). API 안정화 위해 v1.
    """

    snapshotId: UUID
    sourceUrl: str
    storageKey: str
    publicUrl: str | None = None
    contentType: Literal["image/png", "image/jpeg"] = "image/png"
    width: int
    height: int
    capturedAt: datetime
    requestedBy: str | None = None  # api-app 호출 컨텍스트 (journalId 등)


class CandleMinuteAggregatedEvent(_KafkaModel):
    """Topic: `candle.minute.aggregated.v1` — Kotlin com.mj.domain.market.event.CandleMinuteAggregatedEvent.

    PR #113 정의. consumer:TickToMinuteAggregator가 1분 버킷 flush 시 발행 (KIS WS 1분봉 누적).
    api 측 CandleMinuteEventListener가 수신 후 candle 테이블에 INSERT (interval=M1) — 룰엔진/백테스트용.

    **2026-05-05 정정 (사용자 결정)**: pyworker yahoo backfill은 더 이상 이 토픽에 publish하지 않는다.
    yfinance 결과는 사용자 차트 전용(Redis only) path이므로 `UserCandleUpdatedEvent` /
    `market.candle.user.v1` 토픽으로 단일 batch 발행 (ADR 0050 §C-1 정합).

    필드 (Kotlin data class 그대로):
    - code        종목코드 (e.g. "005930")
    - openTime    1분 버킷 시작 시각 (Instant, KST 분 단위 align)
    - open/high/low/close  BigDecimal → 문자열 직렬화
    - volume      Long
    - tradeCount  Long
    """

    code: str
    openTime: datetime
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: int
    tradeCount: int = 0


class CandleSnapshot(_KafkaModel):
    """Kotlin com.mj.domain.market.event.CandleSnapshot — UserCandleUpdatedEvent의 element."""

    openTime: datetime
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: int


class UserCandleUpdatedEvent(_KafkaModel):
    """Topic: `market.candle.user.v1` — Kotlin com.mj.domain.market.event.UserCandleUpdatedEvent.

    사용자 차트 cache update 이벤트. consumer:UserCandleListener가 수신 후 Redis ZADD를 수행한다.
    api:UserChartCandleService(KIS direct fetch)와 pyworker(yfinance backfill) 양쪽이 같은 토픽에 발행.

    yfinance backfill은 종목별 단 1건 메시지로 fetch한 모든 candles(보통 ~1000건)를 batch publish —
    consumer가 단 1번 ZADD batch + STOMP fanout 1회로 묶음 처리.
    """

    userId: str
    code: str
    interval: str  # "M1", "D1" 등
    candles: list[CandleSnapshot]
    occurredAt: datetime


# Kafka __TypeId__ 헤더 매핑
# JsonDeserializer 가 Kotlin 측에서 본인 클래스를 찾을 수 있도록 FQN 부여.
# Kotlin 측에서 spring.json.type.mapping 으로 alias도 가능하지만 FQN이 가장 안전.
TYPE_ID_HEADERS: dict[type[_KafkaModel], str] = {
    NewsRawEvent: "com.mj.domain.news.event.NewsRawEvent",
    DartDisclosureEvent: "com.mj.domain.news.event.DartDisclosureEvent",
    ScreenshotCapturedEvent: "com.mj.domain.market.event.ScreenshotCapturedEvent",
    CandleMinuteAggregatedEvent: (
        "com.mj.domain.market.event.CandleMinuteAggregatedEvent"
    ),
    UserCandleUpdatedEvent: (
        "com.mj.domain.market.event.UserCandleUpdatedEvent"
    ),
}
