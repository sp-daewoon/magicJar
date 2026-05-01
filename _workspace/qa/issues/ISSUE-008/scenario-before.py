"""ISSUE-008 dogfooding 시나리오 — 종목 상세 차트 표시 검증.

ADR 0011 (2026-05-01 개정) 다단계 시나리오 모드 첫 적용.

단계:
1. /symbol/005930 진입 + networkidle 대기 → 캡처
2. 차트 렌더 추가 대기(1.5s) → 캡처
3. 봉 단위 'D' 토글 시도 → 캡처

사용자 판정:
- 차트(캔들·볼륨)가 02·03에서 표시 → dogfooding 성공 (qa-run.mjs wontfix)
- 빈 화면 / 에러 → 진짜 디펙 (수정 dispatch + after 사이클)
"""


async def scenario(page):
    # 1) 페이지 진입
    await page.goto("http://frontend:80/symbol/005930")
    await page.wait_for_load_state("networkidle")
    await page.screenshot(path="01-after-load.png")

    # 2) 차트 추가 렌더 대기
    await page.wait_for_timeout(1500)
    await page.screenshot(path="02-chart-rendered.png")

    # 3) 봉 단위 'D' 토글 시도 — 셀렉터 부재 시 timeout 후 합성 fail (인프라 검증 의도)
    try:
        await page.click("button:has-text('D')", timeout=2000)
        await page.wait_for_timeout(500)
        await page.screenshot(path="03-after-toggle-d.png")
    except Exception:
        # 셀렉터 없으면 OK — 차트만 잘 보이면 dogfooding 성공
        await page.screenshot(path="03-no-d-button.png")
