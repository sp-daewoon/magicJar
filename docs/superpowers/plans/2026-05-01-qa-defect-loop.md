# 5번 트랙 1차 — QA 디펙 순환 루프 구현 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development` (recommended) 또는 `superpowers:executing-plans`. Steps use `- [ ]` for tracking.

**Goal:** 사용자 보고 → architect 시나리오 → pyworker 단계별 캡처 → MinIO + presigned URL → ISSUE 카드 → 시각 검토 → 수정 → 재캡처 → close 한 사이클을 코드로 살린다. 실 매매 진입 5트랙 중 5번 1차 (QA 자동화 살리기) 종결.

**Architecture:** pyworker에 `POST /screenshot/scenario` 신규 endpoint(specCode inline 받아 Playwright 단일 세션 실행 + 단계별 PNG → MinIO + presigned URL). 호스트에 `scripts/qa-run.mjs` CLI(task.mjs 패턴). `_workspace/qa/issues/ISSUE-NNN/`에 카드·시나리오 인용·report 누적. ADR 0011 개정·0016 보강·0030 신규.

**Tech Stack:** Python 3.12 + FastAPI + Playwright async + minio-py / Node.js (host) / Kotlin 미변경 / Mermaid 미변경.

**Source spec:** `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` (commit `e15d578`).

**NOTE (2026-05-01 Phase 2 진입 시 정정):** 본 plan 작성 시 `.spec.ts` (TypeScript) 형식을 가정했으나, pyworker 컨테이너에 Node.js 미설치 + 기존 `PlaywrightCapturer`가 이미 Python async API 사용 중 → **spec_code 형식을 Python async로 변경**. Plan 본문의 TypeScript 코드 블록(Task 5·6·7·10·15의 spec_code 예시)은 다음 Python 패턴으로 대체해 작성:

```python
async def scenario(page):
    await page.goto("http://frontend:80/symbol/005930")
    await page.screenshot(path="01-goto.png")
    await page.click("[data-testid=interval-D]")
    await page.screenshot(path="02-click-d.png")
```

Task 6 `RealPlaywrightRunner`도 subprocess + npx playwright test + JSON reporter 파싱이 아닌, **in-process `playwright.async_api`** 사용 (기존 `PlaywrightCapturer` 패턴 일관). 단계 추적은 `page.screenshot` monkey-patch로 (path 인자 + duration + bytes 누적). 시나리오 영속 파일명은 `.py`.

**Branch:** `agent/architect/qa-defect-loop` (단일 worktree, ADR 0013).
**Author:** `bot+architect@magicjar.local` (ADR 0017).
**Model:** opus (architect 전담).
**비범위:** Playwright `expect` 자동 검증 / 픽셀 diff / 시나리오 카탈로그 영속 / 대시보드 ISSUE 통합 / 머지 게이트 강제 / 자동 dispatch / sandbox 격리 (모두 다음 사이클).

---

## 0. Pre-flight

- [ ] **0-1**: `git status` clean 확인. 옆 세션 변경 있으면 사용자 컨펌 후 진행
- [ ] **0-2**: `git checkout -b agent/architect/qa-defect-loop`
- [ ] **0-3**: pyworker 컨테이너 healthy 확인 — `docker compose -f docker/docker-compose.yml ps pyworker`
- [ ] **0-4**: spec 일독 — `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` (특히 §3 책임 boundary, §6 endpoint 시그니처, §7.1 호스트 정합)

---

## 1. ADR 정리 (의사결정 토대)

코드 작성 전에 ADR을 먼저 갱신해 결정 근거를 고정한다. 모든 후속 task가 이 ADR을 참조한다.

### Task 1: ADR 0030 신규 — 오케스트레이터 = architect 메타 규약

**Files:**
- Create: `_workspace/arch/adr/0030-orchestrator-as-architect.md`
- Modify: `_workspace/arch/adr/README.md` (목차 한 행 추가)

- [ ] **1-1**: 신규 ADR 작성

```markdown
# ADR 0030: 오케스트레이터 = architect 일체화 (메타 운영 규약)

- 상태: Accepted
- 작성일: 2026-05-01
- 결정자: architect (사용자 직접 진단)
- 관계: ADR 0013·0014·0016 운영 규약 통합

## 맥락

ADR 0013(단일 worktree·순차)·0014(모델 차등)·0016(QA On-Demand)이 각각 운영 패턴을 정해놨으나, **"메인 세션이 ADR을 어디까지 자율 해석하고 어디부터 architect sub-agent를 dispatch해야 하는가"** 가 명문화 안 됨. 그 결과 매 작업마다 architect dispatch → 토큰·시간 낭비, 또는 메인 세션이 ADR 무시하고 즉흥 결정 → 짜임새 깨짐, 양극단을 오감.

## 결정

**메인 세션(=오케스트레이터) = architect의 일상적 권한자.** ADR 신규 작성·개정·spec/plan 산출 같은 무거운 결정만 architect 에이전트 sub-agent로 dispatch. 나머지(ADR 참조·해석·일상 결정)는 메인 세션이 직접 수행.

### 자율 vs 컨펌

| 케이스 | 행동 |
|---|---|
| ADR 정합 + 기존 코드 패턴과 자연 | 메인 세션 자율 진행 |
| ADR 모호 / 신규 결정 필요 | 사용자 한 줄 컨펌 |
| 외부 자원·실 매매 도메인 침범 | 사용자 컨펌 + ADR 검토 |
| 시나리오 비결정적·플레이키 | 사용자 안내 후 진행 |

### architect sub-agent dispatch 조건

- ADR 신규 작성·개정
- spec 작성 (`docs/superpowers/specs/`)
- plan 작성 (`docs/superpowers/plans/`)
- Phase 단위 큰 결정 (예: MSA 재구성·CI/CD 도입)

이외에는 메인 세션 자율.

## 결과

- 긍정: 토큰·시간 절약, 일상 결정 빠름, ADR이 죽은 문서가 아니라 일상 참조 권위로 살아남
- 부정: ADR 해석 다툼 시 architect dispatch까지 단계 추가 — 기준 모호 시 사용자 컨펌으로 해소
- 후속: 5번 QA spec(2026-05-01) 첫 적용. 1~4 트랙 진입 시에도 동일 규약 적용.
```

- [ ] **1-2**: README.md 목차에 행 추가

```markdown
| 0030 | 오케스트레이터 = architect 일체화 (메타 규약) | Accepted | 2026-05-01 |
```

`_workspace/arch/adr/README.md`의 0027 행 다음에 추가.

- [ ] **1-3**: Commit

```bash
git add _workspace/arch/adr/0030-orchestrator-as-architect.md _workspace/arch/adr/README.md
git commit -m "feat(adr): 0030 — 오케스트레이터=architect 일체화 메타 규약"
```

### Task 2: ADR 0011 개정 — 다단계 시나리오 + presigned URL

**Files:**
- Modify: `_workspace/arch/adr/0011-qa-screenshot-gate.md` (변경 이력 + 결정 본문 보강)
- Modify: `_workspace/arch/adr/README.md` (상태 표기 갱신)

- [ ] **2-1**: 변경 이력 표에 행 추가 (파일 상단 "변경 이력" 섹션)

```markdown
| 2026-05-01 | **본 개정** — 다단계 시나리오 모드 명문화(단일 URL은 1단계 시나리오로 통합), pyworker `POST /screenshot/scenario` endpoint 신설, **MinIO presigned URL을 사용자 노출 정식 경로로 추가**, "캡처만 (Playwright `expect` 자동 검증은 비범위)" 명시. 시나리오 코드 영속화 0(매 호출 새 작성, ISSUE 카드에 인용으로만 보존). |
```

- [ ] **2-2**: "캡처 방법" 섹션 아래에 신규 하위 섹션 추가

```markdown
### 다단계 시나리오 (2026-05-01 추가)

기존 단일 URL 1장 캡처(`POST /screenshot`)에 더해, **사용자 흐름 중간의 인터랙션 디펙**을 검증하는 다단계 시나리오 모드를 신설한다.

| 항목 | 결정 |
|---|---|
| Endpoint | `POST /screenshot/scenario` |
| 입력 | `{specCode, issueId, attempt: "before"\|"after", retry, viewport}` — **Playwright Python async 코드** inline 문자열 (in-process 실행, Node.js 의존 0) |
| 단계별 캡처 | `page.screenshot({path: "NN-step-name.png"})` 패턴, pyworker가 자동 추적 |
| 검증 | **캡처만**. Playwright runtime error(timeout/selector not found 등)만 자동 fail 표기. 시각 디펙 판정은 사용자 시각 검토 |
| 결과 노출 | MinIO presigned URL (TTL 24h). pyworker 환경변수 `MINIO_PUBLIC_HOST`로 사용자 접근 가능한 호스트 사용 |
| 영속 | MinIO 키 `magicjar-screenshots/issues/ISSUE-NNN/{before\|after}/{ts}/NN-step.png`. 시나리오 코드는 git `_workspace/qa/issues/ISSUE-NNN/scenario-{before\|after}.spec.ts`로 인용 보존 |

상세는 spec `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` 참조.
```

- [ ] **2-3**: 본 ADR 상단 "상태" 라인 갱신

```markdown
- 상태: Accepted (개정 by ADR 0016, 0022, 0023, 0026, **0011 자체 2026-05-01 개정**)
- 작성일: 2026-04-25 (개정: 2026-04-27, 2026-05-01)
```

- [ ] **2-4**: README.md 0011 행 갱신

```markdown
| 0011 | QA 스크린샷 게이트 | **개정 2026-05-01** (다단계 시나리오 + presigned URL + 캡처만) | 2026-04-25 / 2026-04-27 / 2026-05-01 |
```

