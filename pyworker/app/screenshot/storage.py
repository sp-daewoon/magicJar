"""MinIO/S3 업로드 + presigned URL 발급 — minio-py.

`MinioStorage` 가 실 구현, `InMemoryStorage` 는 테스트용.

ADR 0011 (2026-05-02 ISSUE-008 hotfix) —
컨테이너 내부 endpoint(minio:9000)와 사용자 접근 endpoint(localhost:9000)에 대해
**두 개의 minio-py 클라이언트**를 운영한다.
- internal: 업로드/bucket ensure (실 네트워크 호출, 빠른 사설망 경유)
- public:   presigned_get_object 만 호출 (네트워크 0, 외부 호스트 기반 서명 생성)

이전(2026-05-01) 구현은 단일 클라이언트로 presigned URL 발급 후 호스트만 치환했으나,
AWS4-HMAC-SHA256 서명에 호스트가 포함되어 사용자 GET 시 SignatureDoesNotMatch 발생.
"""

from __future__ import annotations

import io
import logging
from dataclasses import dataclass
from datetime import timedelta
from typing import Protocol
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

    internal 클라이언트는 컨테이너 내부 endpoint 로 업로드/bucket 검사를 수행하고,
    public 클라이언트는 외부 endpoint 기준으로 presigned URL을 만든다.
    public 쪽은 presigned_get_object 만 호출하므로 실제 네트워크 도달 가능 여부와 무관
    — 서명 생성에는 endpoint 문자열만 사용된다.
    """

    def __init__(
        self,
        endpoint: str,
        access_key: str,
        secret_key: str,
        bucket: str,
        secure: bool = False,
        region: str = "us-east-1",
        public_endpoint: str | None = None,
    ) -> None:
        from minio import Minio  # 지연 import

        self._internal = Minio(
            endpoint=endpoint,
            access_key=access_key,
            secret_key=secret_key,
            secure=secure,
            region=region,
        )
        # public_endpoint 미지정 시 internal 과 동일 — 단일 클라이언트와 같은 동작
        public_ep = public_endpoint or endpoint
        self._public = Minio(
            endpoint=public_ep,
            access_key=access_key,
            secret_key=secret_key,
            secure=secure,
            region=region,
        )
        self.bucket = bucket
        self.secure = secure
        self.endpoint = endpoint
        self.public_endpoint = public_ep

    async def ensure_bucket(self) -> None:
        import asyncio

        def _run() -> None:
            if not self._internal.bucket_exists(self.bucket):
                self._internal.make_bucket(self.bucket)

        await asyncio.to_thread(_run)

    async def put(
        self, key: str, data: bytes, content_type: str = "image/png"
    ) -> StoreResult:
        import asyncio

        def _put() -> int:
            buf = io.BytesIO(data)
            self._internal.put_object(
                self.bucket,
                key,
                data=buf,
                length=len(data),
                content_type=content_type,
            )
            return len(data)

        size = await asyncio.to_thread(_put)

        def _presign() -> str:
            return self._public.presigned_get_object(
                self.bucket, key, expires=PRESIGNED_TTL
            )

        public_url = await asyncio.to_thread(_presign)
        return StoreResult(key=key, public_url=public_url, size=size)


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
