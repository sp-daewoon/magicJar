# PR-38 T-PAT 진행 plan (PR 시리즈 + 우선순위 + LOC 추정 + 디스패치 매트릭스)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-10
- 정합 SoT: `pr-38-w2-t-pat-design.md` v1 + `pr-38-dispatch-spec.md` v1 + `pr-38-conflict-matrix.md` v1 + `pr-38-kis-pattern-raw-verification.md` v1
- 본 문서: orchestrator가 sub-agent 디스패치 시 우선순위·병렬·LOC 추정 의사결정에 사용.

---

## §1. PR 시리즈 개요 (T-IND PR-32 + T-DSL PR-35 패턴 정합)

| PR | 담당 | 분량 | 라운드 | 우선순위 | 머지 게이트 |
|---|---|---|---|---|---|
| **PR-38** | architect | M (spec 5 파일 + CHANGELOG 슬롯 사전 발행) | spec only(R0) | **현재** (본 PR) | 사용자 1회 결재 (§10 결재 항목 10건) |
| **PR-39a** | strategy-engine | L (~1500-2000 LOC) | **R1+R2 통합** | **PR-38 머지 후 병렬 1** | 단위 테스트 5종 GREEN |
| **PR-39b** | backend-core | M (~600-900 LOC) | **R1+R2 통합** | **PR-38 머지 후 병렬 1 (PR-39a와 동시 발사)** | REST endpoint 200 OK + DSL stub 삭제 + JvmPatternRecognizer bean 활성 |
| **PR-40** | frontend (메인) + strategy-engine + backend-core (보조) | M~L (~1000-1300 LOC) | R3 일괄 | PR-39a/b 머지 후 | 57 fixture e2e + LWC marker e2e + 정확성 fixture 일괄 GREEN |

---

## §2. PR-39a strategy-engine 분량 추정

### 2-1. 파일별 LOC 추정

| 파일 | 추정 LOC | 사유 |
|---|---|---|
| `consumer/.../pattern/JvmPatternRecognizer.kt` | ~150 | PatternRecognizer 구현 + Map registry 등록 + 3 메서드(recognize × 2 overload + consecutive) + KIS L48·L57 정합 가드 |
| `consumer/.../pattern/util/CandleMath.kt` | ~80 | KIS 7 helper (object) — body·upper_shadow·lower_shadow·range·is_bullish·is_bearish·avg_body |
| `consumer/.../pattern/helper/SimpleTripleHelper.kt` | ~80 | KIS L562 _simple_triple port — 3양봉/3음봉 + close 단조 검증 |
| `consumer/.../pattern/helper/ConsecutiveDirectionCalculator.kt` | ~50 | magicJar 자체 (KIS 미존재) — `consecutive(up|down)` Int 응답 |
| `consumer/.../pattern/detector/single/{14 파일}.kt` | ~600 | Single 14 detector — R1 active 5(Doji·Hammer·HangingMan·InvertedHammer·ShootingStar) + R2 stub 9. 평균 R1=60줄·R2=20줄 |
| `consumer/.../pattern/detector/double/{16 파일}.kt` | ~450 | Double 16 detector — R1 active 1(Engulfing) + R2 stub 15. 평균 R1=80줄·R2=20줄 |
| `consumer/.../pattern/detector/triple/{27 파일}.kt` | ~700 | Triple+ 27 detector — R1 active 4(MorningStar·EveningStar·ThreeWhiteSoldiers·ThreeBlackCrows) + R2 stub 23 (그 중 simple_triple delegation 7건은 helper 사용). 평균 R1=80줄·R2=20줄 |
| `api/.../config/PatternRecognizerConfig.kt` | ~30 | api 모듈에 JvmPatternRecognizer bean 등록 (T-IND IndicatorEngineConfig 정합 — strategy-engine 단독 LOCK) |
| `consumer/src/test/kotlin/.../PatternFixtureTest.kt` | ~250 | R1 5종 fixture (Doji·Hammer·Engulfing·MorningStar·ThreeWhiteSoldiers) — KIS raw 룰 1:1 정합 검증 |
| `consumer/src/test/kotlin/.../JvmPatternRecognizerTest.kt` | ~150 | recognize multi-pattern · consecutive · 캔들 부족 가드 · 미지원 패턴 graceful · cache hit 검증 |
| `consumer/src/test/kotlin/.../PatternRegistryTest.kt` | ~50 | `assertEquals(57, detectors.size)` + catalog id 1:1 매핑 검증 |
| `CHANGELOG.md` (in-place edit) | ~1 row | ADR 0050 정합 (작성: strategy-engine) |
| **합계 추정** | **~2590 LOC** | KIS-first raw fetch 정합 (자체 추론 0 — 57 detector 비중 높음) |

### 2-2. wallclock 추정

