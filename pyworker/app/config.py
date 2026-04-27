"""Pydantic Settings — pyworker 환경 변수 로딩.

env vars (모두 optional, defaults는 dev/local 가정):
- KAFKA_BOOTSTRAP            기본 localhost:9092
- KAFKA_TOPIC_NEWS_RAW       기본 news.raw.v1
- KAFKA_TOPIC_DART           기본 dart.disclosure.v1
- KAFKA_TOPIC_SCREENSHOT     기본 screenshot.captured.v1
- S3_ENDPOINT                기본 http://localhost:9000  (MinIO)
- S3_ACCESS_KEY              기본 minioadmin
- S3_SECRET_KEY              기본 minioadmin
- S3_BUCKET_SCREENSHOT       기본 magicjar-screenshots
- S3_SECURE                  기본 false
- NAVER_NEWS_RSS_URL         기본 https://finance.naver.com/news/news_list.naver
- DART_API_KEY               기본 ""  (없으면 DART scraper는 no-op)
- SCRAPE_INTERVAL_NEWS_SEC   기본 300
- SCRAPE_INTERVAL_DART_SEC   기본 600
- PLAYWRIGHT_HEADLESS        기본 true
- PLAYWRIGHT_DEFAULT_TIMEOUT 기본 15000
"""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # Kafka
    kafka_bootstrap: str = Field(default="localhost:9092", alias="KAFKA_BOOTSTRAP")
    kafka_client_id: str = Field(default="magicjar-pyworker", alias="KAFKA_CLIENT_ID")
    kafka_topic_news_raw: str = Field(default="news.raw.v1", alias="KAFKA_TOPIC_NEWS_RAW")
    kafka_topic_dart: str = Field(default="dart.disclosure.v1", alias="KAFKA_TOPIC_DART")
    kafka_topic_screenshot: str = Field(
        default="screenshot.captured.v1", alias="KAFKA_TOPIC_SCREENSHOT"
    )

    # S3 / MinIO
    s3_endpoint: str = Field(default="localhost:9000", alias="S3_ENDPOINT")
    s3_access_key: str = Field(default="minioadmin", alias="S3_ACCESS_KEY")
    s3_secret_key: str = Field(default="minioadmin", alias="S3_SECRET_KEY")
    s3_bucket_screenshot: str = Field(
        default="magicjar-screenshots", alias="S3_BUCKET_SCREENSHOT"
    )
    s3_secure: bool = Field(default=False, alias="S3_SECURE")
    s3_region: str = Field(default="us-east-1", alias="S3_REGION")

    # Naver news
    naver_news_list_url: str = Field(
        default="https://finance.naver.com/news/news_list.naver",
        alias="NAVER_NEWS_LIST_URL",
    )
    naver_news_user_agent: str = Field(
        default=(
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        ),
        alias="NAVER_NEWS_USER_AGENT",
    )

    # DART
    dart_api_key: str = Field(default="", alias="DART_API_KEY")
    dart_base_url: str = Field(
        default="https://opendart.fss.or.kr/api", alias="DART_BASE_URL"
    )

    # Scheduler intervals (seconds)
    scrape_interval_news_sec: int = Field(default=300, alias="SCRAPE_INTERVAL_NEWS_SEC")
    scrape_interval_dart_sec: int = Field(default=600, alias="SCRAPE_INTERVAL_DART_SEC")
    scheduler_enabled: bool = Field(default=False, alias="SCHEDULER_ENABLED")

    # Playwright
    playwright_headless: bool = Field(default=True, alias="PLAYWRIGHT_HEADLESS")
    playwright_default_timeout_ms: int = Field(
        default=15_000, alias="PLAYWRIGHT_DEFAULT_TIMEOUT"
    )

    # Runtime mode — 테스트나 부팅 단계에서 외부 의존(Kafka·Playwright·S3)을 비활성화하기 위함
    bootstrap_external: bool = Field(default=False, alias="BOOTSTRAP_EXTERNAL")


_settings: Settings | None = None


def get_settings() -> Settings:
    """싱글톤 Settings 접근. 테스트는 dependency override로 교체 가능."""
    global _settings
    if _settings is None:
        _settings = Settings()
    return _settings


def reset_settings_for_test() -> None:
    """pytest fixture 안에서만 호출."""
    global _settings
    _settings = None
