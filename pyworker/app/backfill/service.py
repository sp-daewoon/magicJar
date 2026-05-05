"""YahooBackfillService — DataFrame → Kafka publish 변환.

router.py가 호출. yahoo_client는 외부 yfinance만 다루고,
service는 정규화·publish·응답 모양을 책임.

**2026-05-05 정정 (사용자 결정 — ADR 0050 §C-1 정합)**:
- 호출 단위: 1분봉 ~1000개 ≈ 2 영업일 (pyworker는 caller가 보낸 days 그대로 사용; api는 days=2)
- 종목별 asyncio.Lock — 동시 호출 fold (in-flight 차단). 호출 끝나면 즉시 다음 호출 허용.
- end 파라미터 — cursor 기반 (frontend가 더 과거 윈도우 요청 시 그 시점 이전 윈도우 fetch)
- publish target: `market.candle.user.v1` 토픽 + `UserCandleUpdatedEvent` 단일 batch 메시지
  (사용자 차트 cold-start 전용 path. DB INSERT 0건. consumer가 ZADD batch + STOMP fanout 1회)

흐름:
1) request 정규화 (interval alias, days 한도)
2) 종목별 asyncio.Lock 획득 (동시 호출 차단)
3) yahoo_client.fetch_history → DataFrame
4) tz_kst → CandleSnapshot list
5) UserCandleEventPublisher → Kafka topic market.candle.user.v1 (단일 batch 메시지)
6) 결과 요약 응답
"""

from __future__ import annotations

import asyncio
import logging
from collections import defaultdict
from dataclasses import dataclass
from datetime import UTC, datetime
from decimal import Decimal

import pandas as pd

from app.backfill.yahoo_client import (
    FetchRequest,
    YahooFetchError,
    fetch_history,
    normalize_interval,
    to_kst_aware,
    validate_days,
)
from app.messaging.kafka_producer import KafkaProducer
from app.messaging.schemas import (
    TYPE_ID_HEADERS,
    CandleSnapshot,
    UserCandleUpdatedEvent,
)

log = logging.getLogger(__name__)


# Kafka topic + 1차 R1 지원 분봉 (1d는 비범위).
KAFKA_TOPIC_USER_CANDLE = "market.candle.user.v1"
SUPPORTED_MINUTE_INTERVALS: set[str] = {"1m", "5m", "15m", "30m", "60m"}

# ADR 0049 §2-B — 단일 사용자 모드 default. KIS 모의 계좌번호 8자리.
DEFAULT_USER_ID = "74683223"


@dataclass(frozen=True)
class BackfillRequest:
    """REST → service 사이 정규화된 요청.

    code: 6자리 한국 종목코드
    interval: 입력 alias 정규화된 yfinance 표준 ("1m"·"5m"·...)
    days: 거꾸로 fetch할 일수
    end: cursor — 미지정 시 now(), 지정 시 그 시점 이전 days만큼 fetch
    """

    code: str
    interval: str
    days: int
    end: datetime | None = None
    kospi_suffix: str = ".KS"
    kosdaq_fallback_suffix: str = ".KQ"


@dataclass(frozen=True)
class BackfillResult:
    code: str
    interval: str
    fetched: int
    published: int
    time_window: dict[str, str | None]  # {start, end} ISO-8601 KST or None if 0 rows


class UserCandleEventPublisher:
    """market.candle.user.v1 publish 래퍼 — 단일 batch event.

    api:UserChartCandleService(KIS direct path) Kafka publisher와 같은 토픽 공유.
    consumer:UserCandleListener가 receive 후 Redis ZADD batch + STOMP fanout 1회.
    """

    def __init__(self, producer: KafkaProducer, topic: str = KAFKA_TOPIC_USER_CANDLE) -> None:
        self.producer = producer
        self.topic = topic

    async def publish(self, event: UserCandleUpdatedEvent) -> None:
        # key = code (Kotlin api publisher와 동일 partitioning).
        await self.producer.publish(
            topic=self.topic,
            key=event.code,
            payload=event,
            type_id=TYPE_ID_HEADERS[UserCandleUpdatedEvent],
        )


def normalize_request(
    code: str,
    interval: str,
    days: int,
    end: datetime | None = None,
    kospi_suffix: str = ".KS",
    kosdaq_fallback_suffix: str = ".KQ",
) -> BackfillRequest:
    """raw input → BackfillRequest. raises YahooFetchError on invalid input."""
    if not code or len(code) != 6 or not code.isdigit():
        raise YahooFetchError(f"code must be 6-digit KR ticker (got '{code}')")
    yf_interval = normalize_interval(interval)
    if yf_interval not in SUPPORTED_MINUTE_INTERVALS:
        raise YahooFetchError(
            f"interval={interval} (yfinance={yf_interval}) is daily/weekly — "
            f"backfill only supports minute intervals "
            f"({sorted(SUPPORTED_MINUTE_INTERVALS)}). "
            f"For daily candles, use KIS REST adapter instead."
        )
    validate_days(yf_interval, days)
    return BackfillRequest(
        code=code,
        interval=yf_interval,
        days=days,
        end=end,
        kospi_suffix=kospi_suffix,
        kosdaq_fallback_suffix=kosdaq_fallback_suffix,
    )


