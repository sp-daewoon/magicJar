"""네이버 금융 뉴스 스크래퍼 — httpx + BeautifulSoup.

P3 골격 단계:
- list page만 파싱 (개별 기사 본문은 P4+에서 follow)
- title·publishedAt·source·url 추출
- newsId는 url 기반 deterministic UUID5 (재발행 시 dedupe)
- instruments는 빈 리스트로 시작 (분류는 consumer-app 측 SentimentAnalyzer가 함)

네이버 페이지 구조 변동에 대비해 selector를 config 클래스로 분리.
"""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone
from typing import Any

import httpx
from bs4 import BeautifulSoup

from app.messaging.schemas import NewsRawEvent

log = logging.getLogger(__name__)


# 네이버 금융 뉴스 list page에서 deterministic UUID 생성용 namespace.
_NEWS_UUID_NAMESPACE = uuid.UUID("4d9b1cda-1f3b-5b6a-9a90-1b2c3d4e5f60")


def _make_news_id(url: str) -> uuid.UUID:
    return uuid.uuid5(_NEWS_UUID_NAMESPACE, url)


def _parse_published(text: str) -> datetime:
    """`2026-04-27 13:42` 같은 문자열을 UTC datetime으로 변환.

    실패하면 현재 시각 반환 (best-effort).
    """
    text = text.strip()
    for fmt in ("%Y-%m-%d %H:%M", "%Y.%m.%d %H:%M", "%Y-%m-%d", "%Y.%m.%d"):
        try:
            naive = datetime.strptime(text, fmt)
            # KST → UTC (-9h). 여기서는 KST 가정 (네이버 출력은 KST).
            kst_offset = 9 * 3600
            return datetime.fromtimestamp(
                naive.timestamp() - kst_offset, tz=timezone.utc
            )
        except ValueError:
            continue
    return datetime.now(tz=timezone.utc)


def parse_naver_news_list(html: str, source_name: str = "naver") -> list[NewsRawEvent]:
    """네이버 뉴스 list HTML → NewsRawEvent 리스트.

    selector는 가장 안정적인 것을 시도하되 변동에 대비해 fallback도 가짐.
    """
    soup = BeautifulSoup(html, "lxml")
    events: list[NewsRawEvent] = []

    # 네이버 finance news_list: <ul class="newsList"><li> ... <a class="tit">제목</a> ...
    items: list[Any] = soup.select("ul.newsList li") or soup.select("li.articleSubject")
    for item in items:
        anchor = item.select_one("a.tit") or item.select_one("a")
        if anchor is None:
            continue
        title = (anchor.get_text() or "").strip()
        href = anchor.get("href") or ""
        if not title or not href:
            continue
        date_node = item.select_one(".wdate") or item.select_one(".date")
        published = _parse_published(date_node.get_text()) if date_node else datetime.now(
            tz=timezone.utc
        )
        snippet_node = item.select_one(".articleSummary") or item.select_one("p")
        body = (snippet_node.get_text().strip() if snippet_node else "")
        events.append(
            NewsRawEvent(
                newsId=_make_news_id(href),
                source=source_name,
                publishedAt=published,
                title=title,
                body=body or title,
                instruments=[],
            )
        )
    log.debug("parse_naver_news_list extracted=%d", len(events))
    return events


async def fetch_naver_news(
    client: httpx.AsyncClient, list_url: str, user_agent: str
) -> list[NewsRawEvent]:
    """네이버 뉴스 list 페이지 가져와 파싱."""
    headers = {"User-Agent": user_agent}
    resp = await client.get(list_url, headers=headers, timeout=10.0)
    resp.raise_for_status()
    return parse_naver_news_list(resp.text)
