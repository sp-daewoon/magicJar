"""Scrapers — 한국 금융 뉴스·공시 수집.

각 scraper는 외부 호출(httpx)·HTML 파싱(beautifulsoup) 단계만 담당하고,
Kafka 발행은 caller (router or scheduler)에서 한다.
"""
