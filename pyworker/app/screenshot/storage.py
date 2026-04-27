"""MinIO/S3 업로드 — minio-py.

`MinioStorage` 가 실 구현, `InMemoryStorage` 는 테스트용.
"""

from __future__ import annotations

import io
import logging
from dataclasses import dataclass
from typing import Protocol
from uuid import UUID

log = logging.getLogger(__name__)


@dataclass
class StoreResult:
    key: str
    public_url: str | None
    size: int


class StorageBackend(Protocol):
    async def put(
        self, key: str, data: bytes, content_type: str = "image/png"
    ) -> StoreResult: ...

    async def ensure_bucket(self) -> None: ...


class MinioStorage:
    """minio-py 동기 클라이언트 + asyncio.to_thread 비동기 wrap."""

    def __init__(
        self,
        endpoint: str,
        access_key: str,
        secret_key: str,
        bucket: str,
        secure: bool = False,
        region: str = "us-east-1",
    ) -> None:
        from minio import Minio  # 지연 import

        self._client = Minio(
            endpoint=endpoint,
            access_key=access_key,
            secret_key=secret_key,
            secure=secure,
            region=region,
        )
        self.bucket = bucket
        self.secure = secure
        self.endpoint = endpoint

    async def ensure_bucket(self) -> None:
        import asyncio

        def _run() -> None:
            if not self._client.bucket_exists(self.bucket):
                self._client.make_bucket(self.bucket)

        await asyncio.to_thread(_run)

    async def put(
        self, key: str, data: bytes, content_type: str = "image/png"
    ) -> StoreResult:
        import asyncio

        def _run() -> int:
            buf = io.BytesIO(data)
            self._client.put_object(
                self.bucket,
                key,
                data=buf,
                length=len(data),
                content_type=content_type,
            )
            return len(data)

        size = await asyncio.to_thread(_run)
        scheme = "https" if self.secure else "http"
        public_url = f"{scheme}://{self.endpoint}/{self.bucket}/{key}"
        return StoreResult(key=key, public_url=public_url, size=size)


class InMemoryStorage:
    """테스트용 — dict에 저장."""

    def __init__(self, bucket: str = "test-bucket") -> None:
        self.bucket = bucket
        self.objects: dict[str, bytes] = {}

    async def ensure_bucket(self) -> None:
        return None

    async def put(
        self, key: str, data: bytes, content_type: str = "image/png"
    ) -> StoreResult:
        self.objects[key] = data
        return StoreResult(
            key=key, public_url=f"memory://{self.bucket}/{key}", size=len(data)
        )


def screenshot_key(snapshot_id: UUID, ext: str = "png") -> str:
    return f"screenshots/{snapshot_id}.{ext}"
