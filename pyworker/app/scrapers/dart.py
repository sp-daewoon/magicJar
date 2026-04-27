"""DART 공시 스크래퍼.

opendart-py 패키지 의존을 피하고 직접 httpx로 OpenDART API 호출 (의존성 가벼움).
- API: https://opendart.fss.or.kr/api/list.json
- API key 미설정시 no-op (warning 로깅 후 빈 리스트)
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from typing import Any

import httpx

from app.messaging.schemas import DartDisclosureEvent

log = logging.getLogger(__name__)

_DART_UUID_NAMESPACE = uuid.UUID("8e1c2d3e-4f5a-6b7c-8d9e-0f1a2b3c4d5e")


def _make_disclosure_id(rcept_no: str) -> uuid.UUID:
    return uuid.uuid5(_DART_UUID_NAMESPACE, rcept_no)


def _parse_filed_at(rcept_dt: str) -> datetime:
    """DART rcept_dt: `20260427` (YYYYMMDD) → UTC midnight."""
    try:
        naive = datetime.strptime(rcept_dt, "%Y%m%d")
        kst_offset = 9 * 3600
        return datetime.fromtimestamp(naive.timestamp() - kst_offset, tz=timezone.utc)
    except ValueError:
        return datetime.now(tz=timezone.utc)


def parse_dart_response(payload: dict[str, Any]) -> list[DartDisclosureEvent]:
    """DART list.json 응답 → DartDisclosureEvent 리스트.

    spec 응답 형식: { "status": "000", "list": [ {rcept_no, corp_code, corp_name, report_nm, rcept_dt, ...}, ... ] }
    """
    status = payload.get("status")
    if status != "000":
        log.info("DART response non-success status=%s message=%s", status, payload.get("message"))
        return []
    items = payload.get("list", []) or []
    events: list[DartDisclosureEvent] = []
    for it in items:
        rcept_no = str(it.get("rcept_no") or "")
        if not rcept_no:
            continue
        events.append(
            DartDisclosureEvent(
                disclosureId=_make_disclosure_id(rcept_no),
                code=str(it.get("corp_code") or ""),
                companyName=str(it.get("corp_name") or ""),
                disclosureType=str(it.get("report_nm") or ""),
                title=str(it.get("report_nm") or ""),
                filedAt=_parse_filed_at(str(it.get("rcept_dt") or "")),
                url=f"https://dart.fss.or.kr/dsaf001/main.do?rcpNo={rcept_no}",
            )
        )
    return events


async def fetch_dart_disclosures(
    client: httpx.AsyncClient,
    base_url: str,
    api_key: str,
    page_count: int = 50,
) -> list[DartDisclosureEvent]:
    if not api_key:
        log.warning("DART_API_KEY not set — skipping DART fetch (no-op)")
        return []
    params = {
        "crtfc_key": api_key,
        "page_no": 1,
        "page_count": page_count,
    }
    resp = await client.get(f"{base_url}/list.json", params=params, timeout=10.0)
    resp.raise_for_status()
    return parse_dart_response(resp.json())