def dataframe_to_snapshots(df: pd.DataFrame) -> list[CandleSnapshot]:
    """DataFrame → list[CandleSnapshot].

    columns 가정: ["Open", "High", "Low", "Close", "Volume"]
    index: pd.DatetimeIndex (KST tz-aware 권장; service에서 tz 정규화 후 호출)

    Decimal 변환은 pandas Float → str → Decimal (binary float 정밀도 회피).
    None/NaN row는 skip.
    """
    if df.empty:
        return []
    df = to_kst_aware(df)

    snapshots: list[CandleSnapshot] = []
    for ts, row in df.iterrows():
        if any(pd.isna(row[c]) for c in ("Open", "High", "Low", "Close", "Volume")):
            continue
        snapshots.append(
            CandleSnapshot(
                openTime=_normalize_ts(ts),
                open=Decimal(str(float(row["Open"]))),
                high=Decimal(str(float(row["High"]))),
                low=Decimal(str(float(row["Low"]))),
                close=Decimal(str(float(row["Close"]))),
                volume=int(row["Volume"]) if not pd.isna(row["Volume"]) else 0,
            )
        )
    return snapshots


def _normalize_ts(ts) -> datetime:
    """pandas Timestamp → tz-aware datetime (KST 보존).

    naive면 UTC로 가정.
    """
    if isinstance(ts, pd.Timestamp):
        py = ts.to_pydatetime()
    elif isinstance(ts, datetime):
        py = ts
    else:
        raise YahooFetchError(f"unexpected timestamp type: {type(ts)}")
    if py.tzinfo is None:
        py = py.replace(tzinfo=UTC)
    return py


# 종목별 in-flight Lock — 같은 code에 대한 동시 호출만 fold.
# 호출 끝나면 즉시 다음 호출 허용 (cooldown 없음 — 사용자 결정 2026-05-05).
_inflight_locks: dict[str, asyncio.Lock] = defaultdict(asyncio.Lock)


class YahooBackfillService:
    """Service entry — router에서 의존 주입."""

    def __init__(
        self,
        publisher: UserCandleEventPublisher,
        user_id: str = DEFAULT_USER_ID,
    ) -> None:
        self.publisher = publisher
        self.user_id = user_id

    async def run(self, req: BackfillRequest) -> BackfillResult:
        """BackfillRequest 한 건 처리.

        - 종목별 asyncio.Lock 획득 (동시 호출 fold)
        - yahoo_client.fetch_history (suffix fallback 포함)
        - DataFrame → CandleSnapshot list
        - UserCandleUpdatedEvent 1건으로 batch publish
        - 결과 통계 반환
        """
        lock = _inflight_locks[req.code]
        if lock.locked():
            log.info(
                "backfill.service.in-flight code=%s — fold 동시 호출 (현재 진행 중인 호출 끝날 때까지 대기)",
                req.code,
            )

        async with lock:
            return await self._do_run(req)

    async def _do_run(self, req: BackfillRequest) -> BackfillResult:
        log.info(
            "backfill.service.start code=%s interval=%s days=%d end=%s",
            req.code,
            req.interval,
            req.days,
            req.end.isoformat() if req.end else "None",
        )
        df = fetch_history(
            FetchRequest(
                code=req.code,
                interval=req.interval,
                days=req.days,
                end=req.end,
                kospi_suffix=req.kospi_suffix,
                kosdaq_fallback_suffix=req.kosdaq_fallback_suffix,
            )
        )
        snapshots = dataframe_to_snapshots(df)

        published = 0
        if snapshots:
            event = UserCandleUpdatedEvent(
                userId=self.user_id,
                code=req.code,
                interval=_yf_to_domain_interval(req.interval),
                candles=snapshots,
                occurredAt=datetime.now(tz=UTC),
            )
            await self.publisher.publish(event)
            published = len(snapshots)
            window = {
                "start": snapshots[0].openTime.isoformat(),
                "end": snapshots[-1].openTime.isoformat(),
            }
        else:
            window = {"start": None, "end": None}

        log.info(
            "backfill.service.done code=%s fetched=%d published=%d",
            req.code,
            len(snapshots),
            published,
        )
        return BackfillResult(
            code=req.code,
            interval=req.interval,
            fetched=len(snapshots),
            published=published,
            time_window=window,
        )


def _yf_to_domain_interval(yf_interval: str) -> str:
    """yfinance interval → Kotlin domain Interval enum 이름 변환.

    "1m" → "M1", "5m" → "M5", "60m" → "H1" 등.
    domain Interval enum: D1·M1·M5·M10·M15·M30·H1·WEEK·MONTH·M2·M3·...
    """
    return {
        "1m": "M1",
        "5m": "M5",
        "15m": "M15",
        "30m": "M30",
        "60m": "H1",
    }.get(yf_interval, yf_interval.upper())
