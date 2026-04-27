"""Naver news 스크래퍼 — list HTML 파싱.

실제 네이버 호출은 없음 — 고정 HTML 픽스처로 selector 안정성 검증.
"""

from __future__ import annotations

from app.scrapers.naver_news import _make_news_id, parse_naver_news_list

_FIXTURE_HTML = """
<html><body>
  <ul class="newsList">
    <li>
      <a class="tit" href="/news/news_read.naver?article_id=001">삼성전자, 2분기 호실적 발표</a>
      <p class="articleSummary">매출 전년 동기 대비 12% 증가</p>
      <span class="wdate">2026-04-27 09:30</span>
    </li>
    <li>
      <a class="tit" href="/news/news_read.naver?article_id=002">SK하이닉스 HBM3 양산 임박</a>
      <p class="articleSummary">고성능 메모리 수요 폭증</p>
      <span class="wdate">2026-04-27 10:15</span>
    </li>
  </ul>
</body></html>
"""


def test_parse_naver_news_list_extracts_two_items() -> None:
    events = parse_naver_news_list(_FIXTURE_HTML)
    assert len(events) == 2
    assert events[0].title == "삼성전자, 2분기 호실적 발표"
    assert events[1].title == "SK하이닉스 HBM3 양산 임박"
    assert events[0].source == "naver"


def test_parse_naver_news_list_deterministic_uuid() -> None:
    events = parse_naver_news_list(_FIXTURE_HTML)
    expected = _make_news_id("/news/news_read.naver?article_id=001")
    assert events[0].newsId == expected


def test_parse_naver_news_list_empty_html_returns_empty_list() -> None:
    assert parse_naver_news_list("<html><body></body></html>") == []