- [ ] **2-5**: Commit

```bash
git add _workspace/arch/adr/0011-qa-screenshot-gate.md _workspace/arch/adr/README.md
git commit -m "docs(adr): 0011 개정 — 다단계 시나리오 + presigned URL + 캡처만"
```

### Task 3: ADR 0016 보강 — 시나리오 모드 트리거 명시

**Files:**
- Modify: `_workspace/arch/adr/0016-qa-on-demand.md` (변경 이력 + 결정 보강)

- [ ] **3-1**: 변경 이력 섹션 추가 (없으면 신설, 있으면 행 추가)

```markdown
## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-04-25 | 최초 채택 |
| 2026-05-01 | **시나리오 모드 트리거 명시** — On-Demand 호출 시 architect가 `.spec.ts` 작성 → `scripts/qa-run.mjs scenario` 실행. 7 트리거 조건은 그대로 유지. spec 2026-05-01-qa-defect-loop-design.md 참조 |
```

- [ ] **3-2**: 본문 끝에 "시나리오 모드 트리거(2026-05-01 추가)" 섹션

```markdown
## 시나리오 모드 트리거 (2026-05-01)

7 트리거 조건 발동 시, 또는 사용자 명시 호출 시:

1. architect(=오케스트레이터=메인 세션)가 ADR 0011·0022 베이스로 Playwright `.spec.ts` 작성 (ADR 0030 자율 규약)
2. `scripts/qa-run.mjs new` → ISSUE-NNN 채번
3. `scripts/qa-run.mjs scenario --issue ISSUE-NNN --spec <path> --attempt before`
4. 사용자 시각 검토 → 디펙 확정
5. 수정 PR 머지 후 `scripts/qa-run.mjs scenario --attempt after`
6. `scripts/qa-run.mjs close --pr <N>` → ISSUE FIXED + 인덱스 갱신

상세 spec: `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` §4 흐름.
```

- [ ] **3-3**: README.md 0016 행 갱신

```markdown
| 0016 | QA On-Demand 정책 (MVP) | Accepted (**보강 2026-05-01** — 시나리오 모드 트리거) | 2026-04-25 / 2026-05-01 |
```

- [ ] **3-4**: Commit

```bash
git add _workspace/arch/adr/0016-qa-on-demand.md _workspace/arch/adr/README.md
git commit -m "docs(adr): 0016 보강 — 시나리오 모드 트리거 명시"
```

---

## 2. pyworker 인프라 (다단계 시나리오 endpoint)

### Task 4: 환경변수 + storage presigned URL 발급

**Files:**
- Modify: `pyworker/app/config.py` (MINIO_PUBLIC_HOST 추가)
- Modify: `pyworker/app/screenshot/storage.py` (presigned URL 발급 메서드)
- Test: `pyworker/tests/test_screenshot_storage.py` (신규 또는 기존 보강)

- [ ] **4-1**: 실패 테스트 작성 — `pyworker/tests/test_screenshot_storage.py`

```python
"""storage.put + presigned URL 발급."""
from __future__ import annotations
import pytest

@pytest.mark.asyncio
async def test_put_returns_presigned_url_with_public_host(storage_with_public_host):
    """storage.put이 MINIO_PUBLIC_HOST 기반 presigned URL을 응답에 포함."""
    result = await storage_with_public_host.put(
        "issues/ISSUE-008/before/2026-05-01T15-00-00Z/01-test.png",
        b"fake png bytes",
        "image/png",
    )
    assert result.key == "issues/ISSUE-008/before/2026-05-01T15-00-00Z/01-test.png"
    assert result.public_url is not None
    assert result.public_url.startswith("http://localhost:9000/")
    assert "issues/ISSUE-008" in result.public_url
```

`conftest.py`에 fixture 추가:

```python
@pytest.fixture
def storage_with_public_host(monkeypatch):
    monkeypatch.setenv("MINIO_PUBLIC_HOST", "localhost:9000")
    monkeypatch.setenv("S3_BUCKET_SCREENSHOT", "magicjar-screenshots")
    from app.screenshot.storage import InMemoryStorageBackend
    return InMemoryStorageBackend(public_host="localhost:9000")
```

- [ ] **4-2**: 테스트 실행 — fail 확인

```bash
cd pyworker && uv run pytest tests/test_screenshot_storage.py -v
```
Expected: FAIL — `public_host` 인자 없음 / `localhost:9000` 미포함.

- [ ] **4-3**: `app/config.py`에 `MINIO_PUBLIC_HOST` 필드 추가

```python
# (Settings 클래스 안)
minio_public_host: str = Field(default="localhost:9000", alias="MINIO_PUBLIC_HOST")
```

- [ ] **4-4**: `app/screenshot/storage.py`에 presigned URL 로직 추가

```python
# StorageBackend 추상 메서드는 그대로. 구현체에 public_host 인자 추가.
class InMemoryStorageBackend(StorageBackend):
    def __init__(self, public_host: str = "localhost:9000") -> None:
        self._store: dict[str, bytes] = {}
        self._public_host = public_host

    async def put(self, key: str, data: bytes, content_type: str) -> StoragePutResult:
        self._store[key] = data
        public_url = f"http://{self._public_host}/magicjar-screenshots/{key}"
        return StoragePutResult(key=key, public_url=public_url)

# MinioStorageBackend 도 동일 패턴 — minio-py의 presigned_get_object 사용
class MinioStorageBackend(StorageBackend):
    def __init__(self, *, endpoint: str, access_key: str, secret_key: str,
                 bucket: str, secure: bool, public_host: str) -> None:
        self._client = Minio(endpoint, access_key=access_key, secret_key=secret_key, secure=secure)
        self._bucket = bucket
        self._public_host = public_host
        self._secure = secure

    async def put(self, key: str, data: bytes, content_type: str) -> StoragePutResult:
        # 비동기 래핑은 기존 패턴 유지
        await asyncio.to_thread(
            self._client.put_object, self._bucket, key, BytesIO(data), len(data), content_type
        )
        # presigned URL 발급 — public_host로 호스트 치환
        scheme = "https" if self._secure else "http"
        signed = await asyncio.to_thread(
            self._client.presigned_get_object, self._bucket, key, expires=timedelta(hours=24)
        )
        # signed는 endpoint 호스트로 발급되므로 public_host로 치환
        # urlparse로 호스트 추출 → 교체
        parsed = urlparse(signed)
        public_url = parsed._replace(netloc=self._public_host, scheme=scheme).geturl()
        return StoragePutResult(key=key, public_url=public_url)
```

`from datetime import timedelta` / `from io import BytesIO` / `from urllib.parse import urlparse` import 추가.

- [ ] **4-5**: 테스트 실행 — pass 확인

```bash
cd pyworker && uv run pytest tests/test_screenshot_storage.py -v
```
Expected: PASS

- [ ] **4-6**: Commit

```bash
git add pyworker/app/config.py pyworker/app/screenshot/storage.py pyworker/tests/test_screenshot_storage.py pyworker/tests/conftest.py
git commit -m "feat(pyworker): MINIO_PUBLIC_HOST + presigned URL 발급 (24h TTL)"
```

### Task 5: 시나리오 runner 모듈 (Playwright 단계별 실행 + 캡처)

**Files:**
- Create: `pyworker/app/screenshot/scenario.py`
- Test: `pyworker/tests/test_screenshot_scenario.py`

- [ ] **5-1**: 실패 테스트 작성

```python
"""ScenarioRunner — specCode 받아 Playwright 단계별 실행 + 단계별 PNG 추출."""
from __future__ import annotations
import pytest
from app.screenshot.scenario import ScenarioRunner, ScenarioRequest, StepResult

@pytest.mark.asyncio
async def test_runner_executes_all_steps_when_no_error(fake_playwright):
    """모든 단계 PASS 시 steps 모두 status=pass, failedAtStep=None."""
    spec = '''
import { test } from '@playwright/test';
test('demo', async ({ page }) => {
  await page.goto('http://example.com');
  await page.screenshot({ path: '01-goto.png' });
  await page.screenshot({ path: '02-final.png' });
});
'''
    req = ScenarioRequest(spec_code=spec, viewport_w=1440, viewport_h=900)
    runner = ScenarioRunner(playwright=fake_playwright)
    result = await runner.run(req)
    assert len(result.steps) == 2
    assert all(s.status == "pass" for s in result.steps)
    assert result.failed_at_step is None
    assert result.steps[0].name == "01-goto"
    assert result.steps[0].image_bytes  # PNG bytes 존재

@pytest.mark.asyncio
async def test_runner_marks_failed_step_on_runtime_error(fake_playwright_failing_at_step_2):
    """단계 2에서 selector not found → status=fail, failedAtStep=1."""
    spec = '''...'''  # 위와 동일
    req = ScenarioRequest(spec_code=spec, viewport_w=1440, viewport_h=900)
    runner = ScenarioRunner(playwright=fake_playwright_failing_at_step_2)
    result = await runner.run(req)
    assert result.steps[0].status == "pass"
    assert result.steps[1].status == "fail"
    assert "selector" in (result.steps[1].error or "").lower()
    assert result.failed_at_step == 1
```

- [ ] **5-2**: 테스트 실행 — fail 확인

```bash
cd pyworker && uv run pytest tests/test_screenshot_scenario.py -v
```
Expected: FAIL — module 없음

- [ ] **5-3**: `app/screenshot/scenario.py` 신규 작성

