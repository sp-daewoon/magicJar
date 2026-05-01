"""MinIO/S3 업로드 + presigned URL 발급 — minio-py.

`MinioStorage` 가 실 구현, `InMemoryStorage` 는 테스트용.

ADR 0011 (2026-05-01 개정) — 사용자 노출 정식 경로는 presigned URL.
docker network 내부 endpoint(minio:9000)와 host 접근 endpoint(localhost:9000)가
다를 수 있으므로 `public_host` 인자로 사용자 접근 가능한 호스트를 명시.
"""

from __future__ import annotations

import io
import logging
from dataclasses import dataclass
from datetime import timedelta
from typing import Protocol
from urllib.parse import urlparse, urlunparse
from uuid import UUID

log = logging.getLogger(__name__)

# Plan §6.1 — presigned URL TTL 24h
PRESIGNED_TTL = timedelta(hours=24)


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
    """minio-py 동기 클라이언트 + asyncio.to_thread 비동기 wrap.

    presigned_get_object로 24h TTL URL 발급 후, MINIO_PUBLIC_HOST로 호스트 치환.
    이유: 컨테이너 내부 endpoint(minio:9000)는 사용자 브라우저에서 접근 불가.
    """

    def __init__(
        self,
        endpoint: str,
        access_key: str,
        secret_key: str,
        bucket: str,
        secure: bool = False,
        region: str = "us-east-1",
        public_host: str | None = None,
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
        self.public_host = public_host or endpoint

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

        def _put() -> int:
            buf = io.BytesIO(data)
            self._client.put_object(
                self.bucket,
                key,
                data=buf,
                length=len(data),
                content_type=content_type,
            )
            return len(data)

        size = await asyncio.to_thread(_put)

        # ADR 0011 — presigned GET URL 발급 (TTL 24h)
        def _presign() -> str:
            return self._client.presigned_get_object(
                self.bucket, key, expires=PRESIGNED_TTL
            )

        signed = await asyncio.to_thread(_presign)
        public_url = _rewrite_host(signed, self.public_host, self.secure)
        return StoreResult(key=key, public_url=public_url, size=size)


def _rewrite_host(url: str, public_host: str, secure: bool) -> str:
    """presigned URL의 호스트를 사용자 접근 가능한 host로 치환.

    예: http://minio:9000/bucket/key?X-Amz-...  →  http://localhost:9000/bucket/key?X-Amz-...
    경로·쿼리(서명)는 그대로 유지.
    """
    parsed = urlparse(url)
    scheme = "https" if secure else "http"
    return urlunparse(parsed._replace(netloc=public_host, scheme=scheme))


class InMemoryStorage:
    """테스트용 — dict에 저장. presigned URL은 단순 host 기반 URL로 모사."""

    def __init__(
        self,
        bucket: str = "test-bucket",
        public_host: str | None = None,
        secure: bool = False,
    ) -> None:
        self.bucket = bucket
        self.objects: dict[str, bytes] = {}
        self.public_host = public_host
        self.secure = secure

    async def ensure_bucket(self) -> None:
        return None

    async def put(
        self, key: str, data: bytes, content_type: str = "image/png"
    ) -> StoreResult:
        self.objects[key] = data
        if self.public_host:
            scheme = "https" if self.secure else "http"
            public_url = f"{scheme}://{self.public_host}/{self.bucket}/{key}"
        else:
            public_url = f"memory://{self.bucket}/{key}"
        return StoreResult(key=key, public_url=public_url, size=len(data))


def screenshot_key(snapshot_id: UUID, ext: str = "png") -> str:
    return f"screenshots/{snapshot_id}.{ext}"
