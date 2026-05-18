# T-O 본 트랙 진입 — Phase 매트릭스 + sub-agent dispatch 계획 spec

> **위치**: `_workspace/specs/2026-05-18-to-main-track-entry-plan.md`
> **발행일**: 2026-05-18 KST
> **작성**: architect (사용자 의제 — "케이스 5 GREEN으로 충분 → T-O 본 트랙 진입")
> **상태**: Proposed — Phase 매트릭스·잔여 작업·sub-agent dispatch·dogfooding 케이스·release cut 시점 발행
> **연관 ADR**: 0068(T-O plan) · 0070(T-RECON+NOTIFY 묶음) · 0074(SettingsModal 풀세트) · 0077(OPSP8996) · 0067(T-RISK) — 본 spec 채택 시 **신규 ADR 0건** (ADR 0068 정합 spec/plan만)
> **AI 호출 0** — T-O 트랙 정책 (ADR 0023 §2-4 + 0066 §8 + 0067 §8 + 0068 §8)

---

## §0. 사용자 의제 (LOCK)

> "케이스 5 GREEN으로 충분 → T-O 본 트랙 진입 (시그널·체결 의제)" — 2026-05-18 최고 관리자

prereq spec(`2026-05-18-to-prereq-mock-prd-verify.md`) §6.3 진술에서 Phase 1·2·3 부분 완료 + 잔여 3건 명시:
- Phase 1 잔여: KisExecutionWsListener (ADR 0068 §14-6)
- Phase 3 잔여: OrdersPage (frontend 조회·취소 UI)
- Phase 4: dogfooding + 실증 evidence + release cut

본 spec은 prereq spec 진술 vs 실측 검증 → 실제 잔여 작업 도출 → sub-agent dispatch 계획.

---

## §1. 실측 매트릭스 (분류 A — prereq spec §6.3 진술 vs 실측)

본 §은 **모든 클래스·파일을 직접 grep + 정독**하여 합류 상태 검증. spec 진술과 실측이 모순되는 경우 실측 우선 (사용자 메모리 `feedback_verification_strict` 정합).

### §1.1 Phase 1 — strategy-engine (consumer + domain)