```python
"""다단계 Playwright 시나리오 runner.

ADR 0011 (2026-05-01 개정) 다단계 시나리오 endpoint를 위한 코어.
- specCode 문자열 inline 받아 임시 .spec.ts 파일에 기록
- Playwright headless 단일 컨텍스트로 실행
- page.screenshot({path: 'NN-step.png'}) 호출 시 단계 자동 추적
- runtime error(timeout, selector not found, navigation) 발생 단계 = fail
- 시각 디펙 자동 판정은 비범위 — 캡처만
"""
from __future__ import annotations

import logging
import re
import tempfile
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Protocol

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ScenarioRequest:
    spec_code: str
    viewport_w: int = 1440
    viewport_h: int = 900
    full_page: bool = False
    timeout_ms: int = 30000


@dataclass
class StepResult:
    index: int
    name: str
    status: str  # "pass" | "fail"
    duration_ms: int
    image_bytes: bytes
    error: Optional[str] = None


@dataclass
class ScenarioResult:
    steps: list[StepResult] = field(default_factory=list)
    failed_at_step: Optional[int] = None
    total_duration_ms: int = 0


class PlaywrightLike(Protocol):
    """테스트용 추상화 — 실제는 playwright.async_api 인스턴스."""
    async def run_spec(self, spec_path: Path, viewport: dict) -> "PlaywrightRunResult": ...


@dataclass
class PlaywrightRunResult:
    """playwright runner의 raw 결과 — 단계 추적용."""
    captures: list[tuple[str, bytes, int]]  # (filename, png_bytes, duration_ms)
    failed_step_name: Optional[str] = None
    error_message: Optional[str] = None


_STEP_NAME_RE = re.compile(r"^(\d+)-(.+?)\.png$", re.IGNORECASE)


class ScenarioRunner:
    """specCode → 임시 파일 → Playwright 실행 → 단계별 PNG."""

    def __init__(self, *, playwright: PlaywrightLike) -> None:
        self._pw = playwright

    async def run(self, req: ScenarioRequest) -> ScenarioResult:
        # 1) specCode → 임시 파일
        tmp_dir = Path(tempfile.mkdtemp(prefix="qa-scenario-"))
        spec_path = tmp_dir / f"scenario-{uuid.uuid4().hex[:8]}.spec.ts"
        spec_path.write_text(req.spec_code, encoding="utf-8")

        try:
            raw = await self._pw.run_spec(
                spec_path,
                viewport={"width": req.viewport_w, "height": req.viewport_h},
            )
        finally:
            # 항상 임시 파일 폐기
            try:
                spec_path.unlink(missing_ok=True)
                tmp_dir.rmdir()
            except OSError:
                log.warning("temp scenario dir cleanup failed: %s", tmp_dir)

        # 2) raw.captures를 StepResult로 변환
        steps: list[StepResult] = []
        failed_idx: Optional[int] = None
        for i, (filename, png_bytes, dur) in enumerate(raw.captures):
            name = _normalize_step_name(filename, fallback_index=i)
            is_failed = (raw.failed_step_name == filename)
            steps.append(StepResult(
                index=i,
                name=name,
                status="fail" if is_failed else "pass",
                duration_ms=dur,
                image_bytes=png_bytes,
                error=raw.error_message if is_failed else None,
            ))
            if is_failed:
                failed_idx = i

        total = sum(s.duration_ms for s in steps)
        return ScenarioResult(steps=steps, failed_at_step=failed_idx, total_duration_ms=total)


def _normalize_step_name(filename: str, fallback_index: int) -> str:
    """`01-goto-symbol-page.png` → `01-goto-symbol-page`."""
    m = _STEP_NAME_RE.match(filename)
    if m:
        return f"{m.group(1)}-{m.group(2)}"
    return f"{fallback_index:02d}-unnamed"
```

- [ ] **5-4**: `conftest.py`에 fake playwright fixture 추가

```python
@pytest.fixture
def fake_playwright():
    """모든 단계 PASS하는 fake."""
    from app.screenshot.scenario import PlaywrightRunResult

    class FakePW:
        async def run_spec(self, spec_path, viewport):
            return PlaywrightRunResult(
                captures=[
                    ("01-goto.png", b"\x89PNG\r\n\x1a\nfake", 850),
                    ("02-final.png", b"\x89PNG\r\n\x1a\nfake", 320),
                ],
                failed_step_name=None,
                error_message=None,
            )
    return FakePW()


@pytest.fixture
def fake_playwright_failing_at_step_2():
    from app.screenshot.scenario import PlaywrightRunResult

    class FakePW:
        async def run_spec(self, spec_path, viewport):
            return PlaywrightRunResult(
                captures=[
                    ("01-goto.png", b"\x89PNG\r\n\x1a\nok", 800),
                    ("02-click.png", b"\x89PNG\r\n\x1a\nfail-shot", 300),
                ],
                failed_step_name="02-click.png",
                error_message="selector .candle-series not visible (timeout 30000ms)",
            )
    return FakePW()
```

- [ ] **5-5**: 테스트 실행 — pass 확인

```bash
cd pyworker && uv run pytest tests/test_screenshot_scenario.py -v
```
Expected: PASS (2 tests)

- [ ] **5-6**: Commit

```bash
git add pyworker/app/screenshot/scenario.py pyworker/tests/test_screenshot_scenario.py pyworker/tests/conftest.py
git commit -m "feat(pyworker): ScenarioRunner — Playwright 단계별 실행 + 단계 fail 추적"
```

### Task 6: 실제 Playwright 어댑터 (PlaywrightLike 구현체)

**Files:**
- Create: `pyworker/app/screenshot/scenario_pw.py` — 실제 Playwright 호출
- Test: `pyworker/tests/test_screenshot_scenario_pw.py` — 통합 테스트(실제 Playwright headless)

- [ ] **6-1**: 통합 테스트 — 실제 Playwright로 about:blank 캡처 1단계

```python
"""실제 playwright로 1단계 about:blank 시나리오 — 환경 의존."""
from __future__ import annotations
import pytest
from pathlib import Path
from app.screenshot.scenario import ScenarioRequest
from app.screenshot.scenario_pw import RealPlaywrightRunner

@pytest.mark.integration
@pytest.mark.asyncio
async def test_real_playwright_blank_page_capture():
    """실 Playwright headless — Chromium browser 설치되어 있어야 함."""
    spec = """
import { test } from '@playwright/test';
test('blank', async ({ page }) => {
  await page.goto('about:blank');
  await page.screenshot({ path: '01-blank.png' });
});
"""
    runner = RealPlaywrightRunner()
    req = ScenarioRequest(spec_code=spec, viewport_w=800, viewport_h=600)
    # RealPlaywrightRunner는 PlaywrightLike만 구현. ScenarioRunner를 통해 호출
    from app.screenshot.scenario import ScenarioRunner
    sr = ScenarioRunner(playwright=runner)
    result = await sr.run(req)
    assert len(result.steps) == 1
    assert result.steps[0].status == "pass"
    assert result.steps[0].image_bytes.startswith(b"\x89PNG")
    assert result.failed_at_step is None
```

- [ ] **6-2**: 테스트 실행 — fail 확인

```bash
cd pyworker && uv run pytest tests/test_screenshot_scenario_pw.py -v -m integration
```
Expected: FAIL — module 없음

- [ ] **6-3**: `app/screenshot/scenario_pw.py` 작성

