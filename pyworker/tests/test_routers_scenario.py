"""POST /screenshot/scenario — 통합 테스트.

ADR 0011 (2026-05-01 개정) 다단계 시나리오 endpoint.
"""
from __future__ import annotations

import pytest


@pytest.mark.asyncio
async def test_post_scenario_returns_step_results_and_presigned_urls(
    client, fake_scenario_runner_3pass
):
    """3단계 모두 PASS — presigned URL 3개 응답."""
    spec = """
async def scenario(page):
    await page.goto("about:blank")
    await page.screenshot(path="01-blank.png")
    await page.screenshot(path="02-still.png")
    await page.screenshot(path="03-final.png")
"""
    resp = await client.post(
        "/screenshot/scenario",
        json={
            "specCode": spec,
            "issueId": "ISSUE-008",
            "attempt": "before",
            "retry": 1,
            "viewport": {"width": 1440, "height": 900},
            "requestedBy": "test:case",
        },
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["issueId"] == "ISSUE-008"
    assert body["attempt"] == "before"
    assert body["retry"] == 1
    assert len(body["steps"]) == 3
    assert body["failedAtStep"] is None
    for s in body["steps"]:
        assert s["status"] == "pass"
        assert s["storageKey"].startswith("issues/ISSUE-008/before/")
        assert s["presignedUrl"].startswith("http://localhost:9000/")
    assert body["totalDurationMs"] >= 0


@pytest.mark.asyncio
async def test_post_scenario_marks_failed_step_with_fail_suffix(
    client, fake_scenario_runner_step2_fail
):
    """단계 2 fail → storageKey에 -FAIL 접미사 + error 보존."""
    resp = await client.post(
        "/screenshot/scenario",
        json={
            "specCode": "async def scenario(page):\n    pass",
            "issueId": "ISSUE-009",
            "attempt": "before",
            "retry": 1,
            "viewport": {"width": 1440, "height": 900},
        },
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["failedAtStep"] == 1
    assert body["steps"][0]["status"] == "pass"
    assert body["steps"][1]["status"] == "fail"
    assert body["steps"][1]["storageKey"].endswith("-FAIL.png")
    assert "TimeoutError" in body["steps"][1]["error"]


# NOTE: 503 unavailable 케이스는 lifespan이 항상 scenario_runner를 설정하므로
# 단위 테스트로 검증 어려움. 503 처리는 코드 보존(미래 의존성 주입 분리 시 활용).
