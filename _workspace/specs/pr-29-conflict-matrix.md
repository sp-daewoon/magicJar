# PR-29 충돌 행렬 (병렬 디스패치 결정 근거)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-08
- 정합 SoT: `pr-29-w1-close-design.md` v1 + `pr-29-dispatch-spec.md` v1 + ADR 0013·0014·0015·0016·0018·0050
- 사용자 메모리 정합:
  - `feedback_orchestrator_active_parallel.md` — architect "직렬 권장" 답습 금지. add-only 충돌은 진짜 병렬 가능
  - `feedback_3tier_hierarchy.md` — 오케스트라 → architect → 실무 sub-agents (병렬)
  - `feedback_kis_first_policy.md` — KIS endpoint·메시지 형식은 open-trading-api raw fetch 의무
  - `feedback_dev_priority_qa_batch.md` — minor 정정 매달리지 말 것 + QA 누적 후 일괄
  - `feedback_autonomy.md` — Claude 자율 실행

---

## §1. 결론 (한 줄)

> **PR-29a (backend-core, T-MS1) + PR-29b (market-data, T-CA1) + PR-29c (frontend, T-S1 잔재) 3 PR 적극 병렬 권장 (시나리오 A — PR-29a + PR-29b + PR-29c 동시 디스패치, 머지 순서는 임의). 영역이 batch+api+consumer+domain(MS) vs batch+api+domain(CA) vs frontend(S1)로 격리. modify-shared 1건(CHANGELOG.md)은 ADR 0050 슬롯 채번룰 다섯 번째 적용 + HTML 주석 anchor 5건 hunk 분리 (PR-21·PR-11·PR-10 효과 입증). Flyway V 번호 V6/V7/V8 분배 LOCK. KafkaTopicConfig는 PR-29b 단독 modify로 LOCK. add-add 0% 사전 검증 PASS. PR-29d (R3 일괄 테스트)는 a·b·c 머지 후 직렬.**

---

## §2. 인터페이스 동결 시점

본 spec 4종(`pr-29-w1-close-design.md` + `pr-29-dispatch-spec.md` + 본 문서 + `_workspace/backlog.md` 갱신)이 PR로 머지되는 순간 = 인터페이스 동결.

동결되는 계약:
- `pr-29-w1-close-design.md` §3 (T-MS1 도메인+어댑터+SQL+스케줄러 LOCK)
- `pr-29-w1-close-design.md` §4 (T-CA1 도메인+어댑터+SQL+잡 LOCK)
- `pr-29-w1-close-design.md` §5 (T-S1 잔재 검증 범위 LOCK — 코드 신규 0 권장)
- `pr-29-dispatch-spec.md` §2 (PR-29a 작업 범위 LOCK)
- `pr-29-dispatch-spec.md` §3 (PR-29b 작업 범위 LOCK)
- `pr-29-dispatch-spec.md` §4 (PR-29c 작업 범위 LOCK)
- `pr-29-dispatch-spec.md` §5 (PR-29d 작업 범위 LOCK)
- `pr-29-dispatch-spec.md` §6 (영역 분배 LOCK ★)
- `pr-29-dispatch-spec.md` §7 (CHANGELOG 슬롯 row 5 anchor + 4 row LOCK)
- ADR 0013·0014·0015·0016·0018·0050 (모두 본 PR 정합)

**인터페이스 동결 즉시** 3 sub-agent (backend-core·market-data·frontend) 동시에 자기 worktree에서 작업 시작 가능.

---

## §3. Flyway V 번호 분배 (V6 / V7 / V8 LOCK)

**현황 검증** (2026-05-08):
- `api/src/main/resources/db/migration/`: V1 baseline·V2 onboarding·V3 watchlist·V4 instrument search·V5 instrument KIS-first 70+ 컬럼 (총 5건)
- `batch/src/main/resources/db/migration/`: 0건 (api 모듈 단일 master)
- **빈 번호 가용**: V6, V7, V8 (이상)

**분배 LOCK**:

| V 번호 | 위치 | sub-agent | 책임 |
|---|---|---|---|
| **V6** | `api/src/main/resources/db/migration/V6__market_calendar_and_reject_audit.sql` | PR-29a backend-core | market_calendar + order_reject_audit + orders ALTER (한 파일에 묶음 — T-MS1 단일 트랜잭션) |
| **V7** | `batch/src/main/resources/db/migration/V7__corp_action.sql` | PR-29b market-data | corp_action 테이블 |
| **V8** | `batch/src/main/resources/db/migration/V8__candle_adjusted.sql` | PR-29b market-data | candle ALTER (8 컬럼 + adjustment_factor + volume_adjusted) + UPDATE backfill |

**위치 분리 사유**:
- V6은 api 모듈 (T-MS1은 api·consumer 중심 — order_reject_audit는 api 영역)
- V7·V8은 batch 모듈 (T-CA1은 batch 중심 — sync job + adjustment job + entity)
- Flyway는 모듈별 별도 path 인식 — 충돌 0

**중복 V 번호 risk = 0** — PR-29a (V6, api) + PR-29b (V7+V8, batch) 위치도 분리.

---

## §4. 파일 단위 충돌 행렬

### §4-1. PR-29a (backend-core) vs PR-29b (market-data) vs PR-29c (frontend) 변경 파일

| 파일/디렉토리 | PR-29a | PR-29b | PR-29c | 충돌 유형 | 결정 |
|---|---|---|---|---|---|
| `domain/.../market/session/*` | **신규** | 무관 | 무관 | add-only | **격리** (PR-29a 단독) |
| `domain/.../market/order/RejectReason.kt` | **신규** | 무관 | 무관 | add-only | **격리** (PR-29a 단독) |
| `domain/.../instrument/corp_action/*` | 무관 | **신규** | 무관 | add-only | **격리** (PR-29b 단독) |
| `domain/.../instrument/Instrument.kt` (기존 PR-21) | 변경 0 | 변경 0 | 변경 0 | (변경 0) | **무관** |
| `domain/.../instrument/port/InstrumentRepository.kt` (기존 PR-21b) | 변경 0 | 변경 0 권고 | 변경 0 | (변경 0) | **무관** |
| `api/.../market/session/KrxMarketSessionResolver.kt` | **신규** | 무관 | 무관 | add-only | **격리** |
| `api/.../persistence/MarketCalendar*.kt` | **신규** | 무관 | 무관 | add-only | **격리** |
| `api/.../infrastructure/dart/*` | 무관 | **신규** | 무관 | add-only | **격리** |
| `api/.../persistence/InstrumentJpaEntity.kt` (기존 PR-21b 83 필드) | 변경 0 | 변경 0 권고 | 무관 | (변경 0) | **무관** |
| `api/src/main/resources/db/migration/V6__market_calendar_and_reject_audit.sql` | **신규** | 무관 | 무관 | add-only | **격리** |
| `api/src/main/resources/application.yml` | env add (MARKET_*) | 변경 0 | 무관 | modify-shared | **PR-29a 단독 LOCK** (dispatch §6-2) |
| `batch/src/main/resources/db/migration/V7__corp_action.sql` | 무관 | **신규** | 무관 | add-only | **격리** |
| `batch/src/main/resources/db/migration/V8__candle_adjusted.sql` | 무관 | **신규** | 무관 | add-only | **격리** |
| `batch/.../infrastructure/kis/KisChkHolidayAdapter.kt` | **신규** | 무관 | 무관 | add-only | **격리** |
| `batch/.../infrastructure/kis/KisKsdInfoAdapter.kt` | 무관 | **신규** | 무관 | add-only | **격리** |
| `batch/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` (기존 PR-21a) | 변경 0 | 변경 0 권고 | 무관 | (변경 0) | **무관** |
| `batch/.../job/market_calendar/*` | **신규** | 무관 | 무관 | add-only | **격리** |
| `batch/.../job/corp_action/*` | 무관 | **신규** | 무관 | add-only | **격리** |
| `batch/.../persistence/CorpAction*.kt` | 무관 | **신규** | 무관 | add-only | **격리** |
| `batch/.../bootstrap/MarketCalendarSeedRunner.kt` | **신규** | 무관 | 무관 | add-only | **격리** |
| `batch/src/main/resources/market-calendar-seed.json` | **신규** | 무관 | 무관 | add-only | **격리** |
| `batch/src/main/resources/application.yml` | 변경 0 | env add (CORP_ACTION_*) | 무관 | modify-shared | **PR-29b 단독 LOCK** (dispatch §6-2) |
| `consumer/.../application/market/DailyCloseFinalizer.kt` | **신규** | 무관 | 무관 | add-only | **격리** |
| `consumer/.../application/market/TickFallbackEmitter.kt` | **신규** | 무관 | 무관 | add-only | **격리** |
| `consumer/.../application/market/SimpleMarketSessionResolver.kt` (PR-9b 기존) | **modify or delete** | 변경 0 | 무관 | modify-own | **PR-29a 단독 LOCK** |
| `consumer/.../config/KafkaTopicConfig.kt` | 변경 0 (session 토픽 본 사이클 비범위) | **modify** (corp_action.applied.v1 add) | 무관 | modify-own | **PR-29b 단독 LOCK** (dispatch §6-3) |
| `consumer/src/main/resources/application.yml` | env add (DailyCloseFinalizer cron + TickFallbackEmitter cron) | 변경 0 | 무관 | modify-shared | **PR-29a 단독 LOCK** (dispatch §6-2) |
| `frontend/**` | 변경 0 | 변경 0 | **modify** (1줄~10줄 minor 정정만) | modify-own | **격리** (PR-29c 단독) |
| `.env.example` | env add (MAGICJAR_MARKET_*) | 변경 0 권고 (OPENDART_API_KEY 이미 존재) | 변경 0 | modify-shared | **PR-29a 단독 LOCK** (dispatch §6-2) |
| `CHANGELOG.md` | row in-place edit (anchor `<!-- pr-29-slot-backend-core -->` 위) | row in-place edit (anchor `<!-- pr-29-slot-market-data -->` 위) | row in-place edit (anchor `<!-- pr-29-slot-frontend -->` 위) | modify-shared | **ADR 0050 슬롯 채번룰** (architect 사전 발행 + sub-agent in-place edit) |
| `_workspace/backlog.md` | 변경 0 | 변경 0 | 변경 0 | (변경 0) | **PR-29 architect 본 PR에서 §21 신설 + 처리 마킹** (sub-agent 변경 금지) |