- KIS raw fetch + 65 → 57 매핑 정합 검증: 30분
- 7 helper(CandleMath) + 2 helper(SimpleTriple·ConsecutiveDirection): 1시간
- Single 14 detector port (R1 5 active + R2 9 stub): 2시간
- Double 16 detector port (R1 1 + R2 15 stub): 1.5시간
- Triple+ 27 detector port (R1 4 + R2 23 stub + simple_triple delegation): 2.5시간
- JvmPatternRecognizer + PatternRecognizerConfig (api 모듈): 1시간
- 단위 테스트 5종 (R1 5종 fixture): 1.5시간
- PR 본문 + CHANGELOG: 15분
- **wallclock 추정: ~10시간** (1.5일 작업)

### 2-3. 분량 초과 fallback

PR-39a 분량 초과 인지 시 (orchestrator 사전 동의 필요):
- PR-39a-1: util + helper + Single 14 + 단위 테스트 (Doji·Hammer fixture)
- PR-39a-2: Double 16 + Triple+ 27 + JvmPatternRecognizer + 나머지 테스트

backend-core PR-39b는 PR-39a-1 진행 중에도 동시 가능 (domain port 의존만).

---

## §3. PR-39b backend-core 분량 추정

### 3-1. 파일별 LOC 추정

| 파일 | 추정 LOC | 사유 |
|---|---|---|
| `domain/.../pattern/{PatternMatch,Direction,DetectorResult,PatternCatalog,PatternException}.kt` | ~200 | 5 도메인 클래스 + 57 catalog Spec 등록 + sealed exception |
| `domain/.../pattern/port/{PatternRecognizer,PatternDetector}.kt` | ~80 | 2 interface (T-DSL stub 시그니처 호환 의무) |
| `api/.../controller/PatternController.kt` | ~150 | GET /api/patterns (Swagger 4단 + 한국어 설명 + example 값) + 캔들 read + 캐시 hit 처리 |
| `api/.../controller/dto/PatternMatchDto.kt` | ~60 | 직렬화 친화 DTO + PatternResponseDto wrapper |
| `consumer/.../pattern/cache/RedisPatternCacheAdapter.kt` | ~120 | Redis read/write + patternsHash 계산 + TTL 매트릭스 (T-IND 정합) |
| `consumer/.../strategy/PatternRecognizerStub.kt` | **삭제 -60줄** | T-DSL 시점 stub 청소 (5-A 정합) |
| `_workspace/strategy/pattern-catalog.md` | ~10 줄 갱신 | "R2 사이클 보강 대상 47 stub" 표 갱신 (R1 10 active vs R2 47 stub 매트릭스) |
| `CHANGELOG.md` (in-place edit) | ~1 row | ADR 0050 정합 (작성: backend-core) |
| **합계 추정** | **~620 LOC + 삭제 60** | domain port 정합 + REST + DSL stub 청소 |

### 3-2. wallclock 추정