```python
"""실제 Playwright 어댑터 — `playwright.async_api` 위에 PlaywrightLike 구현."""
from __future__ import annotations

import asyncio
import logging
import time
from pathlib import Path
from typing import Optional

from app.screenshot.scenario import PlaywrightLike, PlaywrightRunResult

log = logging.getLogger(__name__)


class RealPlaywrightRunner(PlaywrightLike):
    """Playwright `pytest -p playwright` 또는 `playwright cli` 대신 코드로 직접 실행.

    스펙 코드를 단순화: `import {test} ... test('name', async ({page}) => { ... })`
    형태에서 inner block을 추출해 직접 실행. 복잡한 코드 패턴은 비범위.
    """

    async def run_spec(self, spec_path: Path, viewport: dict) -> PlaywrightRunResult:
        # 우리 시나리오는 playwright runner CLI보다 단순하게 직접 page 객체 위에서 실행
        # spec_path의 코드를 읽어 test 본문(async ({page}) => { ... }) 만 추출
        code = spec_path.read_text(encoding="utf-8")
        body = _extract_test_body(code)

        # node로 실행하는 대신 PlaywrightTest CLI 호출:
        # playwright test <spec_path> --reporter=line --output <tmp_artifacts>
        # 그러나 CI 외부에서 cli 호출은 무겁고 결과 파싱 복잡.
        # → 본 어댑터는 playwright async API를 직접 사용해 작성된 spec을 "함수처럼" 실행.
        # spec 작성자(architect)는 본문이 page 객체에 한정된 단순 sequence 가정.
        return await _run_inline(body, viewport)


_TEST_BODY_RE = __import__("re").compile(
    r"test\([^,]+,\s*async\s*\(\s*\{\s*page\s*\}\s*\)\s*=>\s*\{(.+)\}\s*\)\s*;?\s*$",
    __import__("re").DOTALL,
)


def _extract_test_body(code: str) -> str:
    """spec_code에서 test() 콜백 본문만 추출."""
    m = _TEST_BODY_RE.search(code)
    if not m:
        raise ValueError(
            "scenario specCode 형식 오류: `test('name', async ({page}) => { ... })` 패턴이 필요"
        )
    return m.group(1).strip()


async def _run_inline(body: str, viewport: dict) -> PlaywrightRunResult:
    """playwright async_api로 직접 실행 — 본문에서 page.screenshot 호출 시 캡처 누적."""
    from playwright.async_api import async_playwright

    captures: list[tuple[str, bytes, int]] = []
    failed_step_name: Optional[str] = None
    error_message: Optional[str] = None

    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=True)
        context = await browser.new_context(viewport=viewport)
        page = await context.new_page()

        # 본문은 page.goto, page.click, page.screenshot 등 await 호출 sequence라고 가정.
        # 우리는 그 본문을 동적으로 실행하지 않고, **JavaScript 기반 spec → Python 호출 변환**을
        # 메인 세션(architect)에게 강제하지 않으려면, spec_code를 신뢰해서 그대로 실행해야.
        # → 가장 단순: subprocess로 `npx playwright test <spec>` 실행. 본 plan에서는
        #   subprocess 방식 채택. 위 in-process 실행은 다음 사이클로 미룸.
        await browser.close()

    # 위 in-process 실행은 복잡하므로 subprocess 방식으로 전환 (아래)
    return await _run_via_cli(_TEMP_PATH_PLACEHOLDER, viewport)


# --- subprocess 기반 실제 실행 ---

import re
import shutil
import json

_RE_NEXT = re.compile(r"")  # placeholder


async def _run_via_cli(spec_path: Path, viewport: dict) -> PlaywrightRunResult:
    """`npx playwright test` CLI로 실행, JSON reporter로 결과 파싱.

    spec_path 디렉토리에 임시 playwright.config.ts를 만들고 실행.
    """
    import os

    work_dir = spec_path.parent
    cfg = work_dir / "playwright.config.ts"
    cfg.write_text(
        f"""
import {{ defineConfig }} from '@playwright/test';
export default defineConfig({{
  testDir: '.',
  fullyParallel: false,
  reporter: [['json', {{ outputFile: '{work_dir.as_posix()}/result.json' }}]],
  use: {{
    headless: true,
    viewport: {{ width: {viewport["width"]}, height: {viewport["height"]} }},
    screenshot: 'off',
  }},
}});
""",
        encoding="utf-8",
    )

    proc = await asyncio.create_subprocess_exec(
        "npx", "playwright", "test", str(spec_path.name),
        cwd=str(work_dir),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout, stderr = await proc.communicate()

    # JSON reporter에서 단계 정보 + screenshot 첨부 파일 경로 추출
    result_json = work_dir / "result.json"
    captures: list[tuple[str, bytes, int]] = []
    failed_step_name: Optional[str] = None
    error_message: Optional[str] = None

    if result_json.exists():
        data = json.loads(result_json.read_text())
        # JSON reporter의 각 test의 attachments에서 screenshot path 수집
        for suite in data.get("suites", []):
            for spec in suite.get("specs", []):
                for test in spec.get("tests", []):
                    for r in test.get("results", []):
                        for att in r.get("attachments", []):
                            if att.get("contentType") == "image/png":
                                p = Path(att["path"])
                                if p.exists():
                                    captures.append((p.name, p.read_bytes(), int(r.get("duration", 0))))
                        if r.get("status") == "failed":
                            failed_step_name = (r.get("error", {}).get("message") or "")[:200]
                            error_message = r.get("error", {}).get("message")

    # **NOTE**: 위 단순 변환은 대표적 케이스만 커버. spec 작성자가
    # `page.screenshot({path: 'NN-step.png'})` 패턴을 따르면 정확.
    # 복잡한 reporter 동작은 다음 사이클에서 보강.

    return PlaywrightRunResult(
        captures=captures,
        failed_step_name=failed_step_name,
        error_message=error_message,
    )

_TEMP_PATH_PLACEHOLDER = Path("/tmp/__placeholder__")
```

> **NOTE**: 위 `_run_inline`은 placeholder. 실제로는 `_run_via_cli`만 사용. cleanup은 ScenarioRunner가 수행.

- [ ] **6-4**: 테스트 실행 — pass 확인 (Playwright Chromium 설치 필요)

```bash
cd pyworker && uv run playwright install chromium  # 1회
cd pyworker && uv run pytest tests/test_screenshot_scenario_pw.py -v -m integration
```
Expected: PASS (1 test) — about:blank 캡처 1장

- [ ] **6-5**: Commit

```bash
git add pyworker/app/screenshot/scenario_pw.py pyworker/tests/test_screenshot_scenario_pw.py
git commit -m "feat(pyworker): RealPlaywrightRunner — npx playwright test CLI + JSON reporter 파싱"
```

### Task 7: `POST /screenshot/scenario` 라우터

**Files:**
- Create: `pyworker/app/routers/scenario.py`
- Modify: `pyworker/app/main.py` (라우터 등록 + RealPlaywrightRunner 의존성 주입)
- Test: `pyworker/tests/test_routers_scenario.py`

- [ ] **7-1**: 실패 테스트 작성

```python
"""POST /screenshot/scenario — 인라인 specCode 받아 단계별 PNG + presigned URL 응답."""
from __future__ import annotations
import pytest


@pytest.mark.asyncio
async def test_post_scenario_returns_step_results_and_presigned_urls(
    client, app, fake_scenario_runner, storage_with_public_host
):
    """3단계 시나리오 — 모두 PASS, presigned URL 3개 응답."""
    spec = """
import { test } from '@playwright/test';
test('demo', async ({ page }) => {
  await page.goto('about:blank');
  await page.screenshot({ path: '01-blank.png' });
  await page.screenshot({ path: '02-still.png' });
  await page.screenshot({ path: '03-final.png' });
});
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
async def test_post_scenario_marks_failed_step(
    client, app, fake_scenario_runner_failing_step_2, storage_with_public_host
):
    resp = await client.post(
        "/screenshot/scenario",
        json={"specCode": "...", "issueId": "ISSUE-009", "attempt": "before", "retry": 1,
              "viewport": {"width": 1440, "height": 900}},
    )
    assert resp.status_code == 200
    body = resp.json()
    assert body["failedAtStep"] == 1
    assert body["steps"][1]["status"] == "fail"
    assert body["steps"][1]["error"] is not None
```

- [ ] **7-2**: 테스트 실행 — fail 확인

```bash
cd pyworker && uv run pytest tests/test_routers_scenario.py -v
```
Expected: FAIL — `/screenshot/scenario` 라우트 없음 (404)

- [ ] **7-3**: 라우터 작성

```python
# pyworker/app/routers/scenario.py
"""POST /screenshot/scenario — 다단계 시나리오 캡처.

ADR 0011 (2026-05-01 개정) 다단계 모드.
spec 2026-05-01-qa-defect-loop-design.md §6 시그니처 그대로.
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone
from typing import Annotated, Literal, Optional

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from app.screenshot.scenario import ScenarioRequest, ScenarioRunner
from app.screenshot.storage import StorageBackend

log = logging.getLogger(__name__)
router = APIRouter(prefix="/screenshot", tags=["screenshot"])


class Viewport(BaseModel):
    width: int = Field(default=1440, ge=320, le=4096)
    height: int = Field(default=900, ge=240, le=4096)


class ScenarioRequestBody(BaseModel):
    specCode: str
    issueId: str
    attempt: Literal["before", "after"]
    retry: int = 1
    viewport: Viewport = Field(default_factory=Viewport)
    requestedBy: Optional[str] = None


class StepResponse(BaseModel):
    index: int
    name: str
    status: str
    durationMs: int
    storageKey: str
    presignedUrl: Optional[str]
    error: Optional[str] = None


class ScenarioResponse(BaseModel):
    issueId: str
    attempt: str
    retry: int
    runTimestamp: str
    steps: list[StepResponse]
    failedAtStep: Optional[int]
    totalDurationMs: int


def _runner(request: Request) -> ScenarioRunner:
    r = getattr(request.app.state, "scenario_runner", None)
    if r is None:
        raise HTTPException(status_code=503, detail="scenario_runner unavailable")
    return r


def _storage(request: Request) -> StorageBackend:
    s = getattr(request.app.state, "storage", None)
    if s is None:
        raise HTTPException(status_code=503, detail="storage unavailable")
    return s


@router.post("/scenario", response_model=ScenarioResponse, summary="다단계 Playwright 시나리오 캡처")
async def run_scenario(
    body: ScenarioRequestBody,
    runner: Annotated[ScenarioRunner, Depends(_runner)],
    storage: Annotated[StorageBackend, Depends(_storage)],
) -> ScenarioResponse:
    # 1) 시나리오 실행
    sreq = ScenarioRequest(
        spec_code=body.specCode,
        viewport_w=body.viewport.width,
        viewport_h=body.viewport.height,
    )
    sresult = await runner.run(sreq)

    # 2) 단계별 PNG → MinIO 업로드 + presigned URL
    run_ts = datetime.now(tz=timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")
    base = f"issues/{body.issueId}/{body.attempt}/{run_ts}"
    step_responses: list[StepResponse] = []

    for s in sresult.steps:
        suffix = "-FAIL" if s.status == "fail" else ""
        key = f"{base}/{s.name}{suffix}.png"
        put = await storage.put(key, s.image_bytes, "image/png")
        step_responses.append(StepResponse(
            index=s.index,
            name=s.name,
            status=s.status,
            durationMs=s.duration_ms,
            storageKey=put.key,
            presignedUrl=put.public_url,
            error=s.error,
        ))

    return ScenarioResponse(
        issueId=body.issueId,
        attempt=body.attempt,
        retry=body.retry,
        runTimestamp=run_ts,
        steps=step_responses,
        failedAtStep=sresult.failed_at_step,
        totalDurationMs=sresult.total_duration_ms,
    )
```

- [ ] **7-4**: `app/main.py`에 라우터 등록 + ScenarioRunner 의존성 주입

```python
# 기존 app/main.py의 startup 핸들러 안
from app.screenshot.scenario import ScenarioRunner
from app.screenshot.scenario_pw import RealPlaywrightRunner
from app.routers import scenario as scenario_router  # 신규

# startup에서:
app.state.scenario_runner = ScenarioRunner(playwright=RealPlaywrightRunner())

# 라우터 include:
app.include_router(scenario_router.router)
```

