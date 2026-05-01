"""storage.put + presigned URL 발급 — ADR 0011 (2026-05-02 ISSUE-008 hotfix)."""
from __future__ import annotations

import pytest


@pytest.mark.asyncio
async def test_put_returns_presigned_url_with_public_host(storage_with_public_host):
    """storage.put이 MINIO_PUBLIC_HOST 기반 URL을 응답에 포함."""
    result = await storage_with_public_host.put(
        "issues/ISSUE-008/before/2026-05-01T15-00-00Z/01-test.png",
        b"fake png bytes",
        "image/png",
    )
    assert result.key == "issues/ISSUE-008/before/2026-05-01T15-00-00Z/01-test.png"
    assert result.public_url is not None
    assert result.public_url.startswith("http://localhost:9000/")
    assert "issues/ISSUE-008" in result.public_url


@pytest.mark.asyncio
async def test_put_uses_https_when_secure(storage_with_public_host_secure):
    """secure=true 면 https 스킴."""
    result = await storage_with_public_host_secure.put(
        "issues/ISSUE-009/before/2026-05-01T15-00-00Z/01-test.png",
        b"fake",
        "image/png",
    )
    assert result.public_url.startswith("https://localhost:9000/")


# ---------------------------------------------------------------------------
# ISSUE-008 hotfix (ADR 0011, 2026-05-02) —
# MinioStorage 는 internal(업로드)·public(presign) 두 클라이언트를 분리해서
# AWS4 서명 자체가 외부 호스트 기준으로 만들어지도록 한다.
# ---------------------------------------------------------------------------


class _FakeMinio:
    """minio.Minio 의 가짜 — 어떤 endpoint 로 만들어졌는지 추적하고
    put_object/presigned_get_object 호출을 기록한다."""

    instances: list["_FakeMinio"] = []

    def __init__(self, endpoint, access_key, secret_key, secure, region) -> None:
        self.endpoint = endpoint
        self.access_key = access_key
        self.secret_key = secret_key
        self.secure = secure
        self.region = region
        self.put_calls: list[tuple] = []
        self.presign_calls: list[tuple] = []
        self.bucket_exists_calls: list[str] = []
        self.make_bucket_calls: list[str] = []
        _FakeMinio.instances.append(self)

    def bucket_exists(self, bucket: str) -> bool:
        self.bucket_exists_calls.append(bucket)
        return True

    def make_bucket(self, bucket: str) -> None:
        self.make_bucket_calls.append(bucket)

    def put_object(self, bucket, key, data, length, content_type):
        self.put_calls.append((bucket, key, length, content_type))

    def presigned_get_object(self, bucket, key, expires):
        self.presign_calls.append((bucket, key, expires))
        scheme = "https" if self.secure else "http"
        # 실 minio-py는 endpoint 기반 URL을 반환 — 그대로 모사.
        return f"{scheme}://{self.endpoint}/{bucket}/{key}?X-Amz-Signature=fake"


@pytest.fixture
def fake_minio(monkeypatch):
    _FakeMinio.instances.clear()
    monkeypatch.setattr("minio.Minio", _FakeMinio)
    return _FakeMinio


@pytest.mark.asyncio
async def test_minio_storage_uses_public_endpoint_for_presign(fake_minio):
    """ISSUE-008: presigned_get_object 는 public 클라이언트(외부 endpoint)에서
    호출되어야 한다. 그래야 서명 호스트와 사용자 GET 호스트가 일치한다."""
    from app.screenshot.storage import MinioStorage

    storage = MinioStorage(
        endpoint="minio:9000",
        public_endpoint="localhost:9000",
        access_key="ak",
        secret_key="sk",
        bucket="bk",
        secure=False,
    )
    result = await storage.put("k.png", b"data", "image/png")

    # 두 클라이언트 분리 — internal 업로드용 / public presign용
    assert len(fake_minio.instances) == 2
    internal, public = fake_minio.instances
    assert internal.endpoint == "minio:9000"
    assert public.endpoint == "localhost:9000"

    # 업로드는 internal, presign은 public에서만
    assert internal.put_calls == [("bk", "k.png", 4, "image/png")]
    assert public.put_calls == []
    assert internal.presign_calls == []
    assert len(public.presign_calls) == 1

    # 결과 URL은 public endpoint 기반 — _rewrite_host hack 없이 그대로 사용 가능
    assert result.public_url is not None
    assert result.public_url.startswith("http://localhost:9000/")
    assert "X-Amz-Signature" in result.public_url


@pytest.mark.asyncio
async def test_minio_storage_ensure_bucket_uses_internal_client(fake_minio):
    """ensure_bucket 은 컨테이너 내부 endpoint(internal client)에서 동작해야 한다."""
    from app.screenshot.storage import MinioStorage

    storage = MinioStorage(
        endpoint="minio:9000",
        public_endpoint="localhost:9000",
        access_key="ak",
        secret_key="sk",
        bucket="bk",
        secure=False,
    )
    await storage.ensure_bucket()

    internal, public = fake_minio.instances
    assert internal.bucket_exists_calls == ["bk"]
    assert public.bucket_exists_calls == []