### §4-2. modify-shared 결론

modify-shared 5건:
1. **CHANGELOG.md** — anchor 5건 hunk 분리로 충돌 0 (입증 — PR-21·PR-11·PR-10)
2. **api application.yml** — PR-29a 단독 LOCK
3. **batch application.yml** — PR-29b 단독 LOCK
4. **consumer application.yml** — PR-29a 단독 LOCK
5. **.env.example** — PR-29a 단독 LOCK

→ **modify-shared 충돌 0% 보장**.

### §4-3. add-add 점검

- **domain 모듈**: PR-29a `domain/.../market/*` 신규 vs PR-29b `domain/.../instrument/corp_action/*` 신규 → 디렉토리 다름 → add-add 0
- **api 모듈**: PR-29a `api/.../market/session/*` + `api/.../persistence/MarketCalendar*` 신규 vs PR-29b `api/.../infrastructure/dart/*` 신규 → 디렉토리 다름 → add-add 0
- **batch 모듈**: PR-29a `batch/.../infrastructure/kis/KisChkHolidayAdapter` + `batch/.../job/market_calendar/*` + `batch/.../bootstrap/MarketCalendarSeedRunner` vs PR-29b `batch/.../infrastructure/kis/KisKsdInfoAdapter` + `batch/.../job/corp_action/*` + `batch/.../persistence/CorpAction*` → 같은 모듈이지만 디렉토리·파일명 다름 → add-add 0
- **consumer 모듈**: PR-29a `consumer/.../application/market/*` 신규 vs PR-29b 변경 0 (KafkaTopicConfig modify-own만) → add-add 0
- **frontend 모듈**: PR-29c 단독 → add-add 0

→ **add-add 충돌 0% 사전 검증 PASS** (backlog §5 backend-core 영역 침범 패턴 회피).