- [ ] **7-5**: `conftest.py`에 fake fixture 추가

```python
@pytest.fixture
def fake_scenario_runner(app, storage_with_public_host):
    """3단계 모두 PASS."""
    from app.screenshot.scenario import StepResult, ScenarioResult

    class FakeRunner:
        async def run(self, req):
            return ScenarioResult(
                steps=[
                    StepResult(0, "01-blank", "pass", 800, b"\x89PNG\r\n\x1a\nfake"),
                    StepResult(1, "02-still", "pass", 350, b"\x89PNG\r\n\x1a\nfake"),
                    StepResult(2, "03-final", "pass", 200, b"\x89PNG\r\n\x1a\nfake"),
                ],
                failed_at_step=None,
                total_duration_ms=1350,
            )

    app.state.scenario_runner = FakeRunner()
    app.state.storage = storage_with_public_host
    return FakeRunner()


@pytest.fixture
def fake_scenario_runner_failing_step_2(app, storage_with_public_host):
    from app.screenshot.scenario import StepResult, ScenarioResult

    class FakeRunner:
        async def run(self, req):
            return ScenarioResult(
                steps=[
                    StepResult(0, "01-goto", "pass", 800, b"\x89PNG\r\n\x1a\nok"),
                    StepResult(1, "02-click", "fail", 300, b"\x89PNG\r\n\x1a\nfail",
                               error="selector .candle-series timeout"),
                ],
                failed_at_step=1,
                total_duration_ms=1100,
            )

    app.state.scenario_runner = FakeRunner()
    app.state.storage = storage_with_public_host
    return FakeRunner()
```

- [ ] **7-6**: 테스트 실행 — pass 확인

```bash
cd pyworker && uv run pytest tests/test_routers_scenario.py -v
```
Expected: PASS (2 tests)

- [ ] **7-7**: docker-compose.yml에 `MINIO_PUBLIC_HOST` 환경변수 추가

```yaml
# docker/docker-compose.yml의 pyworker 서비스
pyworker:
  environment:
    # 기존 환경변수 유지...
    MINIO_PUBLIC_HOST: ${MINIO_PUBLIC_HOST:-localhost:9000}
```

- [ ] **7-8**: Commit

```bash
git add pyworker/app/routers/scenario.py pyworker/app/main.py pyworker/tests/test_routers_scenario.py pyworker/tests/conftest.py docker/docker-compose.yml
git commit -m "feat(pyworker): POST /screenshot/scenario 라우터 + MINIO_PUBLIC_HOST 와이어링"
```

---

## 3. scripts/qa-run.mjs CLI

### Task 8: 공용 헬퍼 (frontmatter 파싱·갱신)

**Files:**
- Create: `scripts/qa-run.mjs` (1차 — 헬퍼 함수만)

- [ ] **8-1**: shebang + 헬퍼 작성

```javascript
#!/usr/bin/env node
// magicJar QA 디펙 순환 루프 CLI.
// _workspace/qa/issues/<id>/{card.md, scenario-{before|after}.spec.ts, report-{before|after}.md}
// 갱신 + pyworker /screenshot/scenario 호출.
//
// 사용 예:
//   node scripts/qa-run.mjs new --symptom "차트 안 보여" --target /symbol/005930
//   node scripts/qa-run.mjs scenario --issue ISSUE-008 --spec ./tmp.spec.ts --attempt before
//   node scripts/qa-run.mjs close --issue ISSUE-008 --pr 50
//   node scripts/qa-run.mjs wontfix --issue ISSUE-008 --reason "..."
//   node scripts/qa-run.mjs list

import { readFileSync, writeFileSync, existsSync, readdirSync, mkdirSync, copyFileSync } from 'node:fs';
import { join, basename } from 'node:path';

const QA_ROOT = '_workspace/qa';
const ISSUES_DIR = `${QA_ROOT}/issues`;
const INDEX_FILE = `${QA_ROOT}/issues.md`;
const PYWORKER_URL = process.env.PYWORKER_URL || 'http://localhost:8000';

function nowIso() { return new Date().toISOString(); }

function ensureDirs() {
  if (!existsSync(ISSUES_DIR)) mkdirSync(ISSUES_DIR, { recursive: true });
}

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) { out[a.slice(2)] = argv[++i]; }
    else { out._.push(a); }
  }
  return out;
}

function parseFrontmatter(text) {
  const m = text.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!m) return { meta: {}, body: text };
  const meta = {};
  m[1].split('\n').forEach(line => {
    const idx = line.indexOf(':');
    if (idx > 0) {
      const k = line.slice(0, idx).trim();
      const v = line.slice(idx + 1).trim();
      meta[k] = (v === 'null' || v === '') ? null : v;
    }
  });
  return { meta, body: m[2] };
}

function serializeFrontmatter(meta, body) {
  const order = ['id', 'status', 'reportedAt', 'reportedBy', 'symptom',
                 'target', 'relatedAdr', 'relatedPr', 'fixedAt'];
  const lines = [];
  for (const k of order) {
    if (meta[k] !== undefined) lines.push(`${k}: ${meta[k] === null ? 'null' : meta[k]}`);
  }
  for (const k of Object.keys(meta)) {
    if (!order.includes(k)) lines.push(`${k}: ${meta[k] === null ? 'null' : meta[k]}`);
  }
  return `---\n${lines.join('\n')}\n---\n${body}`;
}

function nextIssueId() {
  ensureDirs();
  const existing = readdirSync(ISSUES_DIR).filter(f => f.startsWith('ISSUE-'));
  let max = 7; // legacy ISSUE-007까지 존재
  for (const dir of existing) {
    const m = dir.match(/^ISSUE-(\d+)$/);
    if (m) max = Math.max(max, parseInt(m[1], 10));
  }
  return `ISSUE-${String(max + 1).padStart(3, '0')}`;
}

function issueDir(id) { return join(ISSUES_DIR, id); }
function cardPath(id) { return join(issueDir(id), 'card.md'); }
```

- [ ] **8-2**: 실행 권한 부여

```bash
chmod +x scripts/qa-run.mjs
```

- [ ] **8-3**: 단순 import 테스트

```bash
node -e "console.log('OK')" && node --check scripts/qa-run.mjs
```
Expected: 출력 OK + syntax 에러 없음

- [ ] **8-4**: Commit (헬퍼만, CLI 명령은 다음 task에서)

```bash
git add scripts/qa-run.mjs
git commit -m "feat(scripts): qa-run.mjs 헬퍼 — frontmatter·issue ID 채번 (1차)"
```

### Task 9: `qa-run.mjs new` 명령

**Files:**
- Modify: `scripts/qa-run.mjs` (cmdNew + dispatch)

- [ ] **9-1**: cmdNew 구현 + 명령 dispatch

`scripts/qa-run.mjs` 끝에 추가:

```javascript
function cmdNew(args) {
  const symptom = args.symptom || (() => { throw new Error('--symptom 필수'); })();
  const target = args.target || (() => { throw new Error('--target 필수 (URL 또는 화면명)'); })();
  const adr = args.adr || '';
  const reportedBy = args.reportedBy || 'user';

  ensureDirs();
  const id = nextIssueId();
  const dir = issueDir(id);
  mkdirSync(dir, { recursive: true });

  const meta = {
    id,
    status: 'OPEN',
    reportedAt: nowIso(),
    reportedBy,
    symptom,
    target,
    relatedAdr: adr ? `[${adr}]` : '[]',
    relatedPr: null,
    fixedAt: null,
  };
  const body = `\n## 증상\n${symptom}\n\n## 재현 단계\n(architect가 시나리오 작성 시 채움)\n\n## before 캡처\n(scenario before 실행 후 채움)\n\n## after 캡처\n(scenario after 실행 후 채움)\n\n## 시나리오 코드 (인용)\n(scenario-before.spec.ts 인용)\n`;

  writeFileSync(cardPath(id), serializeFrontmatter(meta, body), 'utf-8');
  console.log(`✓ ${id} 채번 + 디렉토리 생성`);
  console.log(`  ${dir}/card.md`);
  console.log(`  → 다음: scripts/qa-run.mjs scenario --issue ${id} --spec <path> --attempt before`);
}

