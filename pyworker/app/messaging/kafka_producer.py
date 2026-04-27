"""aiokafka AIOKafkaProducer 래퍼 + 토픽별 publisher.

3 publisher class 제공 (plan §Phase 3-A):
- NewsRawPublisher
- DartDisclosurePublisher
- ScreenshotCapturedPublisher

각 publisher는 동일 KafkaProducer 인스턴스를 공유하며 토픽·schema 만 다르다.
Spring Kafka JsonDeserializer 호환을 위해 `__TypeId__` 헤더를 함께 보낸다.
"""

from __future__ import annotations

import json
import logging
from typing import Protocol

from aiokafka import AIOKafkaProducer
from pydantic import BaseModel

from app.messaging.schemas import (
    TYPE_ID_HEADERS,
    DartDisclosureEvent,
    NewsRawEvent,
    ScreenshotCapturedEvent,
)

log = logging.getLogger(__name__)


class _AsyncSendable(Protocol):
    async def send_and_wait(
        self, topic: str, value: bytes, key: bytes | None = None, headers: list | None = None
    ) -> object: ...


class KafkaProducer:
    """aiokafka 래퍼.

    `start()` / `stop()` 은 FastAPI lifespan에서 호출.
    Bootstrap 비활성(`BOOTSTRAP_EXTERNAL=false`)이면 실제 connect 안 함 (테스트·로컬 개발용).
    """

    def __init__(self, bootstrap_servers: str, client_id: str, enabled: bool = True) -> None:
        self.bootstrap_servers = bootstrap_servers
        self.client_id = client_id
        self.enabled = enabled
        self._producer: _AsyncSendable | None = None
        self._started = False

    async def start(self) -> None:
        if not self.enabled:
            log.info("KafkaProducer disabled (BOOTSTRAP_EXTERNAL=false) — skipping connect")
            return
        if self._started:
            return
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self.bootstrap_servers,
            client_id=self.client_id,
            acks="all",
            enable_idempotence=True,
        )
        await self._producer.start()  # type: ignore[union-attr]
        self._started = True
        log.info("KafkaProducer started (bootstrap=%s)", self.bootstrap_servers)

    async def stop(self) -> None:
        if self._producer is not None and self._started:
            await self._producer.stop()  # type: ignore[union-attr]
            log.info("KafkaProducer stopped")
        self._started = False
        self._producer = None

    async def publish(
        self, topic: str, key: str, payload: BaseModel, type_id: str | None = None
    ) -> None:
        body = payload.model_dump_json(by_alias=True).encode("utf-8")
        headers = []
        if type_id:
            headers.append(("__TypeId__", type_id.encode("utf-8")))
        if not self.enabled or self._producer is None:
            log.debug(
                "KafkaProducer (disabled) would publish topic=%s key=%s body=%s",
                topic,
                key,
                body[:200],
            )
            return
        await self._producer.send_and_wait(
            topic, value=body, key=key.encode("utf-8"), headers=headers
        )
        log.debug("KafkaProducer published topic=%s key=%s bytes=%d", topic, key, len(body))

    # Test injection (used by tests)
    def _inject_for_test(self, fake_producer: _AsyncSendable) -> None:
        self._producer = fake_producer
        self._started = True
        self.enabled = True


class _BasePublisher:
    def __init__(self, producer: KafkaProducer, topic: str) -> None:
        self.producer = producer
        self.topic = topic


class NewsRawPublisher(_BasePublisher):
    async def publish(self, event: NewsRawEvent) -> None:
        await self.producer.publish(
            topic=self.topic,
            key=str(event.newsId),
            payload=event,
            type_id=TYPE_ID_HEADERS[NewsRawEvent],
        )


class DartDisclosurePublisher(_BasePublisher):
    async def publish(self, event: DartDisclosureEvent) -> None:
        await self.producer.publish(
            topic=self.topic,
            key=str(event.disclosureId),
            payload=event,
            type_id=TYPE_ID_HEADERS[DartDisclosureEvent],
        )


class ScreenshotCapturedPublisher(_BasePublisher):
    async def publish(self, event: ScreenshotCapturedEvent) -> None:
        await self.producer.publish(
            topic=self.topic,
            key=str(event.snapshotId),
            payload=event,
            type_id=TYPE_ID_HEADERS[ScreenshotCapturedEvent],
        )


def serialize_for_test(payload: BaseModel) -> dict:
    """test helper — Kotlin domain class에 던질 JSON 모양을 dict로 추출."""
    return json.loads(payload.model_dump_json(by_alias=True))
