"""yfinance 추상화 레이어.

책임 (단일):
- yfinance.Ticker 호출 + chunk 분할 + .KS/.KQ fallback + tz 정규화
- 외부에는 pandas DataFrame 또는 list[dict] (OHLCV) 반환
- Kafka publish는 service.py 책임 (분리)

interval 한도 (yfinance 1.3.0 실측, ADR 0048 §C·architect PoC):
- 1m  : 30일 (단 single request 7~8일 한도 → chunk 분할 필수)
- 5m  : 60일
- 15m : 60일
- 30m : 60일
- 60m : 730일 (~2년)
- 1d  : 10년+

한국 종목 suffix:
- KOSPI·KOSDAQ 모두 `.KS` 시도 → 빈 결과면 `.KQ` fallback (실측 둘 다 `.KS` 작동)

tz: yfinance가 자동 Asia/Seoul tz 부여 (KOSPI/KOSDAQ exchange 기준)
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

import pandas as pd
import yfinance as yf

log = logging.getLogger(__name__)


# === interval 정규화 매트릭스 ===
# yfinance가 받는 표준 interval string과 우리 입력 alias 매핑.
INTERVAL_ALIAS: dict[str, str] = {
    "1m": "1m",
    "M1": "1m",
    "5m": "5m",
    "M5": "5m",
    "15m": "15m",
    "M15": "15m",
    "30m": "30m",
    "M30": "30m",
    "60m": "60m",
    "1h": "60m",  # yfinance는 60m 표준, 1h alias 허용 안 함 → 우리가 변환
    "H1": "60m",
    "1d": "1d",
    "D1": "1d",
    "DAY": "1d",
}

# interval 별 days 한도 (yfinance per-interval restriction).
INTERVAL_MAX_DAYS: dict[str, int] = {
    "1m": 30,
    "5m": 60,
    "15m": 60,
    "30m": 60,
    "60m": 730,
    "1d": 3650,  # 10년
}

# single yfinance.history(start=, end=) 호출당 chunk 최대 일수.
# 1m은 yfinance 측에서 7~8일 한도라 8일로 잡고 안전 마진 확보.
INTERVAL_CHUNK_DAYS: dict[str, int] = {
    "1m": 8,
    "5m": 60,
    "15m": 60,
    "30m": 60,
    "60m": 730,
    "1d": 3650,
}


class YahooFetchError(Exception):
    """yfinance 호출 실패·결과 정규화 실패 raised."""


@dataclass(frozen=True)
class FetchRequest:
    """정규화된 fetch 요청."""

    code: str  # 6자리 한국 종목코드 (e.g. "005930")
    interval: str  # yfinance 표준 (e.g. "1m", "1d")
    days: int  # 거꾸로 fetch할 일수 (end - days ~ end)
    end: datetime | None = None  # cursor — 미지정 시 now() (UTC). 지정 시 그 시점 기준.
    kospi_suffix: str = ".KS"
    kosdaq_fallback_suffix: str = ".KQ"


def normalize_interval(raw: str) -> str:
    """입력 interval alias → yfinance 표준 string.

    raises YahooFetchError if alias 매핑 실패.
    """
    if raw in INTERVAL_ALIAS:
        return INTERVAL_ALIAS[raw]
    raise YahooFetchError(
        f"unsupported interval={raw} (allowed: {sorted(set(INTERVAL_ALIAS.keys()))})"
    )


def validate_days(interval: str, days: int) -> None:
    """yfinance per-interval 한도 검증.

    raises YahooFetchError if days > 한도.
    """
    if days <= 0:
        raise YahooFetchError(f"days must be positive (got {days})")
    max_days = INTERVAL_MAX_DAYS.get(interval)
    if max_days is None:
        raise YahooFetchError(f"interval={interval} not supported")
    if days > max_days:
        raise YahooFetchError(
            f"days={days} exceeds interval={interval} limit={max_days}"
        )


def _interval_seconds(interval: str) -> int:
    """interval string → 초 변환 (검증용)."""
    return {
        "1m": 60,
        "5m": 300,
        "15m": 900,
        "30m": 1800,
        "60m": 3600,
        "1d": 86400,
    }.get(interval, 60)


def _is_interval_consistent(df: pd.DataFrame, expected_interval: str) -> bool:
    """yfinance 응답이 요청 interval과 시간 간격이 일치하는지 검증.

    yfinance가 잘못된 시장 suffix(.KS / .KQ)로 호출돼도 응답을 줄 때가 있음 — 이 경우
    응답의 시간 간격이 일봉 형태로 나옴. 검증 후 일치 안 하면 fallback 시도하도록 false 반환.

    검증 방식:
      - 첫 두 row의 시간 차이를 expected와 비교
      - 차이 > expected * 5 이면 mismatch (예: 1m 요청에 1d 응답)
      - row 1개 이하면 검증 불가 — true (그대로 진행)
    """
    if len(df) < 2:
        return True
    try:
        diff_seconds = (df.index[1] - df.index[0]).total_seconds()
    except Exception:  # noqa: BLE001
        return True
    expected = _interval_seconds(expected_interval)
    return abs(diff_seconds) <= expected * 5


def split_chunks(interval: str, days: int, now: datetime | None = None) -> list[tuple[datetime, datetime]]:
    """now ~ now-days 구간을 chunk_days 단위로 분할.

    yfinance.history(start=, end=) 의 [start, end) 반-개구간 의미를 따름.

    e.g. 1m × 30days → 8일 chunk × 4개 (마지막은 6일).

    return: [(start, end)] (UTC datetime, 가까운 시점 → 먼 시점 순서가 아니라
            아예 chronological — chunk[0]가 가장 이른 시작)
    """
    chunk_days = INTERVAL_CHUNK_DAYS.get(interval)
    if chunk_days is None:
        raise YahooFetchError(f"interval={interval} not supported")
    end = now if now is not None else datetime.now(tz=UTC)
    start = end - timedelta(days=days)

    chunks: list[tuple[datetime, datetime]] = []
    cursor = start
    while cursor < end:
        chunk_end = min(cursor + timedelta(days=chunk_days), end)
        chunks.append((cursor, chunk_end))
        cursor = chunk_end
    return chunks


def _ticker_history(
    symbol: str, start: datetime, end: datetime, interval: str
) -> pd.DataFrame:
    """yfinance Ticker.history wrapper — 외부 의존을 한 곳에 격리해 mock 가능하게."""
    ticker = yf.Ticker(symbol)
    return ticker.history(
        start=start,
        end=end,
        interval=interval,
        auto_adjust=False,
        actions=False,
    )


def fetch_history(req: FetchRequest, now: datetime | None = None) -> pd.DataFrame:
    """단일 종목·interval·기간 → DataFrame.

    1) `{code}.KS` 시도. 빈 결과면 → `.KQ` fallback. 둘 다 빈 결과 → empty DataFrame 반환.
    2) chunk 분할 후 각 chunk concat.
    3) 결과 DataFrame index는 KST tz-aware DatetimeIndex.
    4) columns: ["Open", "High", "Low", "Close", "Volume"] 보장.

    `req.end` 지정 시 그 시점 이전 days만큼 fetch (cursor 기반).
    `req.end` 미지정 시 `now` (test injection) 또는 datetime.now(UTC) 기준.

    raises YahooFetchError on yfinance exception (외부 네트워크 실패 등).
    """
    end_for_chunks = req.end if req.end is not None else now
    chunks = split_chunks(req.interval, req.days, now=end_for_chunks)

    primary_symbol = f"{req.code}{req.kospi_suffix}"
    fallback_symbol = f"{req.code}{req.kosdaq_fallback_suffix}"

    # 1차 시도
    try:
        df_primary = _concat_chunks(primary_symbol, chunks, req.interval)
    except Exception as ex:  # noqa: BLE001
        log.warning(
            "yahoo_client: primary fetch failed code=%s symbol=%s err=%s",
            req.code,
            primary_symbol,
            ex,
        )
        df_primary = pd.DataFrame()

    # 검증 — 응답 row의 시간 간격이 요청 interval과 매칭되는지.
    # yfinance가 잘못된 시장 suffix로 호출돼도 일봉 형태 19행 응답하는 케이스 차단
    # (사용자 보고 2026-05-04: 에코플라스틱 038110.KS — 19행 일봉 형태 응답 → fallback 안 됨).
    if not df_primary.empty and _is_interval_consistent(df_primary, req.interval):
        log.info(
            "yahoo_client: fetched code=%s symbol=%s rows=%d",
            req.code,
            primary_symbol,
            len(df_primary),
        )
        return df_primary
    if not df_primary.empty:
        log.warning(
            "yahoo_client: primary interval mismatch code=%s symbol=%s rows=%d → fallback 시도",
            req.code,
            primary_symbol,
            len(df_primary),
        )

    # 2차 시도: fallback symbol
    log.info(
        "yahoo_client: primary empty/mismatch, trying fallback code=%s symbol=%s",
        req.code,
        fallback_symbol,
    )
    try:
        df_fallback = _concat_chunks(fallback_symbol, chunks, req.interval)
    except Exception as ex:  # noqa: BLE001
        log.warning(
            "yahoo_client: fallback fetch failed code=%s symbol=%s err=%s",
            req.code,
            fallback_symbol,
            ex,
        )
        return pd.DataFrame()

    if not df_fallback.empty:
        log.info(
            "yahoo_client: fetched (fallback) code=%s symbol=%s rows=%d",
            req.code,
            fallback_symbol,
            len(df_fallback),
        )
    else:
        log.warning(
            "yahoo_client: both .KS and .KQ empty code=%s",
            req.code,
        )
    return df_fallback


def _concat_chunks(
    symbol: str, chunks: list[tuple[datetime, datetime]], interval: str
) -> pd.DataFrame:
    """chunk 별 history 호출 → concat. 빈 chunk는 skip."""
    frames: list[pd.DataFrame] = []
    for start, end in chunks:
        df = _ticker_history(symbol, start, end, interval)
        if df is not None and not df.empty:
            frames.append(df)
    if not frames:
        return pd.DataFrame()
    out = pd.concat(frames)
    # chunk 경계에서 발생할 수 있는 중복 index 제거.
    out = out[~out.index.duplicated(keep="first")].sort_index()
    return out


def to_kst_aware(df: pd.DataFrame) -> pd.DataFrame:
    """index를 Asia/Seoul tz-aware로 보장.

    yfinance가 이미 Asia/Seoul (또는 KOSPI exchange tz) 으로 부여하지만,
    naive index가 들어오면 UTC 가정 후 Asia/Seoul로 변환.
    """
    if df.empty:
        return df
    idx = df.index
    if not isinstance(idx, pd.DatetimeIndex):
        return df
    if idx.tz is None:
        df = df.tz_localize("UTC").tz_convert("Asia/Seoul")
    else:
        df = df.tz_convert("Asia/Seoul")
    return df