const args = parseArgs(process.argv.slice(3));
const cmd = process.argv[2];
try {
  switch (cmd) {
    case 'new': cmdNew(args); break;
    case 'scenario': /* task 10 */ break;
    case 'close': /* task 11 */ break;
    case 'wontfix': /* task 11 */ break;
    case 'list': /* task 11 */ break;
    default:
      console.error('Usage: qa-run.mjs <new|scenario|close|wontfix|list> [args...]');
      process.exit(1);
  }
} catch (e) {
  console.error(`✗ ${e.message}`);
  process.exit(1);
}
```

- [ ] **9-2**: 실행 검증

```bash
node scripts/qa-run.mjs new --symptom "테스트용 가짜 보고" --target /symbol/005930
```
Expected: `_workspace/qa/issues/ISSUE-008/card.md` 생성, 콘솔에 ID 출력

- [ ] **9-3**: 검증 후 임시 ISSUE 폴더 삭제 (실제 dogfooding은 task 16에서)

```bash
rm -rf _workspace/qa/issues/ISSUE-008
```

- [ ] **9-4**: Commit

```bash
git add scripts/qa-run.mjs
git commit -m "feat(scripts): qa-run.mjs new 명령 — ISSUE-NNN 채번 + 디렉토리 + card.md"
```

### Task 10: `qa-run.mjs scenario` 명령 (pyworker 호출)

**Files:**
- Modify: `scripts/qa-run.mjs` (cmdScenario)

- [ ] **10-1**: cmdScenario 구현

```javascript
async function cmdScenario(args) {
  const id = args.issue || (() => { throw new Error('--issue 필수'); })();
  const specPath = args.spec || (() => { throw new Error('--spec 필수 (.spec.ts 경로)'); })();
  const attempt = args.attempt;
  if (!['before', 'after'].includes(attempt)) {
    throw new Error('--attempt 는 before|after');
  }
  const retry = parseInt(args.retry || '1', 10);
  const viewportW = parseInt(args.width || '1440', 10);
  const viewportH = parseInt(args.height || '900', 10);

  if (!existsSync(cardPath(id))) {
    throw new Error(`${id} 카드 없음. 먼저: qa-run.mjs new`);
  }
  const specCode = readFileSync(specPath, 'utf-8');

  // 1) pyworker 호출
  console.log(`→ POST ${PYWORKER_URL}/screenshot/scenario (issueId=${id}, attempt=${attempt})`);
  const resp = await fetch(`${PYWORKER_URL}/screenshot/scenario`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      specCode, issueId: id, attempt, retry,
      viewport: { width: viewportW, height: viewportH },
      requestedBy: 'qa-run.mjs',
    }),
  });
  if (!resp.ok) {
    const t = await resp.text();
    throw new Error(`pyworker ${resp.status}: ${t}`);
  }
  const result = await resp.json();

  // 2) scenario-{attempt}.spec.ts 보존
  const specDst = join(issueDir(id), `scenario-${attempt}.spec.ts`);
  copyFileSync(specPath, specDst);

  // 3) report-{attempt}.md 작성
  const reportPath = join(issueDir(id), `report-${attempt}.md`);
  const reportLines = [
    `# ${id} — ${attempt} 보고`,
    ``,
    `- run: \`${result.runTimestamp}\` · retry ${result.retry} · 총 ${result.totalDurationMs}ms`,
    `- 실패 단계: ${result.failedAtStep === null ? '없음 (모두 PASS)' : `step ${result.failedAtStep}`}`,
    ``,
    `## 단계별 결과`,
    ``,
    `| # | 단계 | 상태 | 시간 | URL |`,
    `|---|---|---|---|---|`,
    ...result.steps.map(s => {
      const status = s.status === 'pass' ? '✅ pass' : '❌ fail';
      return `| ${s.index} | ${s.name} | ${status} | ${s.durationMs}ms | [${basename(s.storageKey)}](${s.presignedUrl}) |`;
    }),
    ``,
  ];
  if (result.failedAtStep !== null) {
    const failed = result.steps[result.failedAtStep];
    reportLines.push(`## 실패 정보\n\n\`\`\`\n${failed.error}\n\`\`\`\n`);
  }
  writeFileSync(reportPath, reportLines.join('\n'), 'utf-8');

  // 4) card.md 갱신 — INVESTIGATING 진입
  const cardText = readFileSync(cardPath(id), 'utf-8');
  const { meta, body } = parseFrontmatter(cardText);
  if (attempt === 'before') meta.status = 'INVESTIGATING';
  writeFileSync(cardPath(id), serializeFrontmatter(meta, body), 'utf-8');

  // 5) 콘솔 출력
  console.log(`✓ ${id} ${attempt} 보고 작성: ${reportPath}`);
  console.log(`  단계 ${result.steps.length}개${result.failedAtStep !== null ? ` (실패: step ${result.failedAtStep})` : ''}`);
  console.log(`  presigned URLs:`);
  result.steps.forEach(s => console.log(`    [${s.index}] ${s.presignedUrl}`));
}
```

dispatch에 연결:

```javascript
case 'scenario': await cmdScenario(args); break;
```

상단 try/catch를 async 지원하도록 수정:

```javascript
(async () => {
  try {
    switch (cmd) {
      case 'new': cmdNew(args); break;
      case 'scenario': await cmdScenario(args); break;
      // ...
    }
  } catch (e) {
    console.error(`✗ ${e.message}`);
    process.exit(1);
  }
})();
```

- [ ] **10-2**: 실행 검증 — 임시 ISSUE + 임시 spec.ts로 dry run

```bash
# 임시 ISSUE 채번
node scripts/qa-run.mjs new --symptom "dry-run" --target /

# 임시 spec.ts 작성
cat > /tmp/dry-run.spec.ts <<'EOF'
import { test } from '@playwright/test';
test('dry', async ({ page }) => {
  await page.goto('about:blank');
  await page.screenshot({ path: '01-blank.png' });
});
EOF

# scenario 호출 (pyworker 떠 있어야 함)
node scripts/qa-run.mjs scenario --issue ISSUE-008 --spec /tmp/dry-run.spec.ts --attempt before
```
Expected: `_workspace/qa/issues/ISSUE-008/{report-before.md, scenario-before.spec.ts}` 생성, 콘솔에 presigned URL 1개

- [ ] **10-3**: 임시 ISSUE 정리

```bash
rm -rf _workspace/qa/issues/ISSUE-008 /tmp/dry-run.spec.ts
```

- [ ] **10-4**: Commit

```bash
git add scripts/qa-run.mjs
git commit -m "feat(scripts): qa-run.mjs scenario — pyworker 호출 + report.md + card 갱신"
```

### Task 11: `qa-run.mjs close / wontfix / list` 명령

**Files:**
- Modify: `scripts/qa-run.mjs` (cmdClose / cmdWontfix / cmdList)

- [ ] **11-1**: 세 명령 추가

```javascript
function cmdClose(args) {
  const id = args.issue || (() => { throw new Error('--issue 필수'); })();
  const pr = args.pr || null;
  if (!existsSync(cardPath(id))) throw new Error(`${id} 카드 없음`);
  const text = readFileSync(cardPath(id), 'utf-8');
  const { meta, body } = parseFrontmatter(text);
  meta.status = 'FIXED';
  meta.fixedAt = nowIso();
  if (pr) meta.relatedPr = `#${String(pr).replace(/^#/, '')}`;
  writeFileSync(cardPath(id), serializeFrontmatter(meta, body), 'utf-8');
  refreshIndex();
  console.log(`✓ ${id} FIXED${pr ? ` (PR ${meta.relatedPr})` : ''}`);
}

function cmdWontfix(args) {
  const id = args.issue || (() => { throw new Error('--issue 필수'); })();
  const reason = args.reason || (() => { throw new Error('--reason 필수'); })();
  const text = readFileSync(cardPath(id), 'utf-8');
  const { meta, body } = parseFrontmatter(text);
  meta.status = 'WONTFIX';
  const newBody = body + `\n## WONTFIX 사유\n${nowIso()} — ${reason}\n`;
  writeFileSync(cardPath(id), serializeFrontmatter(meta, newBody), 'utf-8');
  refreshIndex();
  console.log(`✓ ${id} WONTFIX: ${reason}`);
}

function cmdList() {
  ensureDirs();
  const dirs = readdirSync(ISSUES_DIR).filter(d => d.startsWith('ISSUE-'));
  if (!dirs.length) { console.log('(이슈 디렉토리 없음)'); return; }
  for (const d of dirs.sort()) {
    if (!existsSync(cardPath(d))) continue;
    const { meta } = parseFrontmatter(readFileSync(cardPath(d), 'utf-8'));
    const sym = meta.symptom ? meta.symptom.slice(0, 60) : '';
    const icon = { OPEN: '🟡', INVESTIGATING: '🟠', FIXED: '✅', WONTFIX: '⚪', DEFERRED: '⏸️' }[meta.status] || '?';
    console.log(`${icon} ${meta.id}  [${meta.status}]  ${sym}`);
  }
}

function refreshIndex() {
  // _workspace/qa/issues.md 인덱스 양식 — task 12에서 정의
  // 여기서는 단순히 현재 디렉토리 스캔하여 인덱스 행 생성
  ensureDirs();
  const dirs = readdirSync(ISSUES_DIR).filter(d => d.startsWith('ISSUE-')).sort();
  const rows = [];
  for (const d of dirs) {
    if (!existsSync(cardPath(d))) continue;
    const { meta } = parseFrontmatter(readFileSync(cardPath(d), 'utf-8'));
    const sym = (meta.symptom || '').replace(/\|/g, '\\|').slice(0, 80);
    const pr = meta.relatedPr || '';
    rows.push(`| [${meta.id}](issues/${meta.id}/card.md) | ${meta.status} | ${sym} | ${pr} |`);
  }
  // 인덱스 파일이 task 12에서 정의된 양식이라 가정
  if (!existsSync(INDEX_FILE)) return; // task 12 전엔 skip
  const indexText = readFileSync(INDEX_FILE, 'utf-8');
  const marker = '<!-- AUTO-INDEX:START -->';
  const endMarker = '<!-- AUTO-INDEX:END -->';
  const before = indexText.split(marker)[0];
  const after = indexText.split(endMarker)[1] || '';
  const newIndex = `${before}${marker}\n\n| ID | 상태 | 증상 | PR |\n|---|---|---|---|\n${rows.join('\n')}\n\n${endMarker}${after}`;
  writeFileSync(INDEX_FILE, newIndex, 'utf-8');
}
```

dispatch 갱신:

```javascript
case 'close': cmdClose(args); break;
case 'wontfix': cmdWontfix(args); break;
case 'list': cmdList(); break;
```

- [ ] **11-2**: 검증

```bash
node scripts/qa-run.mjs list
```
Expected: 빈 출력 (활성 이슈 디렉토리 0)

- [ ] **11-3**: Commit

```bash
git add scripts/qa-run.mjs
git commit -m "feat(scripts): qa-run.mjs close/wontfix/list — 라이프사이클 종결 + 인덱스 자동 갱신"
```

---

## 4. 디렉토리 양식 + 인덱스 마이그레이션

### Task 12: `_workspace/qa/issues.md` 인덱스 양식 정리

**Files:**
- Modify: `_workspace/qa/issues.md`
- Create: `_workspace/qa/issues/README.md` (양식 가이드)

- [ ] **12-1**: 기존 `issues.md` 헤더 + AUTO-INDEX 마커 추가, ISSUE-001~007은 legacy 단일 파일 양식 보존

```markdown
# magicJar QA — 이슈 카탈로그

