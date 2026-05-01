"""ScenarioRunner — specCode 받아 Playwright 단계별 실행 + 단계별 PNG 추출.

ADR 0011 (2026-05-01 개정) 다단계 시나리오 모드 — 캡처만, 디펙 판정은 사용자.
"""
from __future__ import annotations

import pytest

from app.screenshot.scenario import ScenarioRequest, ScenarioRunner


@pytest.mark.asyncio
async def test_runner_executes_all_steps_when_no_error(fake_playwright):
    """모든 단계 PASS — steps[].status="pass", failedAtStep=None."""
    spec = """
import { test } from '@playwright/test';
test('demo', async ({ page }) => {
  await page.goto('http://example.com');
  await page.screenshot({ path: '01-goto.png' });
  await page.screenshot({ path: '02-final.png' });
});
"""
    req = ScenarioRequest(spec_code=spec, viewport_w=1440, viewport_h=900)
    runner = ScenarioRunner(playwright=fake_playwright)
    result = await runner.run(req)
    assert len(result.steps) == 2
    assert all(s.status == "pass" for s in result.steps)
    assert result.failed_at_step is None
    assert result.steps[0].name == "01-goto"
    assert result.steps[0].image_bytes
    assert result.total_duration_ms > 0


@pytest.mark.asyncio
async def test_runner_marks_failed_step_on_runtime_error(fake_playwright_failing_at_step_2):
    """단계 2 fail — selector not found."""
    spec = "test('demo', async ({page}) => { /* ... */ });"
    req = ScenarioRequest(spec_code=spec, viewport_w=1440, viewport_h=900)
    runner = ScenarioRunner(playwright=fake_playwright_failing_at_step_2)
    result = await runner.run(req)
    assert result.steps[0].status == "pass"
    assert result.steps[1].status == "fail"
    assert "selector" in (result.steps[1].error or "").lower()
    assert result.failed_at_step == 1
