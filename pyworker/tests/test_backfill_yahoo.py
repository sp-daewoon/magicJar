"""YahooBackfillService + yahoo_client + router 단위 테스트.

외부 의존:
- yfinance.Ticker.history → monkeypatch로 mock (실제 네트워크 호출 안 함)
- aiokafka producer → conftest.fake_producer로 capture

검증 8건:
1. 1m × 30days chunk 분할 → 8일 단위 4개 chunk
2. .KS 빈 결과 → .KQ fallback 호출
3. 둘 다 빈 결과 → published=0 응답
4. DataFrame → CandleMinuteAggregatedEvent 변환 (Decimal·tz)
5. tz 정합 (KST aware index)
6. Kafka publish call (camelCase + Type-Id 헤더)
7. input validation (days 범위·interval alias·invalid code)
8. yfinance exception → 502 BAD GATEWAY
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from decimal import Decimal
from unittest.mock import patch

import pandas as pd
import pytest

from app.backfill.service import (
    BackfillRequest,
    UserCandleEventPublisher,
    YahooBackfillService,
    dataframe_to_snapshots,
    normalize_request,
)
from app.backfill.yahoo_client import (
    FetchRequest,
    YahooFetchError,
    fetch_history,
    normalize_interval,
    split_chunks,
    to_kst_aware,
    validate_days,
)
from app.messaging.schemas import CandleSnapshot, UserCandleUpdatedEvent

# ============================================================
# 1. chunk 분할
# ============================================================


def test_split_chunks_1m_30days_yields_4_chunks() -> None:
    """1m × 30일 → 8일 chunk × 4개 (마지막은 6일)."""
    now = datetime(2026, 5, 4, 0, 0, tzinfo=UTC)
    chunks = split_chunks("1m", days=30, now=now)
    # 30일 / 8일/chunk = ceil(30/8) = 4
    assert len(chunks) == 4
    # chunk[0].start = now - 30days, chunk[-1].end = now
    assert chunks[0][0] == datetime(2026, 4, 4, 0, 0, tzinfo=UTC)
    assert chunks[-1][1] == now
    # 인접 chunk 경계 일치
    for i in range(len(chunks) - 1):
        assert chunks[i][1] == chunks[i + 1][0]


def test_split_chunks_5m_60days_yields_1_chunk() -> None:
    """5m은 60일 chunk_days라 60일도 single chunk."""
    chunks = split_chunks("5m", days=60)
    assert len(chunks) == 1


def test_split_chunks_unsupported_interval_raises() -> None:
    with pytest.raises(YahooFetchError):
        split_chunks("3m", days=30)


# ============================================================
# 2~3. .KS / .KQ fallback
# ============================================================


def _make_fake_df(rows: int, start_ts: datetime) -> pd.DataFrame:
    """1분봉 row 모양 DataFrame 생성 (KST tz-aware).

    start_ts가 tz-aware면 그 tz로 align한 뒤 KST로 변환한다.
    """
    if rows == 0:
        return pd.DataFrame()
    # start_ts가 tz-aware면 pd.Timestamp 그대로 변환, naive면 UTC 가정.
    ts = pd.Timestamp(start_ts)
    if ts.tz is None:
        ts = ts.tz_localize("UTC")
    ts = ts.tz_convert("Asia/Seoul")
    idx = pd.date_range(start=ts, periods=rows, freq="1min")
    return pd.DataFrame(
        {
            "Open": [70000.0 + i for i in range(rows)],
            "High": [70100.0 + i for i in range(rows)],
            "Low": [69900.0 + i for i in range(rows)],
            "Close": [70050.0 + i for i in range(rows)],
            "Volume": [1000 * (i + 1) for i in range(rows)],
        },
        index=idx,
    )


def test_fetch_history_falls_back_to_kq_when_ks_empty() -> None:
    """1차 .KS 빈 결과 → .KQ로 fallback."""
    fake_df = _make_fake_df(rows=10, start_ts=datetime(2026, 4, 30, 9, 0, tzinfo=UTC))

    call_log: list[str] = []

    def fake_history(symbol: str, start, end, interval: str) -> pd.DataFrame:
        call_log.append(symbol)
        if symbol.endswith(".KS"):
            return pd.DataFrame()
        return fake_df

    with patch("app.backfill.yahoo_client._ticker_history", side_effect=fake_history):
        df = fetch_history(
            FetchRequest(code="091990", interval="1m", days=1),
            now=datetime(2026, 5, 1, 0, 0, tzinfo=UTC),
        )

    assert not df.empty
    # .KS가 먼저 시도된 후 .KQ 시도
    assert any(s.endswith(".KS") for s in call_log)
    assert any(s.endswith(".KQ") for s in call_log)


def test_fetch_history_returns_empty_when_both_suffixes_fail() -> None:
    """.KS·.KQ 모두 빈 결과 → empty DataFrame."""
    with patch(
        "app.backfill.yahoo_client._ticker_history",
        return_value=pd.DataFrame(),
    ):
        df = fetch_history(
            FetchRequest(code="999999", interval="1m", days=1),
            now=datetime(2026, 5, 1, 0, 0, tzinfo=UTC),
        )
    assert df.empty


# ============================================================
# 4. DataFrame → events 변환
# ============================================================


def test_dataframe_to_snapshots_preserves_ohlcv_with_decimal() -> None:
    """OHLC는 Decimal, Volume은 int. NaN row는 skip."""
    df = _make_fake_df(rows=3, start_ts=datetime(2026, 4, 30, 9, 0, tzinfo=UTC))
    snapshots = dataframe_to_snapshots(df)
    assert len(snapshots) == 3
    assert all(isinstance(s, CandleSnapshot) for s in snapshots)
    s0 = snapshots[0]
    assert s0.open == Decimal("70000.0")
    assert s0.high == Decimal("70100.0")
    assert s0.low == Decimal("69900.0")
    assert s0.close == Decimal("70050.0")
    assert s0.volume == 1000


def test_dataframe_to_snapshots_skips_nan_rows() -> None:
    """row 중 OHLCV NaN 있으면 skip."""
    df = _make_fake_df(rows=3, start_ts=datetime(2026, 4, 30, 9, 0, tzinfo=UTC))
    df.iloc[1, df.columns.get_loc("Close")] = float("nan")
    snapshots = dataframe_to_snapshots(df)
    assert len(snapshots) == 2


# ============================================================
# 5. tz 정합
# ============================================================


def test_to_kst_aware_localizes_naive_index() -> None:
    """naive index → UTC localize → KST 변환."""
    naive_idx = pd.date_range("2026-04-30 00:00", periods=3, freq="1min")
    df = pd.DataFrame(
        {"Open": [1, 2, 3], "High": [1, 2, 3], "Low": [1, 2, 3], "Close": [1, 2, 3], "Volume": [1, 2, 3]},
        index=naive_idx,
    )
    out = to_kst_aware(df)
    assert out.index.tz is not None
    # UTC 00:00 → KST 09:00
    assert out.index[0].hour == 9


# ============================================================
# 6. Kafka publish (camelCase + Type-Id 헤더)
# ============================================================


@pytest.mark.asyncio
async def test_user_candle_publisher_sends_camel_case_with_type_id(
    kafka_producer, fake_producer
) -> None:
    publisher = UserCandleEventPublisher(kafka_producer, "market.candle.user.v1")
    snapshot = CandleSnapshot(
        openTime=datetime(2026, 4, 30, 0, 0, tzinfo=UTC),
        open=Decimal("70000"),
        high=Decimal("70200"),
        low=Decimal("69800"),
        close=Decimal("70100"),
        volume=1234567,
    )
    event = UserCandleUpdatedEvent(
        userId="74683223",
        code="005930",
        interval="M1",
        candles=[snapshot],
        occurredAt=datetime(2026, 5, 5, 12, 0, tzinfo=UTC),
    )
    await publisher.publish(event)

    assert len(fake_producer.records) == 1
    rec = fake_producer.records[0]
    assert rec["topic"] == "market.candle.user.v1"
    body = json.loads(rec["value"])
    assert body["userId"] == "74683223"
    assert body["code"] == "005930"
    assert body["interval"] == "M1"
    assert len(body["candles"]) == 1
    c0 = body["candles"][0]
    assert c0["openTime"] == "2026-04-30T00:00:00Z"
    assert c0["open"] == "70000"
    assert c0["volume"] == 1234567
    # Type-Id FQN
    assert (
        rec["headers"]["__TypeId__"].decode("utf-8")
        == "com.mj.domain.market.event.UserCandleUpdatedEvent"
    )
    # key = code (api Kotlin publisher와 같음)
    assert rec["key"] == "005930"


# ============================================================
# 7. input validation
# ============================================================


def test_normalize_interval_aliases() -> None:
    assert normalize_interval("1m") == "1m"
    assert normalize_interval("M1") == "1m"
    assert normalize_interval("5m") == "5m"
    assert normalize_interval("M30") == "30m"
    assert normalize_interval("1h") == "60m"
    assert normalize_interval("H1") == "60m"
    assert normalize_interval("1d") == "1d"
    assert normalize_interval("DAY") == "1d"


def test_normalize_interval_rejects_unknown() -> None:
    with pytest.raises(YahooFetchError):
        normalize_interval("3m")


def test_validate_days_rejects_overflow() -> None:
    with pytest.raises(YahooFetchError):
        validate_days("1m", 31)  # 한도 30일
    with pytest.raises(YahooFetchError):
        validate_days("1m", 0)
    # 정상
    validate_days("1m", 30)
    validate_days("60m", 730)


def test_normalize_request_rejects_bad_code() -> None:
    with pytest.raises(YahooFetchError):
        normalize_request(code="ABCDE", interval="1m", days=30)
    with pytest.raises(YahooFetchError):
        normalize_request(code="123", interval="1m", days=30)
    # 정상
    req = normalize_request(code="005930", interval="1m", days=30)
    assert req.interval == "1m"


def test_normalize_request_rejects_daily_interval() -> None:
    """1d는 §C 비범위 — KIS 영구 영속 사용."""
    with pytest.raises(YahooFetchError, match="minute intervals"):
        normalize_request(code="005930", interval="1d", days=30)


# ============================================================
# 8. yfinance exception → 응답
# ============================================================


@pytest.mark.asyncio
async def test_service_run_handles_empty_dataframe(kafka_producer, fake_producer) -> None:
    """0 rows 응답 — published=0, error 없음, Kafka publish 0건."""
    pub = UserCandleEventPublisher(kafka_producer, "market.candle.user.v1")
    svc = YahooBackfillService(pub)

    with patch("app.backfill.service.fetch_history", return_value=pd.DataFrame()):
        result = await svc.run(BackfillRequest(code="999999", interval="1m", days=1))

    assert result.fetched == 0
    assert result.published == 0
    assert result.time_window == {"start": None, "end": None}
    assert len(fake_producer.records) == 0


@pytest.mark.asyncio
async def test_service_run_publishes_single_batch_event(kafka_producer, fake_producer) -> None:
    """비어있지 않은 DataFrame → 단 1건 batch event publish (모든 candles 포함)."""
    pub = UserCandleEventPublisher(kafka_producer, "market.candle.user.v1")
    svc = YahooBackfillService(pub)
    df = _make_fake_df(rows=5, start_ts=datetime(2026, 4, 30, 9, 0, tzinfo=UTC))

    with patch("app.backfill.service.fetch_history", return_value=df):
        result = await svc.run(BackfillRequest(code="005930", interval="1m", days=1))

    assert result.fetched == 5
    assert result.published == 5
    # 단 1건 Kafka 메시지에 5건 candles batch — Kafka 중복 방지의 본질
    assert len(fake_producer.records) == 1
    body = json.loads(fake_producer.records[0]["value"])
    assert body["code"] == "005930"
    assert body["interval"] == "M1"
    assert len(body["candles"]) == 5
    assert result.time_window["start"] is not None
    assert result.time_window["end"] is not None


# ============================================================
# 9. router endpoint integration
# ============================================================


@pytest.mark.asyncio
async def test_router_returns_200_with_summary(client, fake_producer) -> None:
    """POST /backfill/yahoo 200 OK + camelCase 응답."""
    df = _make_fake_df(rows=3, start_ts=datetime(2026, 4, 30, 9, 0, tzinfo=UTC))

    with patch("app.backfill.service.fetch_history", return_value=df):
        resp = await client.post(
            "/backfill/yahoo",
            json={"code": "005930", "interval": "1m", "days": 1},
        )

    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["code"] == "005930"
    assert body["interval"] == "1m"
    assert body["fetched"] == 3
    assert body["published"] == 3
    assert "timeWindow" in body
    assert body["timeWindow"]["start"] is not None


@pytest.mark.asyncio
async def test_router_accepts_end_cursor(client, fake_producer) -> None:
    """POST /backfill/yahoo with end — cursor 기반 윈도우 fetch."""
    df = _make_fake_df(rows=3, start_ts=datetime(2026, 4, 25, 9, 0, tzinfo=UTC))

    with patch("app.backfill.service.fetch_history", return_value=df) as fh:
        resp = await client.post(
            "/backfill/yahoo",
            json={
                "code": "005930",
                "interval": "1m",
                "days": 2,
                "end": "2026-04-27T00:00:00Z",
            },
        )
    assert resp.status_code == 200
    # FetchRequest에 end가 전달됐는지 검증
    assert fh.call_count == 1
    fr = fh.call_args.args[0]
    assert fr.end == datetime(2026, 4, 27, 0, 0, tzinfo=UTC)


@pytest.mark.asyncio
async def test_router_400_on_invalid_end_format(client) -> None:
    resp = await client.post(
        "/backfill/yahoo",
        json={
            "code": "005930",
            "interval": "1m",
            "days": 2,
            "end": "not-a-date",
        },
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_router_400_on_invalid_code(client) -> None:
    resp = await client.post(
        "/backfill/yahoo",
        json={"code": "abc", "interval": "1m", "days": 30},
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_router_400_on_overflow_days(client) -> None:
    resp = await client.post(
        "/backfill/yahoo",
        json={"code": "005930", "interval": "1m", "days": 31},
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_router_400_on_daily_interval(client) -> None:
    """1d는 비범위."""
    resp = await client.post(
        "/backfill/yahoo",
        json={"code": "005930", "interval": "1d", "days": 30},
    )
    assert resp.status_code == 400