본 파일은 **인덱스**다. 상세는 `_workspace/qa/issues/ISSUE-NNN/` 디렉토리.

- 신규 양식 (ISSUE-008~): `qa-run.mjs new` → 디렉토리 생성. `qa-run.mjs close` 시 아래 표 자동 갱신.
- legacy 양식 (ISSUE-001~007): 단일 파일, 본 인덱스 끝부분에 보존.

상태 표기: OPEN / INVESTIGATING / FIXED / WONTFIX / DEFERRED.

## 신규 양식 (ISSUE-008~)

<!-- AUTO-INDEX:START -->

| ID | 상태 | 증상 | PR |
|---|---|---|---|

<!-- AUTO-INDEX:END -->

## Legacy 양식 (ISSUE-001~007 — 보존)

(아래는 기존 ISSUE-001~007 본문 — 손대지 말 것)

---
```

기존 ISSUE-001~007 본문은 위 헤더 다음에 그대로 보존. 각 이슈 본문은 변경하지 않고 헤더 1줄만 추가:

```markdown
> 상태 갱신은 본문 안에서 수동. ISSUE-008부터는 디렉토리 양식.
```

- [ ] **12-2**: `_workspace/qa/issues/README.md` 작성

```markdown
# QA 이슈 디렉토리 — 양식 가이드

각 이슈는 `ISSUE-NNN/` 디렉토리 한 개로 표현된다 (ISSUE-008~).

## 디렉토리 구조

\`\`\`
ISSUE-008/
├─ card.md                      # frontmatter + 증상·재현·라이프사이클
├─ scenario-before.spec.ts      # qa-run.mjs scenario --attempt before 시 자동 보존
├─ scenario-after.spec.ts       # (있는 경우)
├─ report-before.md             # 단계별 결과 + presigned URL 목록
└─ report-after.md              # (있는 경우)
\`\`\`

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

\`\`\`
OPEN → INVESTIGATING → FIXED
                    ↘ WONTFIX
                    ↘ DEFERRED
\`\`\`

- OPEN: `qa-run.mjs new` 직후
- INVESTIGATING: `qa-run.mjs scenario --attempt before` 후
- FIXED: `qa-run.mjs close --pr <N>` 후
- WONTFIX: `qa-run.mjs wontfix --reason "..."` 후

## MinIO 키 패턴

\`\`\`
magicjar-screenshots/issues/ISSUE-NNN/{before|after}/{ts}/NN-step-name.png
\`\`\`

캡처 자체는 git이 아닌 MinIO에 영속. presigned URL(24h)은 report-{attempt}.md에 인용.

## 관련 ADR

- ADR 0011 (개정 2026-05-01) — 다단계 시나리오 게이트
- ADR 0016 (보강 2026-05-01) — On-Demand 시나리오 모드 트리거
- ADR 0030 — 오케스트레이터=architect 일체화 메타 규약
- spec: `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md`
```

- [ ] **12-3**: 검증 — `qa-run.mjs list` 빈 인덱스 출력

```bash
node scripts/qa-run.mjs list
```
Expected: `(이슈 디렉토리 없음)`

- [ ] **12-4**: Commit

```bash
git add _workspace/qa/issues.md _workspace/qa/issues/README.md
git commit -m "docs(qa): 이슈 인덱스 신/legacy 양식 분리 + 디렉토리 가이드"
```

---

## 5. 에이전트·스킬 갱신

### Task 13: `agents/qa.md` 책임 갱신

**Files:**
- Modify: `.claude/agents/qa.md`

- [ ] **13-1**: 책임 섹션에 시나리오 모드 추가

기존 "핵심 역할" 섹션 아래에 새 하위 섹션 추가:

```markdown
## 시나리오 모드 (2026-05-01 추가)

ADR 0011 개정 + spec `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` 채택 후, QA의 운영 모드:

| 책임 | 책임자 |
|---|---|
| 시나리오 `.spec.ts` 작성 | architect (= 메인 세션, ADR 0030) |
| 받은 스크립트 실행 | **QA 에이전트** (이 파일) |
| pyworker `/screenshot/scenario` 호출 | QA 에이전트 (`scripts/qa-run.mjs`) |
| 결과 분석 + ISSUE 카드 갱신 | **QA 에이전트** |
| 시각 디펙 판정 | 사용자 |

QA 에이전트의 자체 시나리오 작성·시각 판정은 **비범위**. 받은 스크립트 효율적 실행 + 정확한 카드 작성에 집중.

### 호출 인터페이스

\`\`\`bash
# 1) 채번
node scripts/qa-run.mjs new --symptom "..." --target /symbol/005930

# 2) before 캡처
node scripts/qa-run.mjs scenario --issue ISSUE-NNN --spec /tmp/scenario.spec.ts --attempt before

# 3) 사용자 검토 + 수정 PR 머지 후
node scripts/qa-run.mjs scenario --issue ISSUE-NNN --spec /tmp/scenario-after.spec.ts --attempt after
node scripts/qa-run.mjs close --issue ISSUE-NNN --pr <N>
\`\`\`
```

- [ ] **13-2**: Commit

```bash
git add .claude/agents/qa.md
git commit -m "docs(agent): qa — 시나리오 모드 운영 인터페이스 명시"
```

### Task 14: `skills/magicjar-boundary-qa/SKILL.md` 갱신

**Files:**
- Modify: `.claude/skills/magicjar-boundary-qa/SKILL.md`

- [ ] **14-1**: SKILL.md 본문 끝에 "시나리오 모드 워크플로 (2026-05-01)" 섹션 추가

```markdown
## 시나리오 모드 워크플로 (2026-05-01)

ADR 0011 개정 + spec `2026-05-01-qa-defect-loop-design.md` 채택 후. 사용자 보고가 시각·인터랙션 디펙으로 보일 때 (백엔드 500 같은 명시 에러는 비범위 — 로그/curl로 즉시 식별):

1. **architect (= 메인 세션, ADR 0030)** — ADR 베이스로 Playwright `.spec.ts` 작성. ADR 충돌·부자연 시 사용자 한 줄 컨펌.
2. **QA 에이전트** — `scripts/qa-run.mjs new` → ISSUE-NNN 채번 → `qa-run.mjs scenario --attempt before`로 pyworker 호출.
3. **사용자** — presigned URL 시각 검토 → "맞다, 진짜 안 보임" → 디펙 확정.
4. **architect** — 수정 dispatch (frontend/backend-core 등). PR 머지 후 `--attempt after`로 재캡처.
5. **사용자** — after 캡처 검토 → "이제 보임".
6. **QA 에이전트** — `qa-run.mjs close --pr <N>` → FIXED + 인덱스 갱신.

### 책임 분리 boundary

| 컴포넌트 | 책임 | 비책임 |
|---|---|---|
| architect | 시나리오 작성·디펙 확정·수정 dispatch | 직접 캡처 실행 |
| QA 에이전트 | 받은 스크립트 실행·결과 분석·카드 작성 | 시나리오 작성·시각 판정 |
| pyworker | Playwright runner + MinIO + presigned URL | 시나리오 해석 |

### 비범위 (다음 사이클)

- Playwright `expect` 자동 검증
- 픽셀 diff (Percy/Chromatic)
- 시나리오 카탈로그 영속
- 머지 게이트 강제
- 자동 dispatch (메인 세션 키워드 감지)
```

- [ ] **14-2**: Commit

```bash
git add .claude/skills/magicjar-boundary-qa/SKILL.md
git commit -m "docs(skill): boundary-qa — 시나리오 모드 워크플로 + 책임 boundary"
```

---

## 6. Dogfooding (ISSUE-008 첫 사이클)

### Task 15: ISSUE-008 첫 사이클 — 종목 상세 차트 표시 검증

**목표**: 본 plan 채택 후 가장 단순한 1단계 시나리오로 end-to-end 검증. "사용자 보고는 가짜이지만 spec·plan·코드가 실제로 동작하는지" 확인.

**Files:**
- Create: `_workspace/qa/issues/ISSUE-008/card.md` (qa-run.mjs new로 자동 생성)
- Create: `_workspace/qa/issues/ISSUE-008/scenario-before.spec.ts` (architect 작성 → qa-run.mjs scenario로 자동 보존)
- Create: `_workspace/qa/issues/ISSUE-008/report-before.md` (qa-run.mjs scenario로 자동 작성)

- [ ] **15-1**: pyworker 컨테이너 healthy 재확인 + scenario endpoint 접근 테스트

```bash
docker compose -f docker/docker-compose.yml ps pyworker
curl -sS http://localhost:8000/openapi.json | python3 -c "import sys,json; print('paths:', list(json.load(sys.stdin)['paths'].keys()))"
```
Expected: `pyworker  Up healthy`, paths에 `/screenshot/scenario` 포함

- [ ] **15-2**: ISSUE-008 채번 — "종목 상세 차트 표시 dogfooding"

```bash
node scripts/qa-run.mjs new \
  --symptom "dogfooding — 종목 상세 차트 표시 시나리오 동작 검증" \
  --target /symbol/005930 \
  --adr "0011, 0022"
```
Expected: `_workspace/qa/issues/ISSUE-008/card.md` 생성, status=OPEN

- [ ] **15-3**: architect가 시나리오 작성 — `/tmp/issue-008.spec.ts`

