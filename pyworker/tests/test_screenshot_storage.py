"""storage.put + presigned URL 발급 — ADR 0011 (2026-05-01 개정)."""
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
