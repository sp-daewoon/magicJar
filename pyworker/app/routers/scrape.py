"""POST /scrape/{source} — 수동 스크래핑 트리거.

source 값:
- naver  → 네이버 금융 뉴스 list 1회 fetch + Kafka publish
- dart   → DART 공시 list 1회 fetch + Kafka publish
"""

from __future__ import annotations

import logging
from typing import Annotated, Literal

import httpx
from fastapi import APIRouter, Depends, HTTPException, Request

from app.config import Settings, get_settings
from app.messaging.kafka_producer import (
    DartDisclosurePublisher,
    NewsRawPublisher,
)
from app.scrapers.dart import fetch_dart_disclosures
from app.scrapers.naver_news import fetch_naver_news

log = logging.getLogger(__name__)

router = APIRouter(prefix="/scrape", tags=["scrape"])

ScrapeSource = Literal["naver", "dart"]


def _news_publisher(request: Request) -> NewsRawPublisher:
    pub = getattr(request.app.state, "news_raw_publisher", None)
    if pub is None:
        raise HTTPException(status_code=503, detail="news_raw_publisher unavailable")
    return pub


def _dart_publisher(request: Request) -> DartDisclosurePublisher:
    pub = getattr(request.app.state, "dart_publisher", None)
    if pub is None:
        raise HTTPException(status_code=503, detail="dart_publisher unavailable")
    return pub


def _http_client(request: Request) -> httpx.AsyncClient:
    client = getattr(request.app.state, "http_client", None)
    if client is None:
        raise HTTPException(status_code=503, detail="http_client unavailable")
    return client


@router.post("/{source}", summary="수동 스크래핑 트리거")
async def trigger_scrape(
    source: ScrapeSource,
    settings: Annotated[Settings, Depends(get_settings)],
    news_pub: Annotated[NewsRawPublisher, Depends(_news_publisher)],
    dart_pub: Annotated[DartDisclosurePublisher, Depends(_dart_publisher)],
    client: Annotated[httpx.AsyncClient, Depends(_http_client)],
) -> dict[str, object]:
    if source == "naver":
        events = await fetch_naver_news(
            client, settings.naver_news_list_url, settings.naver_news_user_agent
        )
        for ev in events:
            await news_pub.publish(ev)
        return {"source": source, "published": len(events)}
    if source == "dart":
        events = await fetch_dart_disclosures(
            client, settings.dart_base_url, settings.dart_api_key
        )
        for ev in events:
            await dart_pub.publish(ev)
        return {"source": source, "published": len(events)}
    raise HTTPException(status_code=404, detail=f"unknown source: {source}")