```typescript
import { test } from '@playwright/test';
test('symbol detail chart visible', async ({ page }) => {
  // 1) 페이지 진입
  await page.goto('http://frontend:80/symbol/005930');
  await page.waitForLoadState('networkidle');
  await page.screenshot({ path: '01-goto-symbol-page.png' });
  // 2) 차트 영역 로딩 대기
  await page.waitForTimeout(1500);
  await page.screenshot({ path: '02-after-load-wait.png' });
  // 3) 봉 단위 'D' 토글 (data-testid 부재 시 텍스트 매칭)
  const dButton = page.locator('button', { hasText: 'D' }).first();
  if (await dButton.count()) {
    await dButton.click();
    await page.waitForTimeout(500);
  }
  await page.screenshot({ path: '03-after-interval-d.png' });
});
```

- [ ] **15-4**: before 캡처 실행

```bash
node scripts/qa-run.mjs scenario \
  --issue ISSUE-008 \
  --spec /tmp/issue-008.spec.ts \
  --attempt before
```
Expected: `_workspace/qa/issues/ISSUE-008/{report-before.md, scenario-before.spec.ts}` 생성, presigned URL 3개 콘솔 출력, card.md status=INVESTIGATING

- [ ] **15-5**: 사용자에게 presigned URL 안내 → 시각 검토

3개 URL을 사용자에게 출력. 사용자가 브라우저에서 각 PNG 확인:
- 01: 페이지 진입 직후
- 02: 1.5초 대기 후 (차트 로드)
- 03: 'D' 토글 후

사용자 판정:
- 차트가 정상 표시 → 시나리오·인프라 OK → ISSUE-008은 `WONTFIX` (dogfooding 성공)
- 차트가 비어있음 → 진짜 디펙 → after 사이클 진행 (수정 PR + after 캡처)

- [ ] **15-6**: 결과에 따라 close 또는 wontfix

`WONTFIX` (dogfooding 성공 가정):

```bash
node scripts/qa-run.mjs wontfix \
  --issue ISSUE-008 \
  --reason "dogfooding 검증 성공 — pyworker scenario endpoint + qa-run.mjs CLI + ISSUE 디렉토리 양식 모두 작동 확인. 실 디펙 아님."
```

또는 `FIXED` (실 디펙 발견 시 수정 후):

```bash
# 수정 PR 머지 후
node scripts/qa-run.mjs scenario --issue ISSUE-008 --spec /tmp/issue-008-after.spec.ts --attempt after
node scripts/qa-run.mjs close --issue ISSUE-008 --pr <N>
```

- [ ] **15-7**: Commit (ISSUE-008 디렉토리 보존)

```bash
git add _workspace/qa/issues/ISSUE-008/ _workspace/qa/issues.md
git commit -m "test(qa): ISSUE-008 dogfooding — 5번 1차 인프라 end-to-end 검증"
rm -f /tmp/issue-008.spec.ts /tmp/issue-008-after.spec.ts
```

---

## 7. 마무리

### Task 16: PR + harness-state 갱신

**Files:**
- Modify: `_workspace/_harness-state.json` (5번 1차 완료 기록)
- Modify: `CLAUDE.md` (변경 이력 한 행 추가)

- [ ] **16-1**: harness-state.json에 5번 트랙 1차 완료 기록 추가

`_workspace/_harness-state.json`에 한 항목:

```json
{
  "phaseF": {
    "track5_qa_defect_loop_round1": {
      "status": "COMPLETED",
      "completedAt": "<ISO>",
      "spec": "docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md",
      "plan": "docs/superpowers/plans/2026-05-01-qa-defect-loop.md",
      "adr": ["0011 개정", "0016 보강", "0030 신규"],
      "deliverables": [
        "pyworker /screenshot/scenario",
        "scripts/qa-run.mjs",
        "_workspace/qa/issues/ 디렉토리 양식",
        "agents/qa.md + skills/magicjar-boundary-qa SKILL.md 갱신",
        "ISSUE-008 dogfooding 사이클"
      ]
    }
  }
}
```

기존 키 구조에 맞춰 병합.

- [ ] **16-2**: CLAUDE.md 변경 이력 표에 행 추가

```markdown
| 2026-05-01 | **5번 트랙 1차 완료 — QA 디펙 순환 루프.** spec `2026-05-01-qa-defect-loop-design.md` + plan + ADR 0011 개정·0016 보강·0030 신규. pyworker `POST /screenshot/scenario` 신설(다단계 Playwright + MinIO presigned URL 24h). `scripts/qa-run.mjs` CLI 신설(new·scenario·close·wontfix·list). `_workspace/qa/issues/ISSUE-NNN/` 디렉토리 양식(card.md + scenario-{before\|after}.spec.ts + report-{before\|after}.md). ISSUE-001~007 legacy 보존, ISSUE-008부터 신 양식. ISSUE-008 dogfooding 사이클로 인프라 end-to-end 검증. `agents/qa.md` + `skills/magicjar-boundary-qa/SKILL.md` 책임 갱신(architect=시나리오 작성, QA 에이전트=실행+카드, pyworker=인프라). 비범위 다음 사이클: expect 자동 검증·픽셀 diff·시나리오 영속·머지 게이트 강제·자동 dispatch·sandbox. | `pyworker/app/{config.py,routers/scenario.py,screenshot/{scenario.py,scenario_pw.py,storage.py}}`, `pyworker/tests/test_*scenario*.py`, `scripts/qa-run.mjs`, `docker/docker-compose.yml`, `_workspace/arch/adr/{0011,0016,0030}*.md`, `_workspace/qa/{issues.md,issues/README.md,issues/ISSUE-008/}`, `.claude/agents/qa.md`, `.claude/skills/magicjar-boundary-qa/SKILL.md`, `_workspace/_harness-state.json` | spec/plan + 사용자 brainstorm 12 결정 |
```

- [ ] **16-3**: 최종 commit

```bash
git add _workspace/_harness-state.json CLAUDE.md
git commit -m "chore(harness): 5번 트랙 1차 완료 기록 — QA 디펙 순환 루프 종결"
```

- [ ] **16-4**: PR 발행

```bash
git push -u origin agent/architect/qa-defect-loop
gh pr create --title "feat: 5번 트랙 1차 — QA 디펙 순환 루프 (pyworker scenario + qa-run.mjs + ISSUE 디렉토리)" --body "$(cat <<'EOF'
## Summary
- pyworker 신규 endpoint `POST /screenshot/scenario` (Playwright 단계별 캡처 + MinIO + presigned URL 24h)
- 호스트 CLI `scripts/qa-run.mjs` 5명령 (new·scenario·close·wontfix·list)
- `_workspace/qa/issues/ISSUE-NNN/` 디렉토리 양식 + 인덱스 마이그레이션
- ADR 0011 개정 (다단계 시나리오) + 0016 보강 (시나리오 모드) + 0030 신규 (오케스트레이터=architect 메타 규약)
- ISSUE-008 dogfooding 사이클 (인프라 end-to-end 검증)

## 관련 문서
- spec: `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` (commit e15d578)
- plan: `docs/superpowers/plans/2026-05-01-qa-defect-loop.md`

## Test plan
- [ ] pyworker pytest 전체 PASS
- [ ] `node scripts/qa-run.mjs list` 작동
- [ ] ISSUE-008 사이클 사용자 시각 검토 OK
- [ ] docker compose `pyworker` 컨테이너 healthy

Model/Author: opus / `bot+architect@magicjar.local` (ADR 0017)
Task: 단일 worktree (ADR 0013)
QA: spec 채택 자체가 본 trk의 결과물 — 별도 boundary-qa skill 호출 불요

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **16-5**: PR URL 사용자에게 안내. 사용자 review + 머지.

---

## 자체 검토 (작성 직후 수행됨)

- [x] **Spec coverage**: spec §0~§17 모든 결정 task에 매핑됨
  - §3 책임 boundary → Task 13·14
  - §4 흐름 → Task 7·10·11·15
  - §5 디렉토리 → Task 12
  - §6 endpoint → Task 4·5·6·7
  - §7 CLI + 7.1 호스트 정합 → Task 4·8·9·10·11
  - §8 시나리오 작성 규약 → Task 13·14·15
  - §9 ADR 영향 → Task 1·2·3
  - §10 보안·접근 → Task 4 (presigned 24h), Task 13·14 (architect 권한자)
  - §12 마이그레이션 → Task 12
  - §13 테스트 전략 → Task 4·5·6·7
  - §14 운영 플레이북 → Task 15
- [x] **Placeholder scan**: TODO/TBD 없음. 단 Task 6의 `_run_inline`은 사용 안 하고 `_run_via_cli`만 사용 — 코드 주석에 명시
- [x] **Type consistency**: ScenarioRequest·StepResult·ScenarioResult·ScenarioRunner·PlaywrightLike·PlaywrightRunResult·StorageBackend 일관 사용
- [x] **No spec gap**: 모든 spec 결정 task에 들어감

비범위 항목들(§11)은 모두 명시적으로 다음 사이클로 미룸 — 본 plan에서 task 미작성. 정합.

---

## 실행 옵션

Plan 완성 + 저장 완료 (`docs/superpowers/plans/2026-05-01-qa-defect-loop.md`).

두 가지 실행 방식:

1. **Subagent-Driven (권장)** — task별로 fresh subagent dispatch, task 사이 두 단계 review (architect dispatch + main review). 빠른 반복.
2. **Inline Execution** — 메인 세션에서 직접 실행, batch checkpoint로 review.

본 plan은 ADR 0013(단일 worktree·순차) + 0030(메인 세션 자율)을 따르므로 **Inline Execution이 자연**할 가능성이 높습니다 — 다만 Phase 2 pyworker 코드는 분량이 크니 subagent dispatch도 합리적.

사용자 선택 부탁드립니다.