---

## §5. 시나리오 비교

### §5-1. 시나리오 A (적극 병렬 — 권장 ★)

```
PR-29 (architect) 머지
  ↓
[PR-29a + PR-29b + PR-29c 동시 디스패치]
  ↓
3 PR 임의 순서 머지 (rebase + author 분리)
  ↓
PR-29d (R3 일괄) 디스패치 + 머지
  ↓
v3.1.0 태그 컷 + release/v3.1 베이스라인 push
```

**예상 시간**: 1.5~2 사이클 (PR-29a~c 병렬 1 사이클 + PR-29d 1 사이클).

**리스크**:
- modify-shared 5건 모두 LOCK 분배 + anchor — 충돌 0% 입증 (PR-21·11·10·9 누적 효과)
- add-add 0% 사전 검증 PASS

### §5-2. 시나리오 B (PR-29a → PR-29b → PR-29c 직렬)

직렬 머지. **거부 사유**:
- 사용자 메모리 `feedback_orchestrator_active_parallel.md` — "architect 직렬 권장 답습 금지"
- add-add·modify-shared 모두 0% 사전 검증 PASS인데 직렬은 시간 낭비
- v3.1.0 릴리즈 게이트 도달 지연

### §5-3. 시나리오 C (PR-29c 단독 선행 → PR-29a + PR-29b 병렬)

frontend 단독 검증 사이클 후 backend 2 PR 병렬. **거부 사유**:
- PR-29c는 frontend 영역 단독이라 backend 2 PR과 독립 — 동시 진행에 risk 0
- 시간 낭비

→ **시나리오 A 권장 LOCK**.

---

## §6. 적극 병렬 권장 시나리오 (사용자 메모리 적극 적용)

`feedback_orchestrator_active_parallel.md` 정합:

> "architect 직렬 권장 답습 금지. 사용자 의도+실제 충돌 행렬 재검토 후 적극 병렬화. add-only 충돌은 진짜 병렬 가능"

본 PR-29 충돌 행렬 재검토 결과:
- **add-only 비율**: PR-29a 95%·PR-29b 95%·PR-29c 100% (frontend는 100% modify-own minor)
- **modify-shared**: 5건 (CHANGELOG·yaml 3건·.env.example) → 모두 sub-agent 단독 LOCK 또는 anchor hunk 분리
- **modify-own**: 2건 (SimpleMarketSessionResolver·KafkaTopicConfig) → sub-agent 단독 LOCK

**결론**: 시나리오 A (3 PR 동시 디스패치) **적극 권장**. 사용자 메모리 정합.

---

## §7. 머지 순서

### §7-1. 권장

1. **PR-29 (architect)** — 본 spec PR (docs only) 선행 머지
2. **[PR-29a + PR-29b + PR-29c 동시 디스패치]**
   - 셋 다 디스패치 후 자기 worktree에서 작업
   - 머지 순서는 임의 (셋 다 add-only + modify-own + LOCK 분배 → rebase 충돌 0%)
3. **PR-29d (R3 일괄)** — a·b·c 모두 머지 후 직렬

### §7-2. 머지 방식

`--rebase` 의무 (헌법 §5 + ADR 0034). squash 금지.

---

## §8. 운영 체크 (헌법 §1·§2)

- ✅ 본 PR-29 (architect) 시작 시 `task.mjs start`
- ✅ branch agent/architect/pr-29-w1-close-spec
- ✅ author bot+architect@magicjar.local
- ✅ KIS-first raw fetch 7 endpoint 출처 인용 (헌법 §3 — design §3-3·§4-3)
- ✅ DART OPENDART 출처 인용 (design §4-4)
- ✅ CHANGELOG anchor 5건 + row 5건 사전 발행 (헌법 §4)
- ✅ rebase 머지 의무 (헌법 §5)
- ✅ 룰 2 단일 책임 — 본 PR docs only

---

## §9. 변경 이력

- v1 (2026-05-08, architect) — 초안. 시나리오 A 적극 병렬 권장 + Flyway V6/V7/V8 분배 LOCK + add-add 0% 검증 + KafkaTopicConfig PR-29b 단독 LOCK.