- domain pattern/* 5 파일 + port 2 파일: 1시간
- PatternController + DTO: 1시간
- RedisPatternCacheAdapter: 30분
- PatternRecognizerStub 삭제 + smoke 검증: 15분
- pattern-catalog.md 갱신: 15분
- **wallclock 추정: ~3시간**

---

## §4. PR-40 R3 frontend·통합 분량 추정 (참고용 — 별도 dispatch 발행)

| 영역 | 추정 LOC | 사유 |
|---|---|---|
| `frontend/src/components/chart/PatternMarker.tsx` | ~250 | LWC v4 setMarkers + BULL/BEAR 색상·shape 분기 + symbol 표시 |
| `frontend/src/components/chart/PatternToggleMenu.tsx` | ~300 | 57 catalog id picker UI + Single/Double/Triple+ 카테고리 분류 |
| `frontend/src/api/patterns.ts` | ~80 | TanStack Query usePatterns hook |
| `frontend/src/pages/Symbol/SymbolDetail.tsx` 통합 | ~50 | LWC chart + PatternMarker overlay 라인 1~5 |
| `consumer/src/test/kotlin/.../PatternAccuracyFixtureTest.kt` | ~600 | 57 detector fixture 일괄 — R1 10건 + R2 47 stub 모두 호출 가능 검증 (NEUTRAL graceful) |
| `consumer/src/test/kotlin/.../PatternE2ELwcFixtureTest.kt` | ~200 | LWC markers 출력 e2e (REST 호출 + JSON 응답 → setMarkers 호출 가능 형태 검증) |
| **합계 추정** | **~1480 LOC** | R3 일괄 — 별도 dispatch-spec PR-39 머지 후 발행 |

---

## §5. 디스패치 매트릭스 (T-IND·T-DSL 정합)

### 5-1. 시나리오 A — 완전 병렬 (architect 권장)

```
T0: PR-38 spec 머지
   │
   ├─ T0+5min: PR-39a strategy-engine background 발사
   └─ T0+5min: PR-39b backend-core background 발사 (동시)
   │
T0+3h:  PR-39b 완료 (먼저 — backend-core 분량 적음)
   │ - rebase main + push + PR open
   │
T0+10h: PR-39a 완료
   │ - rebase main(PR-39b 머지 후) + push + PR open
   │
T0+12h: PR-39a 머지 (R1 10 active + R2 47 stub 등록)
   │
T0+12h+1h: PR-40 R3 frontend·strategy-engine·backend-core 발사 (또는 다음 사이클)
```

**병렬 시 wallclock 단축**: 직렬 ~13h → 병렬 ~10h (PR-39a 단독 시간) — **23% 단축** (T-IND·T-DSL은 40% 이상 단축 — T-PAT은 PR-39a 비중이 압도적이라 단축 폭 작음 BUT 직렬보다는 우월).

### 5-2. 시나리오 B — 직렬 (사용자 명시 시 fallback)

PR-39b 머지 → PR-39a 시작. 보장은 강하지만 wallclock 길어짐. architect 비권장 — 충돌 행렬에서 modify-shared 1건(PatternRecognizerStub.kt — backend-core 단독 LOCK), add-only 100% 정합이라 병렬 안전.

### 5-3. 권장 결정

**시나리오 A 채택 (사용자 메모리 `feedback_orchestrator_active_parallel.md` 정합)**: orchestrator는 architect "직렬 권장" 답습 금지. 본 PR-39 충돌 행렬 modify-shared 1건만 + backend-core 단독 LOCK이라 진짜 병렬 안전.

---

## §6. 머지 순서 + 인터페이스 동결

### 6-1. 인터페이스 동결 시점

본 spec PR-38 머지 즉시 — 양쪽 sub-agent가 동시에 spec §6-3(domain port) + §6-4(REST endpoint) + §6-5(Redis 캐시) + §5(stub 1줄 교체) 보고 시작 가능.

### 6-2. 머지 순서

| 순서 | PR | 시점 | 비고 |
|---|---|---|---|
| 1 | PR-38 | 현재 — 사용자 결재 후 | spec 5 파일 + CHANGELOG 슬롯 사전 발행 |
| 2a | PR-39a strategy-engine | PR-38 머지 후 병렬 1 | 분량 큰 쪽 — 57 detector + JvmPatternRecognizer + util/helper |
| 2b | PR-39b backend-core | PR-38 머지 후 병렬 1 (동시 발사) | 분량 작은 쪽 — domain port + REST + DSL stub 삭제 |
| 3 | PR-40 | PR-39a/b 머지 후 별도 dispatch | R3 frontend overlay + 57 fixture e2e + LWC markers + 정확성 fixture 일괄 |

### 6-3. main 보호 + rebase 규약

- 본 PR-38(spec) → PR-39a/b → PR-40 모두 `--rebase` 정책 (CLAUDE.md + agent-charter §5 정합)
- pre-push hook이 `./gradlew check` 자동 실행 — main 보호 의무
- PR-39a·PR-39b 동시 진행 시 두 번째 머지자가 git rebase + 충돌 0(modify-shared 1건은 backend-core 단독 LOCK이라 strategy-engine은 touch 0)

---

## §7. 분량 재검토 시점

| 시점 | 검토 항목 | 행동 |
|---|---|---|
| PR-39a 작업 시작 24h 후 | LOC 진행률 50% 미만? | architect 호출 → fallback (PR-39a-1·PR-39a-2 분할 — §2-3 정합) |
| PR-39b 작업 시작 8h 후 | DSL stub 삭제 + REST 200 OK 미달성? | architect 호출 → 시그니처 검토 |
| PR-39a·PR-39b 머지 후 | DSL `pattern("hammer")` evaluate 시 EvalResult.signal=BUY 미동작? | architect+strategy-engine 합류 hotfix |

---

## §8. backlog 합류 항목 (post-PR-39)

본 PR-38 spec 결정 후 **R3·post-MVP로 미루는 항목**을 backlog에 합류 의무 (orchestrator 머지 후 backlog §갱신):

- R2 47 stub → active port (post-T-PAT — 사용자 dogfooding 빈도 따라 우선순위)
- 신뢰도 모델 (ML-based confidence — KIS 0/1 binary 대비 0.0~1.0 그라데이션)
- 사용자 정의 패턴 (Spring auto-collect 확장)
- pattern.detected.v1 Kafka publish (시그널 발생 시 고지) — T-NOTIFY W3 합류 시점
- LWC 박스 영역 시각화 (markers 외 — T-LWC W5 본격)
- pattern alias `up_down_gap_three_methods`·`downside_tasuki_gap`·`upside_tasuki_gap` 정밀화 (단순 tasuki_gap delegation → 전용 룰)

---

## §9. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-10 | PR-38 진행 plan v1 발행. PR-39a strategy-engine ~2590 LOC + PR-39b backend-core ~620 LOC + PR-40 R3 frontend ~1480 LOC 분량 추정 + 시나리오 A 완전 병렬 23% 단축 권장 + 인터페이스 동결 시점 = PR-38 머지. backlog 합류 6건. (architect, opus 4.7 1M) |