| 컴포넌트 | spec §6.3 진술 | 실측 (grep + 정독) | gap |
|---|---|---|---|
| `TradingModeGateImpl` | ✓ 완료 | ✅ `consumer/.../order/TradingModeGateImpl.kt` 합류. 3중 게이트(envMode + envAllowReal + consentPort) 정상. 단위 테스트 4 케이스 GREEN(`TradingModeGateImplTest`). | **0** |
| `KisOrderHttpAdapter` | ✓ 완료 | ✅ `consumer/.../order/KisOrderHttpAdapter.kt` 합류. mode→accountType→URL/tr_id/credential 4단 분기 정상. 단위 테스트 6 케이스 GREEN(`KisOrderHttpAdapterTest`). | **0** |
| `OrderRouterKafkaListener` | ✓ 완료 + Phase 2 합류 fix(PR #481) 후 케이스 5 GREEN | ✅ `consumer/.../order/OrderRouterKafkaListener.kt` 합류. **단** dogfooding 케이스 5 후속 결함 1건 (backlog §62 — `persistRequested` catch 메시지 misleading) 진행 중 별도 hotfix. | **0** (본 spec 범위 외 §62 별도) |
| `KisExecutionWsListener` | (ADR 0068 §14-6 미완) | **△ 클래스 자체 합류** (`consumer/.../order/KisExecutionWsListener.kt` 400 line + `processMessage` 흐름 분기 LOCK 정합 + `KisExecutionMessage` 26 컬럼 파싱 + 단위 테스트 5 케이스 GREEN). **그러나 KisWebSocketBridge wiring 미완** — `consumer/.../ws/KisWebSocketBridge.kt:489` line: `"H0STCNI0" -> log.info("KIS WS 체결통보 수신: trId=H0STCNI0 (handler 미구현 — T-O)")`. WS bridge가 KisExecutionWsListener.onWebSocketMessage()를 호출하지 않음. | **잔여 — wiring 1건** |
| `OrderIdempotencyGuardImpl` | (Phase 1 LOCK) | ✅ `consumer/.../order/OrderIdempotencyGuardImpl.kt` 합류. Redis SETNX 60s 정상. | **0** |
| `OrderFilledKafkaProducer`·`OrderRequestedKafkaProducer`·`OrderRejectedKafkaProducer`·`OrderCancelledKafkaProducer` | (Phase 1 LOCK) | ✅ 4건 모두 `consumer/.../order/` 합류. | **0** |
| `ConsumerOrderRequestRepositoryAdapter`·`ConsumerOrderAuditEventRepositoryAdapter` | (PR #481 Phase 2 합류 fix) | ✅ `consumer/.../persistence/` 6 파일 합류 (entity 2 + repository 2 + adapter 2). dogfooding 케이스 5 GREEN evidence. | **0** |
| `OrderExecutionRepository` consumer 측 합류 | (검증 의무) | **□ 검증 필요** — `OrderExecutionRepositoryAdapter`는 api 모듈 단독 거주(`api/persistence/order/OrderExecutionRepositoryAdapter.kt`). consumer 모듈은 PR #481에서 OrderRequest + OrderAuditEvent만 합류. `KisExecutionWsListener.handleExecution()`이 `OrderExecutionRepository` ObjectProvider 사용 — Phase 1 wiring 후 graceful fallback이 활성화될 risk. | **잔여 — ConsumerOrderExecutionRepositoryAdapter 합류 필요** |

**Phase 1 잔여 종합**: (a) KisWebSocketBridge wiring (WS body → onWebSocketMessage 라우팅) + (b) ConsumerOrderExecutionRepositoryAdapter 합류 (PR #481 동일 패턴).

### §1.2 Phase 2 — backend-core (api + Flyway + JPA + REST + STOMP)

| 컴포넌트 | spec §6.3 진술 | 실측 (grep + 정독) | gap |
|---|---|---|---|
| `OrderQueryService` | ✓ 완료 | ✅ `api/.../service/order/OrderQueryService.kt` 73 line 합류. | **0** |
| `OrderCancelService` | ✓ 완료 | ✅ `api/.../service/order/OrderCancelService.kt` 83 line 합류. | **0** |
| `RealTradingConsentController`·`Service` | ✓ 완료 | ✅ `api/.../controller/order/RealTradingConsentController.kt` 104 line + `RealTradingConsentService.kt` 47 line 합류. | **0** |
| Flyway V20 (`order_request`) | ✓ 완료 | ✅ `api/src/main/resources/db/migration/V20__order_request.sql` 존재 | **0** |
| Flyway V21 (`order_execution`) | ✓ 완료 | ✅ `V21__order_execution.sql` 존재 | **0** |
| Flyway V22 (`order_audit_event`) | ✓ 완료 | ✅ `V22__order_audit_event.sql` 존재 | **0** |
| Flyway V23 (`user_account.real_trading_consent_at` ALTER) | ✓ 완료 | ✅ `V23__user_account_consent.sql` 존재 | **0** |
| `OrderRequestJpaEntity`·`Repository`·`Adapter` (api 측) | (Phase 2 LOCK) | ✅ `api/.../persistence/order/` 3쌍 모두 합류 | **0** |
| `OrderExecutionJpaEntity`·`Repository`·`Adapter` (api 측) | (Phase 2 LOCK) | ✅ 3쌍 모두 합류 | **0** |
| `OrderAuditEventJpaEntity`·`Repository`·`Adapter` (api 측) | (Phase 2 LOCK) | ✅ 3쌍 모두 합류 | **0** |
| `RealTradingConsentJpaAdapter` | (Phase 2 LOCK) | ✅ `api/.../persistence/order/RealTradingConsentJpaAdapter.kt` 합류 | **0** |
| `OrderQueryController` | (REST 4 endpoint) | ✅ `api/.../controller/order/OrderQueryController.kt` 합류 | **0** |
| `OrderCancelController` | (REST 4 endpoint) | ✅ `api/.../controller/order/OrderCancelController.kt` 합류 | **0** |
| `StompOrderNotifier` | (ADR 0068 §15-4) | ✅ `api/.../messaging/StompOrderNotifier.kt` 합류. Kafka `order.requested/filled/rejected/cancelled.v1` 4 topic listen → STOMP `/topic/order.user.{userId}` fanout. groupId `magicjar-api-stomp-order` LOCK. | **0** |

**Phase 2 잔여 종합**: **0** (모두 합류 완료).

### §1.3 Phase 3 — frontend

| 컴포넌트 | spec §6.3 진술 | 실측 (grep + 정독) | gap |
|---|---|---|---|
| `RealTradingConsentToggle` | ✓ 완료 | ✅ `frontend/src/components/settings/RealTradingConsentToggle.tsx` + `RealTradingConsentSection.tsx` 합류 | **0** |
| `useUpdateRealTradingConsent` hook | (Phase 3 부수) | ✅ `frontend/src/api/hooks/useUpdateRealTradingConsent.ts` 합류 | **0** |
| `OrdersPage` | (ADR 0068 §16, 잔여) | ✅ `frontend/src/pages/orders/OrdersPage.tsx` 195 line 합류. 상태 필터·페이지네이션·STOMP 구독·useRealTradingConsent 배지·setup 안내 graceful 모두 정상 | **0** |
| `OrderDetailPage` | (ADR 0068 §16) | ✅ `frontend/src/pages/orders/OrderDetailPage.tsx` 합류 | **0** |
| `OrderCard`·`ExecutionTable`·`AuditEventTimeline`·`OrderStatusBadge`·`CancelOrderButton` | (ADR 0068 §16-1) | ✅ `frontend/src/components/order/` 5건 모두 합류 | **0** |
| `useOrders` hook | (Phase 3 부수) | ✅ `frontend/src/api/hooks/useOrders.ts` 합류 | **0** |
| `useStompOrder` hook | (ADR 0068 §16-3 4 event 토스트) | ✅ `frontend/src/api/hooks/useStompOrder.ts` 합류 | **0** |
| `order.ts` REST client | (Phase 3 부수) | ✅ `frontend/src/api/order.ts` 합류 | **0** |
| 라우팅 `/orders` + `/orders/:id` | (ADR 0068 §16-7) | ✅ `frontend/src/App.tsx:123-124` `<Route path="/orders" ...>` + `<Route path="/orders/:id" ...>` 합류 | **0** |
| `AppHeader` 메뉴 1 | (ADR 0068 §16-7) | ✅ `frontend/src/components/layout/AppHeader.tsx:156` `to="/orders"` 링크 합류 | **0** |

**Phase 3 잔여 종합**: **0** (모두 합류 완료).

### §1.4 실측 매트릭스 종합 결론

| Phase | spec §6.3 진술 정확도 | 실측 잔여 작업 |
|---|---|---|
| Phase 1 | 80% (KisExecutionWsListener 클래스 합류는 진술 누락 — 진술은 "잔여"였으나 실측 "클래스 합류 + wiring 잔여") | (a) KisWebSocketBridge wiring + (b) ConsumerOrderExecutionRepositoryAdapter 합류 |
| Phase 2 | 100% (모두 진술대로 완료) | **0** |
| Phase 3 | 100% (OrdersPage 진술 누락이었으나 실측 완전 합류) | **0** |
| Phase 4 | (dogfooding + release) | dogfooding 케이스 6·7·8 + release cut |

**핵심 발견** — prereq spec §6.3 작성 시점 이후(또는 동시에) **Phase 3 OrdersPage·OrderDetailPage·5 컴포넌트·hooks·라우팅 모두 합류 완료** 상태. spec §6.3 진술은 보수적이며 **실제로는 Phase 3 100% GREEN**. Phase 1 KisExecutionWsListener도 클래스는 완전 합류(processMessage 흐름 분기 LOCK 정합 + 단위 테스트 5 GREEN), 단 KIS WS bridge 라우팅 1줄 누락 + Repository Adapter 1쌍 누락이 잔여.

**결과**: T-O 본 트랙 잔여 작업은 **단 2건** + Phase 4 dogfooding + release cut.

---

## §2. 잔여 작업 매트릭스 (분류 B)

### §2.1 잔여 작업 정의 (2건)

#### B-1. KisWebSocketBridge — H0STCNI0/H0STCNI9 → KisExecutionWsListener wiring

**현행 상태** (`consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ws/KisWebSocketBridge.kt:489`):
```kotlin
"H0STCNI0" -> log.info("KIS WS 체결통보 수신: trId=H0STCNI0 (handler 미구현 — T-O)")
```

**잔여 작업**:
1. `KisExecutionWsListener` 의존 주입 (생성자 또는 ObjectProvider — KisWebSocketBridge가 consumer/ws 영역이므로 직접 의존 OK)
2. line 489 분기 변경: `kisExecutionWsListener.onWebSocketMessage(parsed.rawData)` 호출
3. H0STCNI9 추가 분기 (MOCK 체결 통보) — 동일 listener 호출 (listener 내부 분기는 cntgYn/acptYn/rfusYn 기준이므로 tr_id 분기 불필요)
4. H0STCNI0/H0STCNI9 subscribe 메시지 발행 — 본인 사용자별 1 구독 (ADR 0068 §9-4 — 현재 사용자 1명 LOCK, 즉 1 추가 구독 = 41건 한도 30 margin OK)

**파일 변경 예상**:
- `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ws/KisWebSocketBridge.kt` (수정 — line 489 + 신규 subscribe 분기)
- 또는 별도 `KisExecutionSubscriber.kt` 신규 (subscribe 흐름 분리 — 헌법 §6 단일 책임 정합 권장)

**영역**: `consumer/ws` + `consumer/order` (boundary)

**참고**: 본 wiring은 사용자별 1 구독이므로 사용자 onboarding 시점 또는 KIS WS 연결 직후 자동 subscribe 흐름 설계 필요. ADR 0068 §14-6 본문은 wiring 단계까지 명시 — sub-agent dispatch 시 명세 확인 의무.

#### B-2. ConsumerOrderExecutionRepositoryAdapter 합류

**현행 상태**:
- `domain/.../order/execution/port/OrderExecutionRepository.kt` (port — sumExecutedQuantityByOrder, save, findByOrderId)
- `api/.../persistence/order/OrderExecutionRepositoryAdapter.kt` (api 모듈 단독 거주)
- consumer 모듈은 PR #481에서 OrderRequest + OrderAuditEvent만 합류 → **OrderExecutionRepository는 consumer 측 graceful skip 위험**

**잔여 작업** (PR #481 동일 패턴):
1. `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/persistence/ConsumerOrderExecutionJpaEntity.kt` (`@Table("order_execution")` 동일 테이블 사본 entity, V21 정합)
2. `ConsumerOrderExecutionJpaRepository.kt` (abstract fun만 헌법 §6 — `sumExecutedQuantityByOrder(orderId)` `@Query` + `findByOrderIdOrderByExecutedAtAsc` + `save`)
3. `ConsumerOrderExecutionRepositoryAdapter.kt` (port 구현 `@Component`, mapping fun)
4. 단위 테스트 `ConsumerOrderExecutionRepositoryAdapterTest.kt` 3 케이스: (1) save 변환 + (2) sumExecutedQuantityByOrder 합계 (3) findByOrderId 시간순 정렬

**파일 변경 예상**: 4 파일 신규 (consumer/persistence + test).

**영역**: `consumer/persistence` 단일 (PR #481 영역 정합).

**모듈 경계** (ADR 0004): consumer 모듈 의존성 변경 0건 — Flyway V21은 api 모듈 단독 관리 유지. 본 entity는 `@Table("order_execution")` 사본만(spring.jpa.hibernate.ddl-auto=validate 모드면 DB 스키마와 일치 검증).

### §2.2 충돌 행렬 (영역 분리)

| 잔여 | 영역 | B-1 충돌 | B-2 충돌 |
|---|---|---|---|
| B-1 KisWebSocketBridge wiring | `consumer/ws` (+ `consumer/order` 의존 1줄) | — | **add-only** (다른 파일) |
| B-2 ConsumerOrderExecutionRepositoryAdapter | `consumer/persistence` | **add-only** (다른 파일) | — |

→ **병렬 가능** (영역 충돌 0). 단 B-1이 production 흐름상 B-2 이후 합류되어야 graceful skip이 작동하지 않음 — 의존 순서 권장.

### §2.3 의존 순서

```
B-2 (ConsumerOrderExecutionRepositoryAdapter) 합류 PR 머지
  ↓
B-1 (KisWebSocketBridge wiring) 합류 PR 머지
  ↓
Phase 4 dogfooding 케이스 6·7·8
  ↓
release v3.6.13 patch cut
```

병렬 가능성: B-1 + B-2 동시 dispatch OK (영역 add-only). B-1이 먼저 머지되어도 listener는 Repository ObjectProvider가 null → graceful skip(log.warn) → 결함 없음. 다만 dogfooding 케이스 6·7·8 실행 GREEN 위해 B-1 + B-2 모두 머지 후 진입.

### §2.4 잔여 작업 외 — backlog 정합

| 항목 | 처리 |
|---|---|
| backlog §62 (OrderRouterKafkaListener persistRequested catch misleading) | 별도 hotfix 진행 중 (사용자 메모리 — orchestrator 별도 dispatch). 본 spec scope 외. |
| backlog §63 (dogfooding signal seed data 부재) | 본 spec §4.1 사전 준비 단계에 흡수 — dogfooding 실행 시 minimal strategy + signal seed row insert 절차 명시. |

---

## §3. sub-agent dispatch plan (분류 C)

### §3.1 dispatch 매트릭스

| Phase | 잔여 컴포넌트 | sub-agent | 영역 | 우선순위 | 예상 PR 크기 |
|---|---|---|---|---|---|
| Phase 1 | B-2 ConsumerOrderExecutionRepositoryAdapter | **backend-core** (PR #481 동일 패턴, consumer/persistence 영역) | `consumer/persistence` | **HIGH** (B-1 전제) | S (4 파일 신규, ~150 line) |
| Phase 1 | B-1 KisWebSocketBridge wiring | **market-data** (KIS WS bridge 책임 영역) | `consumer/ws` (+ `consumer/order` 의존 1줄) | **HIGH** (체결 추적 본체) | S~M (1-2 파일 수정 + subscribe 흐름 설계) |
| Phase 4 | dogfooding 케이스 6·7·8 | **orchestrator** (사용자 메모리 `feedback_verification_strict` evidence 의무) | runtime 검증 영역 (코드 변경 0) | **HIGH** (release prereq) | - (runtime 실증) |
| Phase 4 | release v3.6.13 patch cut | **orchestrator** (release cycle) | release 영역 (코드 변경 0) | **MEDIUM** | - (tag + release.sh) |

### §3.2 권장 dispatch 순서 (병렬 → 직렬 → 직렬)

**Step 1 — Phase 1 잔여 병렬 dispatch** (architect spec PR 머지 후 즉시):
- (병렬 1) backend-core → B-2 ConsumerOrderExecutionRepositoryAdapter (consumer/persistence add-only)
- (병렬 2) market-data → B-1 KisWebSocketBridge wiring (consumer/ws 수정 + consumer/order 의존)

두 PR 모두 영역 add-only, 충돌 0. CHANGELOG 슬롯 사전 발행(헌법 §4) — 본 spec PR diff에 sub-agent별 row 2건 prepend 의무.

**Step 2 — Phase 1 잔여 머지 검증** (orchestrator):
- Step 1 두 PR 머지 후 통합 빌드 `./gradlew check` GREEN 확인
- consumer 모듈 부팅 smoke — `scripts/build-and-deploy.sh consumer-app` 후 docker logs grep `KisExecutionWsListener|H0STCNI` 에러 0건 검증

**Step 3 — Phase 4 dogfooding** (orchestrator 실행, evidence 수집):
- 케이스 6·7·8 (§4 본문) 순차 실행
- evidence (logs·DB·STOMP) 사용자 메모리 `feedback_verification_strict` 정합 — 머지·green만으로 "완료" 종결 금지

**Step 4 — Phase 4 release** (orchestrator):
- dogfooding 4 MUST + 케이스 6·7·8 GREEN 후
- docker logs 4 모듈 에러 0건 검증 (사용자 메모리 `feedback_docker_log_verify_before_cut` 정합)
- v3.6.13 patch cut (`feedback_release_policy_patch_first` 정합 — v3.7=W4 마감, v3.8=W5 마감, 그 외 patch only → T-O 본 트랙 마감은 patch)

### §3.3 sub-agent 선정 사유

**B-2 → backend-core**: PR #481(`OrderRouterKafkaListener Repository 합류 fix`)이 backend-core 영역에서 처리됨 + ConsumerOrderRequestRepositoryAdapter + ConsumerOrderAuditEventRepositoryAdapter 동일 패턴. 동일 sub-agent에 dispatch하여 패턴 일관성 + 코드 컨벤션 유지.

**B-1 → market-data**: KisWebSocketBridge는 market-data 영역(시세·캔들·호가·체결 WS bridge 총괄). subscribe 흐름 설계는 KIS WS 도메인 지식 필요(approval_key·tr_type=1·custtype=P 등). market-data가 ADR 0048(KIS WS 41 구독)·ADR 0077(OPSP8996)에서 본 영역 책임.

**Phase 4 → orchestrator**: dogfooding은 runtime 통합 검증으로 단일 sub-agent 영역을 넘어섬(consumer·api·frontend·KIS 외부 모두 cover). evidence 수집은 사용자 보고 의무 → orchestrator가 사용자 대면 직접 dispatch.

### §3.4 CHANGELOG 슬롯 사전 발행 (헌법 §4)

본 spec PR diff에 다음 2 sub-agent slot row prepend:

```
<!-- pr-N-slot-backend-core -->
| 2026-05-18 | **fix(consumer): T-O Phase 1 — ConsumerOrderExecutionRepositoryAdapter 합류 (PR #481 패턴 정합)** — TBD (작성: backend-core) | consumer/persistence | T-O 본 트랙 진입 잔여 |
<!-- pr-N-slot-market-data -->
| 2026-05-18 | **fix(consumer): T-O Phase 1 — KisWebSocketBridge H0STCNI0/H0STCNI9 → KisExecutionWsListener wiring (ADR 0068 §14-6)** — TBD (작성: market-data) | consumer/ws + consumer/order | T-O 체결 추적 본체 |
<!-- pr-N-slot-end -->
```

sub-agent는 본인 slot row만 in-place edit (TBD 자리표시자 → 실제 사유 교체). 다른 row·다른 anchor 절대 수정 금지.

---

## §4. Phase 4 dogfooding 매트릭스 (분류 D)

### §4.1 사전 준비 (prereq spec §4.1 + 본 spec 보강)

prereq spec §4.1 Step 0~3 정합(환경 자가 진단·컨테이너 기동·MOCK credential 등록·consent_at 초기 상태 확인). 추가 보강:

#### Step 4. signal seed 데이터 (backlog §63 gap 흡수)

prereq spec §4.2 케이스 1·5는 `signalId` 임의 값 명시 — 실측 `order_request.signal_id` FK 위반 발생. 본 spec dogfooding 케이스 6·7·8은 다음 절차 명시:

```sql
-- (Step 4-1) minimal strategy seed
INSERT INTO strategy (id, user_id, name, status, dsl_json, created_at, updated_at)
VALUES (99001, '<USER_ID>', 'dogfood-test', 'ACTIVE', '{}'::jsonb, NOW(), NOW())
ON CONFLICT (id) DO NOTHING;

-- (Step 4-2) minimal signal seed (FK 만족)
INSERT INTO signal (id, strategy_id, user_id, code, direction, strength, decision_at, risk_status, created_at)
VALUES (99002, 99001, '<USER_ID>', '005930', 'BUY', 1.0, NOW(), 'ALLOWED', NOW())
ON CONFLICT (id) DO NOTHING;
```

후속 개선: backlog §63 권장 처리 — admin seed endpoint 또는 `_workspace/scripts/dogfood-seed.sql` 발행(별도 사이클).

### §4.2 핵심 dogfooding 시나리오 (3 신규 케이스 — prereq §4.2 케이스 1~5 외 추가)

#### 케이스 6: 정상 MOCK 주문 + KisExecutionWsListener 체결 통보 dispatch

**전제**: B-1 + B-2 모두 머지 + env MAGICJAR_MODE=MOCK + ALLOW_REAL=false + MOCK credential 등록 + signal seed (Step 4) 완료

**Step 1**: 시그널 발행 (Kafka publish 또는 강제 trigger — prereq spec §4.2 케이스 1 동일)

**Step 2**: KIS MOCK 서버(openapivts...:29443) ACK 수신 + order_request.status=ACK + kis_order_no 채번 — prereq 케이스 1 evidence 확인

**Step 3**: KIS WS H0STCNI9 모의 체결 통보 수신 → KisExecutionWsListener.onWebSocketMessage() 진입 검증
- consumer log: `KisExecutionWsListener FILLED: orderId=<X> qty=<N>` 또는 `PARTIALLY_FILLED: orderId=<X> sum=<S>/<N>`
- DB `order_execution` row INSERT (executed_quantity·executed_price·executed_at)
- DB `order_request.status = "FILLED"` 또는 `"PARTIALLY_FILLED"` + completed_at 갱신
- DB `order_audit_event.event_type = "FILL_RECEIVED"` + (sum==qty 시) `"FULL_FILL"`
- Kafka `order.filled.v1` publish → StompOrderNotifier → STOMP `/topic/order.user.<USER_ID>` payload(eventType=FILLED) 전송 — frontend useStompOrder가 토스트 표시

**evidence 의무** (사용자 메모리 `feedback_verification_strict`):
- consumer docker logs (KisExecutionWsListener 진입 + 종착 분기 명시)
- DB 3 테이블 직접 조회 (order_request + order_execution + order_audit_event)
- STOMP payload 캡처 (브라우저 DevTools Network WS 탭 또는 backend log)

**기대**: KIS WS → consumer → DB 영속 → Kafka → STOMP → frontend 토스트 풀체인 GREEN.

**복구**: 없음(MOCK 주문 + 자본 영향 0).

#### 케이스 7: OrdersPage 조회 (REQUESTED → ACK → FILLED 상태 추적)

**전제**: 케이스 6 GREEN 후 (또는 동시에)

**Step 1**: 브라우저 `localhost:5173` → `/orders` 진입
- userId 미등록 시 setup 안내 표시 (graceful)
- userId 등록 시 주문 목록 표시 — 케이스 6에서 발행한 주문이 목록에 표시

**Step 2**: 상태 필터 4단 검증
- 전체 / REQUESTED / ACK / FILLED / PARTIALLY_FILLED / REJECTED / CANCELLED 각 필터 클릭 시 totalElements 갱신 + 목록 갱신

**Step 3**: OrderCard 클릭 → `/orders/<id>` 진입
- OrderCard 정상 표시 (status badge 색상 LOCK 정합 — REQUESTED blue·ACK indigo·PARTIALLY_FILLED yellow·FILLED green·REJECTED red·CANCELLED gray)
- ExecutionTable (분할 체결 1:N 시각화) 표시
- AuditEventTimeline (시계열) 표시

**Step 4**: STOMP 실시간 갱신 검증
- 새 주문 발행 시 OrdersPage 자동 갱신 (useStompOrder 토스트 + 목록 invalidate)

**evidence 의무**:
- 브라우저 screenshot 또는 DOM trace
- STOMP payload (DevTools)
- useOrders API 응답 캡처

**기대**: GET `/api/orders?userId=&status=`·`/api/orders/{id}` REST + STOMP `/topic/order.user.{userId}` 풀체인 GREEN.

#### 케이스 8: OrdersPage 취소 (cancel intent → KIS cancel call → 상태 갱신)

**전제**: 케이스 7에서 REQUESTED·ACK·PARTIALLY_FILLED 상태 주문 존재 (FILLED·CANCELLED·REJECTED 제외)

**Step 1**: OrdersPage 또는 OrderDetailPage에서 OrderCancelButton 클릭
- POST `/api/orders/{id}/cancel` 호출
- OrderCancelService.cancel() → KisOrderHttpAdapter.cancel(intent, qtyAllYn="Y") → KIS TTTC0013U(REAL) / VTTC0013U(MOCK) 호출

**Step 2**: KIS 응답 검증
- 성공: order_request.status=CANCELLED + order_audit_event(KIS_CANCEL_ACK) + Kafka order.cancelled.v1 publish → STOMP CANCELLED toast
- 실패 (409 + 사유): order_audit_event(KIS_CANCEL_REJECT) + 사용자 토스트

**Step 3**: KIS WS ACPT_YN="3" 통보 수신 (case match 흐름) → KisExecutionWsListener.handleCancelled() 진입 (백업 처리)
- order_request.status=CANCELLED 멱등 유지 (이미 step 2에서 갱신)
- order_audit_event(KIS_CANCEL_ACK) 추가 row (백업 evidence)

**evidence 의무**:
- consumer log (KisOrderHttpAdapter.cancel + KisExecutionWsListener.handleCancelled 둘 다)
- DB order_request.status 전이 (REQUESTED/ACK/PARTIALLY_FILLED → CANCELLED)
- STOMP payload (CANCELLED event)

**기대**: 사용자 수동 취소 path GREEN + KIS WS 백업 통보 멱등 검증.

**복구**: MOCK 주문이므로 복구 의무 0. 단 케이스 6·7·8 누적 DB row가 dogfooding 잔재 — orchestrator 마감 시 cleanup SQL 선택적 실행 (`DELETE FROM order_execution WHERE order_id IN (SELECT id FROM order_request WHERE signal_id=99002)` 등).

### §4.3 dogfooding 종합 GREEN 기준 (prereq §4.3 + 본 spec 보강)

prereq 4 MUST(케이스 1·2·3·5) + 본 spec 3 MUST(케이스 6·7·8) = **총 7 MUST 케이스 GREEN** → T-O 본 트랙 마감 + release cut 진입.

| 케이스 | 검증 영역 | 필수 GREEN |
|---|---|---|
| 1. MOCK 정상 | mode=MOCK → VTTC0012U + openapivts + ODNO 채번 | **MUST** (prereq) |
| 2. ALLOW_REAL=false 차단 | 안전망 조건 2 | **MUST** (prereq) |
| 3. CONSENT_NOT_GIVEN 차단 | 안전망 조건 3 | **MUST** (prereq) |
| 4. REAL 정상 (자본 변동) | 3 조건 모두 통과 | OPTIONAL (사용자 별도 의제) |
| 5. CREDENTIAL_NOT_FOUND | MOCK row 부재 시 | **MUST** (prereq, GREEN 완료) |
| **6. 체결 통보 + 풀체인** | **KIS WS → consumer → DB → Kafka → STOMP → frontend toast** | **MUST** (본 spec) |
| **7. OrdersPage 조회 + STOMP** | **REST 4 endpoint + 라우팅 + 컴포넌트 표시** | **MUST** (본 spec) |
| **8. OrdersPage 취소 + KIS WS 백업** | **사용자 수동 취소 path + 멱등** | **MUST** (본 spec) |

7 MUST 케이스 GREEN → T-O 본 트랙 마감 → release cut.

---

## §5. release cut 시점 (분류 E)

### §5.1 사용자 메모리 정합 (`feedback_release_policy_patch_first`)

> v3.7=W4 마감, v3.8=W5 마감으로 minor 종료. 그 외 모든 변경(W3 잔여·dogfooding·bugfix)은 patch (v3.6.1·v3.6.2…). v3.8.0 이후 patch only.

**적용**: T-O 본 트랙 마감은 W3 종착 노드 → **v3.6.13 patch cut**. minor cut(v3.7.0)은 W4(T-AI·T-MCP) 마감 시점.

### §5.2 v3.6.13 patch cut 트리거 매트릭스

| 트리거 | 검증 |
|---|---|
| B-1·B-2 두 PR 머지 | gh pr list 확인 |
| `./gradlew check` GREEN | pre-push 자동 |
| `scripts/build-and-deploy.sh` 완료 | 4 모듈 컨테이너 healthy |
| docker logs 4 모듈 에러 0건 | 사용자 메모리 `feedback_docker_log_verify_before_cut` |
| dogfooding 7 MUST 케이스 GREEN | §4.3 evidence |
| backlog §62 hotfix 머지 | (병행 진행 중 — 본 cut 직전 머지 의무) |

### §5.3 v3.6.13 cut 절차 (CLAUDE.md §CI/CD §3 정합)

1. `git tag v3.6.13` + `release/v3.6.13` brunch push (ADR 0031 immutable)
2. `scripts/release.sh v3.6.13` — release notes 합성 + `gh release create` + `sync-readme-releases.mjs` 자동 갱신
3. master.html 자동 갱신 (`scripts/master-index.mjs` via release.sh) — ADR 0079 정합

### §5.4 release notes 핵심 항목 (release.sh 발행 시 본문 LOCK)

- T-O 본 트랙 마감 (Phase 1·2·3·4 모두 GREEN)
- Phase 1 잔여 2건 fix 합류 (B-1 + B-2)
- dogfooding 7 MUST 케이스 GREEN evidence
- 자본 결합 안전 게이트 검증 완료 (3중 게이트 + endpoint/tr_id/credential lookup 모두 dogfooding GREEN)
- ADR 0068 status Proposed → Accepted 갱신 (release commit과 묶음)
- 다음 트랙: T-AI·T-MCP (W4 진입 — minor cut v3.7.0)

---

## §6. ADR 0068 정합 결과 (분류 F)

### §6.1 spec 본문 vs ADR 0068 모순 검증

| ADR 0068 § | spec 본문 인용 | 모순 |
|---|---|---|
| §14-3 3중 게이트 LOCK | §1.1 TradingModeGateImpl 실측 정합 | **0** |
| §14-5 KIS-first raw 인용 (TTTC0012U·VTTC0012U·TTTC0011U·VTTC0011U·TTTC0013U·VTTC0013U) | §4.2 케이스 8 KIS_CANCEL_REQUEST 흐름 | **0** |
| §14-6 KisExecutionWsListener 26 컬럼 매핑 LOCK | §1.1 실측 정합 (processMessage 흐름 분기 LOCK 정합) | **0** |
| §15-1 Flyway V20·V21·V22·V23 LOCK | §1.2 실측 정합 (4건 모두 합류) | **0** |
| §15-4 StompOrderNotifier 4 event discriminator | §1.2 실측 정합 (Kafka 4 topic → STOMP fanout) | **0** |
| §16 OrdersPage 8 컴포넌트 + 라우팅 | §1.3 실측 정합 (모두 합류) | **0** |
| §17 통합 검증 매트릭스 (Phase 1·2·3 직렬) | §3.2 dispatch 순서 (Phase 1 잔여 병렬 + dogfooding 직렬) | **0** (Phase 2·3은 이미 완료, 잔여는 Phase 1 단독이므로 직렬 LOCK 영향 0) |

**결과**: 모순 0건. 본 spec은 ADR 0068 정합 spec/plan만 발행 (사용자 의제 LOCK).

### §6.2 ADR 0068 status 갱신 의제 (별도 사이클)

ADR 0068 status는 현재 **Proposed**. T-O 본 트랙 dogfooding 7 MUST GREEN 시점에 architect가 **Accepted** 갱신 의무.

**처리**: §5.3 release v3.6.13 cut 직전 단일 PR로 ADR 0068 status Proposed → Accepted + `_workspace/arch/adr/README.md` 갱신 + `docs/architecture/master-architecture.md` §10 ADR 인덱스 동시 갱신 (ADR 0030 SoT 정합 — architect 영역).

### §6.3 ADR 신규 발행 의제 (없음)

본 spec dispatch 과정에서 ADR 신규 발행 의제 **0건**. B-1·B-2 모두 ADR 0068 §14-6·§15-2 본문 LOCK 범위 내. 단 dogfooding 후 결함 발견 시 후속 ADR 검토 (분류 A 코드 결함 시).

---

## §7. AI 호출 0 검증 (T-O 트랙 정책)

본 spec 본문에 LLM provider import 0건. AI 호출 0 정책 (ADR 0023 §2-4 + 0066 §8 + 0067 §8 + 0068 §8) LOCK.

검증 grep (PR 본문 첨부 의무):
```bash
grep -rn "AnthropicChatModel\|OpenAiChatModel\|ChatClient" \
  _workspace/specs/2026-05-18-to-main-track-entry-plan.md
# 결과: 0건
```

---

## §8. 영향 ADR·문서 매트릭스

| 문서 | 영향 | 처리 |
|---|---|---|
| ADR 0068 (T-O plan) | 본 spec의 직속 권위 — §14-6·§15·§16 인용 + §6.2 status 갱신 의제 | release cut 직전 별도 PR |
| ADR 0070 (T-RECON+NOTIFY) | order.filled.v1 listen 합류점 — 본 spec dogfooding 케이스 6 → reconcile 영향 검증 (별도) | 변경 0건 |
| ADR 0074 (SettingsModal 풀세트) | RealTradingConsentToggle 인용 | 변경 0건 |
| ADR 0077 (OPSP8996) | KIS WS approval_key·real-kis 정합 인용 | 변경 0건 |
| `tracks-roadmap.md` T-O row | dogfooding 7 MUST GREEN 후 "Accepted" mark | release cut 직전 |
| `backlog.md` §43 (T-O) | 본 spec 발행 후 "Phase 매트릭스 합류 진행" mark | 본 spec PR |
| `backlog.md` §62 (catch misleading) | 별도 hotfix 사이클 — 본 spec scope 외 | 별도 PR |
| `backlog.md` §63 (signal seed gap) | 본 spec §4.1 Step 4에 흡수 (dogfooding 절차 명시) | 본 spec PR |
| CHANGELOG.md | 본 spec PR 머지 row + sub-agent slot 2건 (B-1·B-2) sign 사전 발행 | 본 spec PR |

**ADR 신규 발행 0건** (사용자 의제 LOCK).

---

## §9. Rollback 시나리오

본 spec PR은 **문서 only**. 머지 후 단순 `git revert <SHA>` 1회로 복구 가능.

후속 sub-agent dispatch rollback:
- B-1 wiring rollback: KisWebSocketBridge line 489 원복(log.info 분기 복원) — 자본 영향 0 (체결 통보 미수신 시 사용자 OrdersPage 수동 새로고침 fallback)
- B-2 Adapter rollback: 4 파일 단순 revert — KisExecutionWsListener는 ObjectProvider graceful skip (log.warn) 작동
- dogfooding 케이스 6·7·8 rollback: MOCK 주문이므로 자본 영향 0 + 선택적 cleanup SQL

---

## §10. PR 발행 메타

- 브랜치: `agent/architect/to-main-track-entry-spec`
- author: `bot+architect@magicjar.local`
- base: `main`
- 영역: `_workspace/specs/` add-only + CHANGELOG.md row (본인 + sub-agent slot 2건) + task 파일
- 운영 코드 0 touch (api/* · consumer/* · batch/* · domain/* · frontend/*)
- 자동 검증: `./gradlew check` GREEN (코드 변경 0건 — pre-push 자동 의무)

---

## §11. 종료 보고 매트릭스 (orchestrator 인계용)

- **실측 매트릭스 (A) 결과**: Phase 1 잔여 **2건** (B-1 KisWebSocketBridge wiring + B-2 ConsumerOrderExecutionRepositoryAdapter) — prereq spec §6.3 진술보다 **훨씬 적음** (Phase 3 OrdersPage 등은 이미 합류 완료). Phase 2 잔여 0건, Phase 3 잔여 0건.
- **잔여 작업 매트릭스 (B) 결과**: 2건 모두 영역 add-only(consumer/persistence + consumer/ws) → 병렬 가능 + 의존 순서 권장 (B-2 → B-1).
- **sub-agent dispatch plan (C) 결과**: B-2 → backend-core (PR #481 패턴 정합), B-1 → market-data (KIS WS 책임 영역). orchestrator는 두 sub-agent 병렬 dispatch 가능.
- **Phase 4 dogfooding 매트릭스 (D) 결과**: prereq 4 MUST + 본 spec 3 신규 케이스 6·7·8 = **총 7 MUST** GREEN 시 T-O 본 트랙 마감.
- **release cut (E) 결과**: v3.6.13 patch (사용자 메모리 `feedback_release_policy_patch_first` 정합 — T-O는 W3 트랙이므로 patch only).
- **ADR 0068 정합 (F) 결과**: 모순 **0건**. status 갱신(Proposed → Accepted)은 release cut 직전 별도 PR 의제.

**다음 단계** (orchestrator 자율 dispatch 가능 — 사용자 의제 §0 "T-O 본 트랙 진입" LOCK):
1. 본 spec PR 머지
2. B-1 + B-2 두 sub-agent 병렬 dispatch
3. 두 PR 머지 + 통합 빌드 검증
4. dogfooding 케이스 6·7·8 evidence 수집
5. backlog §62 hotfix 합류 확인
6. ADR 0068 status 갱신 PR
7. v3.6.13 patch cut
