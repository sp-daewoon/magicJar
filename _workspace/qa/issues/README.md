# QA 이슈 디렉토리 — 양식 가이드 (ISSUE-008~)

각 이슈는 `ISSUE-NNN/` 디렉토리 한 개로 표현된다.

## 디렉토리 구조

```
ISSUE-008/
├─ card.md                      # frontmatter + 증상·재현·라이프사이클
├─ scenario-before.py           # qa-run.mjs scenario --attempt before 시 자동 보존
├─ scenario-after.py            # (있는 경우)
├─ report-before.md             # 단계별 결과 + presigned URL 목록
└─ report-after.md              # (있는 경우)
```

## card.md frontmatter

| 필드 | 의미 |
|---|---|
| id | ISSUE-NNN |
| status | OPEN / INVESTIGATING / FIXED / WONTFIX / DEFERRED |
| reportedAt | 사용자 보고 시각 (ISO) |
| reportedBy | 보고자 (user / agent / system) |
| symptom | 증상 한 줄 |
| target | URL 또는 화면명 |
| relatedAdr | 참조 ADR 배열 (예: [0011, 0022]) |
| relatedPr | 수정 PR (예: #50) |
| fixedAt | FIXED 진입 시각 (ISO) |

## 라이프사이클

```
OPEN → INVESTIGATING → FIXED
                    ↘ WONTFIX
                    ↘ DEFERRED
```

- OPEN: `qa-run.mjs new` 직후
- INVESTIGATING: `qa-run.mjs scenario --attempt before` 후 (디펙 확정 전 캡처 단계)
- FIXED: `qa-run.mjs close --pr <N>` 후
- WONTFIX: `qa-run.mjs wontfix --reason "..."` 후

## MinIO 키 패턴

```
magicjar-screenshots/issues/ISSUE-NNN/{before|after}/{ts}/NN-step-name.png
```

캡처 자체는 git이 아닌 MinIO에 영속. presigned URL(24h)은 `report-{attempt}.md`에 인용.

## 시나리오 코드 (`scenario-{before|after}.py`)

`async def scenario(page):` 단일 함수 — Python async, in-process Playwright 실행.

예:
```python
async def scenario(page):
    await page.goto("http://frontend:80/symbol/005930")
    await page.screenshot(path="01-goto-symbol-page.png")
    await page.click("[data-testid=interval-D]")
    await page.screenshot(path="02-after-click.png")
    await page.wait_for_timeout(500)
    await page.screenshot(path="03-after-wait.png")
```

## 관련 ADR

- ADR 0011 (개정 2026-05-01) — QA 스크린샷 게이트, 다단계 시나리오
- ADR 0016 (보강 2026-05-01) — On-Demand 시나리오 모드 트리거
- ADR 0030 — 오케스트레이터=architect 일체화 메타 규약
- spec: `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md`
- plan: `docs/superpowers/plans/2026-05-01-qa-defect-loop.md`
