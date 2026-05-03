"""YahooBackfillService — DataFrame → Kafka publish 변환.

router.py가 호출. yahoo_client는 외부 yfinance만 다루고,
service는 정규화·publish·응답 모양을 책임.

흐름:
1) request 정규화 (interval alias, days 한도)
2) yahoo_client.fetch_history → DataFrame
3) tz_kst → CandleMinuteAggregatedEvent (분봉 1m·5m·15m·30m·60m만 지원, 1d는 §C 비범위)
4) CandleMinutePublisher → Kafka topic candle.minute.aggregated.v1
5) 결과 요약 응답
"""

from __future__ import annotations

import logging
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
    CandleMinuteAggregatedEvent,
)

log = logging.getLogger(__name__)


# Kafka topic + 1차 R1 지원 분봉 (1d는 비범위).
KAFKA_TOPIC_CANDLE_MINUTE = "candle.minute.aggregated.v1"
SUPPORTED_MINUTE_INTERVALS: set[str] = {"1m", "5m", "15m", "30m", "60m"}


@dataclass(frozen=True)
class BackfillRequest:
    """REST → service 사이 정규화된 요청.

    code: 6자리 한국 종목코드
    interval: 입력 alias 정규화된 yfinance 표준 ("1m"·"5m"·...)
    days: 거꾸로 fetch할 일수
    """

    code: str
    interval: str
    days: int
    kospi_suffix: str = ".KS"
    kosdaq_fallback_suffix: str = ".KQ"


@dataclass(frozen=True)
class BackfillResult:
    code: str
    interval: str
    fetched: int
    published: int
    time_window: dict[str, str | None]  # {start, end} ISO-8601 KST or None if 0 rows


class CandleMinutePublisher:
    """candle.minute.aggregated.v1 publish 래퍼.

    pyworker `_BasePublisher` 패턴 답습 (NewsRawPublisher 등과 동일).
    """

    def __init__(self, producer: KafkaProducer, topic: str = KAFKA_TOPIC_CANDLE_MINUTE) -> None:
        self.producer = producer
        self.topic = topic

    async def publish(self, event: CandleMinuteAggregatedEvent) -> None:
        # key는 code + openTime (Kotlin 측 멱등 INSERT를 위해 같은 partition 보장).
        await self.producer.publish(
            topic=self.topic,
            key=f"{event.code}:{event.openTime.isoformat()}",
            payload=event,
            type_id=TYPE_ID_HEADERS[CandleMinuteAggregatedEvent],
        )


def normalize_request(
    code: str,
    interval: str,
    days: int,
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
        kospi_suffix=kospi_suffix,
        kosdaq_fallback_suffix=kosdaq_fallback_suffix,
    )


def dataframe_to_events(df: pd.DataFrame, code: str) -> list[CandleMinuteAggregatedEvent]:
    """DataFrame → list[CandleMinuteAggregatedEvent].

    columns 가정: ["Open", "High", "Low", "Close", "Volume"]
    index: pd.DatetimeIndex (KST tz-aware 권장; service에서 tz 정규화 후 호출)

    Decimal 변환은 pandas Float → str → Decimal (binary float 정밀도 회피).
    None/NaN row는 skip.
    """
    if df.empty:
        return []
    df = to_kst_aware(df)

    events: list[CandleMinuteAggregatedEvent] = []
    for ts, row in df.iterrows():
        if any(pd.isna(row[c]) for c in ("Open", "High", "Low", "Close", "Volume")):
            continue
        # ts는 KST tz-aware. CandleMinuteAggregatedEvent.openTime은 Instant (UTC) 직렬화.
        # _to_iso_utc가 자동 변환하므로 그대로 datetime 전달 OK.
        events.append(
            CandleMinuteAggregatedEvent(
                code=code,
                openTime=_normalize_ts(ts),
                open=Decimal(str(float(row["Open"]))),
                high=Decimal(str(float(row["High"]))),
                low=Decimal(str(float(row["Low"]))),
                close=Decimal(str(float(row["Close"]))),
                volume=int(row["Volume"]) if not pd.isna(row["Volume"]) else 0,
                tradeCount=0,
            )
        )
    return events


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


class YahooBackfillService:
    """Service entry — router에서 의존 주입."""

    def __init__(self, publisher: CandleMinutePublisher) -> None:
        self.publisher = publisher

    async def run(self, req: BackfillRequest) -> BackfillResult:
        """BackfillRequest 한 건 처리.

        - yahoo_client.fetch_history (suffix fallback 포함)
        - DataFrame → events
        - 각 event를 Kafka publish (직렬, 한 종목 30K건도 single-loop 충분)
        - 결과 통계 반환
        """
        log.info(
            "backfill.service.start code=%s interval=%s days=%d",
            req.code,
            req.interval,
            req.days,
        )
        df = fetch_history(
            FetchRequest(
                code=req.code,
                interval=req.interval,
                days=req.days,
                kospi_suffix=req.kospi_suffix,
                kosdaq_fallback_suffix=req.kosdaq_fallback_suffix,
            )
        )
        events = dataframe_to_events(df, code=req.code)
        published = 0
        for ev in events:
            await self.publisher.publish(ev)
            published += 1

        if events:
            window = {
                "start": events[0].openTime.isoformat(),
                "end": events[-1].openTime.isoformat(),
            }
        else:
            window = {"start": None, "end": None}

        log.info(
            "backfill.service.done code=%s fetched=%d published=%d",
            req.code,
            len(events),
            published,
        )
        return BackfillResult(
            code=req.code,
            interval=req.interval,
            fetched=len(events),
            published=published,
            time_window=window,
        )
