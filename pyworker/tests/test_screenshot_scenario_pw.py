"""RealPlaywrightRunner — 실제 Playwright headless로 검증.

@pytest.mark.integration — Chromium browser 설치 필요. 빠른 단위 테스트와 분리.
"""
from __future__ import annotations

import pytest

from app.screenshot.scenario import ScenarioRequest, ScenarioRunner
from app.screenshot.scenario_pw import RealPlaywrightRunner


@pytest.mark.integration
@pytest.mark.asyncio
async def test_real_playwright_blank_page_capture():
    """about:blank로 1단계 캡처 — 외부 네트워크 의존 0."""
    spec = """
async def scenario(page):
    await page.goto("about:blank")
    await page.screenshot(path="01-blank.png")
"""
    runner = RealPlaywrightRunner()
    sr = ScenarioRunner(playwright=runner)
    req = ScenarioRequest(spec_code=spec, viewport_w=800, viewport_h=600)
    result = await sr.run(req)
    assert len(result.steps) == 1
    assert result.steps[0].status == "pass"
    assert result.steps[0].image_bytes.startswith(b"\x89PNG")
    assert result.steps[0].name == "01-blank"
    assert result.failed_at_step is None


@pytest.mark.integration
@pytest.mark.asyncio
async def test_real_playwright_marks_fail_on_runtime_error():
    """존재하지 않는 selector click → timeout → fail."""
    spec = """
async def scenario(page):
    await page.goto("about:blank")
    await page.screenshot(path="01-blank.png")
    await page.click("#never-exists", timeout=500)
    await page.screenshot(path="02-never-runs.png")
"""
    runner = RealPlaywrightRunner(default_timeout_ms=500)
    sr = ScenarioRunner(playwright=runner)
    req = ScenarioRequest(spec_code=spec, viewport_w=800, viewport_h=600)
    result = await sr.run(req)
    # 1단계 pass + 2단계는 합성 fail (02-error.png)
    assert result.steps[0].status == "pass"
    assert result.steps[0].name == "01-blank"
    assert result.failed_at_step == 1
    assert result.steps[1].status == "fail"
    assert "02-error" in result.steps[1].name
    assert result.steps[1].error is not None
    assert "timeout" in result.steps[1].error.lower() or "never-exists" in result.steps[1].error.lower()


def test_load_scenario_rejects_missing_function(tmp_path):
    """spec_code 안에 scenario 함수 없으면 ValueError."""
    from app.screenshot.scenario_pw import _load_scenario

    bad = tmp_path / "bad.py"
    bad.write_text("async def other_name(page): pass", encoding="utf-8")
    with pytest.raises(ValueError, match="scenario"):
        _load_scenario(bad)


def test_load_scenario_rejects_syntax_error(tmp_path):
    from app.screenshot.scenario_pw import _load_scenario

    bad = tmp_path / "bad.py"
    bad.write_text("async def scenario(page: invalid syntax", encoding="utf-8")
    with pytest.raises(ValueError, match="문법"):
        _load_scenario(bad)
