# 06 — PR · ADR · 기능 추적성 매트릭스 (v3.5.0 → v3.6.0)

> **작성자**: architect (PR-54)
> **시점**: 2026-05-13 v3.6.0 태그 컷 직후 (release commit `<TBD>`, `release/v3.6.0` 이미지 immutable)
> **범위**: v3.5.0 (2026-05-12 22:42 KST, main `53e5113`) → v3.6.0 (2026-05-13 <TBD> KST 시점) 사이 10 PR(#367~#376) + ADR 1건 신규(0068 Accepted) + v3.5.0 release 후속 정리 3건(release.sh 풍부 합성·README 매트릭스·자동 갱신 헬퍼 #367·#368·#369) + ADR 0068 plan·Accepted·정밀 명세서 #370·#371·#372 + Phase 1·2·3 직렬 디스패치 #373·#374·#375 + v3.6.0 cut + baseline init(가칭 PR-future).

본 문서는 **PR·ADR·기능 3축 추적성 매트릭스**다. raw 출처:
- `git log v3.5.0..main --oneline` (worktree 직접 실행)
- `gh pr list --state merged --search "merged:>=2026-05-12"` 결과 인용 (PR #367~#376)
- `CHANGELOG.md` 시계열 row
- `_workspace/arch/adr/README.md` 인덱스 (64 → 65, 0068 Accepted)
- `_workspace/backlog.md` § 단위 (§43 처리 매트릭스)

---

## §1. v3.6.0 사이클 트랙별 PR 매핑 (핵심)

`docs/architecture/tracks-roadmap.md` §2 트랙 정의 + v3.6.0 release row(CHANGELOG.md) 풀 정합.

| 트랙 ID | ★ 등급 | 명칭 | 주요 PR | 부수 PR | 결과물 |
|---------|-------|------|---------|----------------|--------|
| **release 절차 보강 + README 매트릭스 자동 갱신 (사용자 점검 정합)** | — | `scripts/release.sh` 풍부 합성 + ADR 0052 §X 추가 + 7 GitHub Release 재발행 + README "Releases & Baseline 진화 매트릭스" + 자동 갱신 헬퍼 | #367 (architect+scripts — release.sh 풍부 합성 + ADR 0052 §X 보강 + 7 release 재발행) · #368 (docs — README Releases & Baseline 매트릭스 추가) · #369 (scripts — sync-readme-releases.mjs 자동 갱신 헬퍼) | — | 사용자 메모리 점검 흡수 ("릴리즈 노트가 v3.1.0·v3.1.1·v3.2.1~v3.5.0 8건 성의없고 알 수 없는 축약어만"). v3.3.0까지 baseline-level 본문 vs v3.3.1~v3.5.0 CHANGELOG row 한 줄 풍부도 0 → release.sh `--backfill` 보강 + ADR 0052에 §X "release notes 작성 절차"(plan→baseline 생성→html→Hero 본문 자동 합성) 정합 추가 + 사용자 결정 α(release/baseline 발행 시 README 매트릭스 자동 갱신 헬퍼) 채택. 사용자 비전 "프로젝트의 꽃" README 정합 확장 |
| **v3.6.0 — T-O W3 네 번째 트랙 마감 (ADR 0068)** | ★★ | T-O 주문 실행 (KIS REST 주문 + WS H0STCNI0 체결 추적 + 3 영속 + STOMP 4 event + 멱등성 Redis SETNX + 3중 게이트 자본 보호) | #370 (architect plan ADR 0068 Proposed) → #371 (architect ADR 0068 Accepted — 사용자 A-1~H-1 모두 채택) → #372 (architect §14·§15·§16·§17 정밀 명세서 보강 — Phase 1·2·3 + orchestrator 통합 검증) → #373 (strategy-engine Phase 1 — domain/order + consumer 흐름 엔진 + 26 Unit Test) → #374 (backend-core Phase 2 — Flyway V20~V24 + JPA 3쌍 + REST 4 + StompOrderNotifier) → #375 (frontend Phase 3 — /orders + /orders/:id + 6 컴포넌트 + RealTradingConsentToggle 이중 confirm) | — | **ADR 0068 신규** (사용자 결정 의제 8건 — A 모의/실전 분기 / B KIS endpoint / C 주문 가격 / D 체결 추적 / E 정정/취소 / F 영속 / G 알림 / H 멱등성 — 모두 architect 권장안 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 채택). **자동매매 본체 종착 실행 노드 가동** — T-RISK ALLOWED 시그널이 Kafka `order.intent.v1` → OrderRouterKafkaListener 12 step 게이트 chain(idempotency Redis SETNX 60s → TradingModeGate 3중 게이트 → KillSwitch 재검증 → order_request INSERT → KIS REST `order-cash` 호출 6 tr_id 분기 → ACK → KIS WS H0STCNI0 체결통보 listen → order_execution N row INSERT 분할 체결 cover → FILLED + position 갱신 + STOMP 4 event 토스트). **3중 게이트 자본 보호 LOCK** — `MAGICJAR_MODE=REAL` env × `MAGICJAR_ALLOW_REAL=true` env × `user_account.real_trading_consent_at IS NOT NULL` 사용자 동의 토글. **KIS-first raw KDoc 의무** — TTTC0012U·VTTC0012U·TTTC0011U·VTTC0011U·TTTC0013U·VTTC0013U 6 tr_id + H0STCNI0/H0STCNI9 26 컬럼. **정밀 명세서 §14·§15·§16·§17 추가**(PR #372 사용자 "확실히 오류 없이 잘 낼 수 있게 설계하라" 명시 정합) — §14 Phase 1 strategy-engine 16 파일 + 12 step 흐름 + 오류 risk 매트릭스 10건 + 단위 테스트 LOCK / §15 Phase 2 backend-core Flyway V20~V23 + JPA 3쌍 + REST 4 + STOMP 4 event + Swagger 4단 / §16 Phase 3 frontend 16 파일 + 색상 매트릭스 + 이중 confirm UX + dogfooding 3 케이스 / §17 orchestrator 통합 검증 매트릭스 19 항목. Phase 1 strategy-engine(#373): `domain/order/OrderIntent.kt`·`OrderRequest.kt`·`OrderExecution.kt`·`OrderAuditEvent.kt`·`OrderSide`(BUY·SELL)·`OrderPriceType`(MARKET=01·LIMIT=00)·`OrderStatus` 7단·`OrderAuditEventType` 12단·`DenyReason` sealed·`KisOrderAck`. 9 port(`OrderRequestRepository`·`OrderExecutionRepository`·`OrderAuditEventRepository`·`KisOrderPort`·`TradingModeGate`·`OrderIdempotencyGuard`·`OrderNotifier`·`PositionUpdatePort`·`KillSwitchPort`). consumer 10 컴포넌트: `OrderRouterKafkaListener` `@KafkaListener(order.intent.v1, groupId=magicjar-consumer-order)` 12 step LOCK + ackMode MANUAL_IMMEDIATE + max.poll.records=1 + concurrency=1 per partition (자본 path race 회피). `OrderIdempotencyGuardImpl` Redis SETNX 60s `idem:order:{key}`. `TradingModeGateImpl` 3중 게이트 4 case (envMode != REAL → MOCK fallback / !allowReal → RealGateDenied / user NULL → USER_NOT_FOUND / consentAt NULL → CONSENT_NOT_GIVEN). `KisOrderHttpAdapter` KIS REST 6 tr_id 분기 + raw KDoc(domestic_stock_functions.py L10097~L10884) + 5xx 1회 retry 500ms backoff + UserKisCredentialRepository.findByUserAndAccountType. `KisExecutionWsListener` H0STCNI0/H0STCNI9 26 컬럼 매핑 LOCK + CNTG_YN 분기(1=접수/2=체결) + 분할 체결 1:N + sumExecutedQuantity 정합. `PositionUpdateAdapter` ADR 0042·0067 정합. 4 Kafka Producer(`order.requested.v1`·`order.filled.v1`·`order.rejected.v1`·`order.cancelled.v1` retention 30d partition by userId). `OrderTopicConfig` KafkaAdmin NewTopic 4건. **26 Unit Test** GREEN(OrderRouterKafkaListenerTest 6 케이스 + TradingModeGateImplTest 4 케이스 + OrderIdempotencyGuardImplTest 3 케이스 + KisOrderHttpAdapterTest 5 케이스 + KisExecutionWsListenerTest 5 케이스 + 기타 3 케이스). AI 호출 0 grep 검증 + inline 주석. `:domain:check` + `:consumer:check` GREEN. Phase 2 backend-core(#374): Flyway V20(`order_request` BIGSERIAL + idempotency_key UQ + kis_order_no UQ + CHECK 6건 + partial idx WHERE status IN open · IF NOT EXISTS) · V21(`order_execution` order_id FK ON DELETE CASCADE + 분할 체결 1:N + idx_order_user_time · IF NOT EXISTS) · V22(`order_audit_event` JSONB + event_type 12단 · IF NOT EXISTS) · V23(`user_account.real_trading_consent_at` ALTER ADD COLUMN IF NOT EXISTS · COMMENT 부착 자본 보호 명시) · V24 spare. **V18 ADR 0042 충돌 패턴 재발 회피 LOCK** — 모든 신규 테이블 `IF NOT EXISTS` + 기존 테이블 확장 `ADD COLUMN IF NOT EXISTS` 의무. JPA 3쌍(OrderRequest·OrderExecution·OrderAuditEvent) ADR 0051 default fun 회피 abstract만 + @EntityGraph N+1 회피 + `@Query SUM sumExecutedQuantityByOrder`. REST 4 endpoint(`GET /api/orders` 페이징 + `GET /api/orders/{id}` with executions+audit_events + `POST /api/orders/{id}/cancel` + `PUT /api/user-account/real-trading-consent`) + Swagger 4단 한국어 example. `OrderQueryService` · `OrderCancelService` (status ∈ open 검증 → KisOrderPort.cancel 위임 → CANCELLED UPDATE + audit + STOMP + publish) · `RealTradingConsentService` (UserAccount @Version optimistic lock). `StompOrderNotifier` `@KafkaListener` 4 order.* topic → eventType discriminator → `/topic/order.user.{userId}` fanout. AI 호출 0 inline 주석. `:api:check` GREEN. Phase 3 frontend(#375): `/orders` OrdersPage(무한스크롤 + OrderFilterBar) + `/orders/:id` OrderDetailPage(executions + audit_events + cancel button) + 6 컴포넌트: `OrderCard` · `OrderStatusBadge` 7 status 색상 매트릭스 LOCK(REQUESTED blue·ACK blue-deep·PARTIALLY_FILLED yellow·FILLED green·REJECTED red·CANCELLED gray·EXPIRED gray-deep + 한국어 라벨) · `ExecutionTable` 분할 체결 시계열 + footer sum · `OrderAuditTimeline` event_type 매트릭스 · `CancelOrderButton` 확인 모달 의무 · **`RealTradingConsentToggle` SettingsModal 이중 confirm UX 자본 보호 LOCK**(1차 "실거래 모드 활성화" + 2차 "재확인: 자본 손실 risk 동의"). 5 hook: `useOrders` · `useOrder` · `useCancelOrder` · `useUpdateRealTradingConsent` · `useStompOrder`(/topic/order.user.{userId} subscribe + 4 event 토스트 색상 분기 — REQUESTED toast.info blue / FILLED toast.success green or partial yellow / REJECTED toast.error red / CANCELLED toast.info gray + queryClient.invalidateQueries 자동 새로고침). 라우팅 통합 + AppHeader 메뉴 "주문" 1건 add. `pnpm typecheck && pnpm build` GREEN. 사용자 메모리 정합(`feedback_no_mock_data.md` 0건 시 "데이터 없음" · `feedback_honorific.md` 한국어 존댓말 · `feedback_swagger_pattern.md` backend Swagger 4단). AI 호출 0 grep 검증(consumer/order + api/order + domain/order 0 lines) + inline 주석 의무. **end-to-end latency** <405ms total (boundary 20ms + signal 75ms + risk 75ms + order intent 10ms + OrderRouter 20ms + KIS REST 200ms + STOMP 5ms). KIS WS 구독 한도(ADR 0048 41건) 영향: 사용자별 H0STCNI0/H0STCNI9 1 구독 추가 → 사용자당 ~11건(margin 30건 OK). order 영속 부담: ~140K row/year per user (가벼움). |
| **v3.6.0 cut + baseline init** | — | release + baseline 폴더 신설 | (가칭 #future-release) (release v3.6.0 — orchestrator commit `<TBD>`) + #376 (chore(baseline): v3.6.0 init — 폴더 + CHANGELOG 5 slot prepend PR-54) | — | v3.5.0 → v3.6.0 minor. orchestrator release commit + CHANGELOG row + master §10 ADR 인덱스 0068 갱신 + release.sh `--backfill` v3.6.0 풍부 본문 자동 합성(ADR 0052 §X 단계 5 정합) + README 매트릭스 자동 갱신(sync-readme-releases.mjs). baseline init: `_workspace/baseline/v3.6.0/` 폴더 신설 + README skeleton + 7 파일 인덱스 + CHANGELOG sub-agent 슬롯 4 anchor(architect/backend-core/market-data/frontend + orchestrator) |

---

## §2. PR 시계열 (#367 ~ #376, 10 PR)

`gh pr list --state merged --search "merged:>=2026-05-12"` raw 결과 + `git log v3.5.0..main --oneline` 정합.

> **PR Prefix 분류**: `docs(arch/baseline/architect/adr)` = architect 단독 · `feat(...)` = sub-agent 트랙 본격 구현 · `fix/hotfix(...)` = hotfix · `chore(meta/adr/ci/task)` = orchestrator·meta 정리 · `release:` = 릴리즈 컷 · `chore(baseline)` = baseline init.

### 2-1. v3.5.0 release 후속 정리 — release 절차 보강 (2026-05-12 14:28~14:37 KST)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #367 | 2026-05-12 14:28 | `agent/architect/release-notes-cycle-fix` | architect+scripts · release 절차 보강 | **docs(arch+scripts): release 절차 ADR 0052 §X 보강 + release.sh 풍부 합성 + 7 GitHub Release 재발행** — 사용자 점검 evidence: "릴리즈는 /baseline 으로 md html 생성하고 나서 릴리즈 하고 해당 데이터를 기반한걸 릴리즈 노트에 명시 했던거 같은데?" + "v3.3.0까지는 릴리즈 노트가 상당히 상세했는데 v3.3.1~v3.5.0은 왜 이렇게 성의없고 알 수 없는 축약어만 늘어놓은거야?". architect 점검 결과 사용자 인식 정확 — v3.3.0은 baseline-level 본문(Hero + 시퀀스 + ADR 인용 + dogfooding) vs v3.3.1·v3.4.0·v3.5.0 등 8건은 release.sh --backfill이 CHANGELOG row 한 줄만 추출 → 풍부도 0. ADR 0052·`magicjar-baseline` skill 본문에 release notes 작성 절차 자체가 명시 X. 본 PR이 (1) `scripts/release.sh --backfill` 본문 풍부 합성 보강 — baseline html Hero 본문 추출 + ADR 인용 자동 합성 + dogfooding evidence row 추가 (2) ADR 0052 §X 신규 추가 — "release notes 작성 절차" 5 단계(plan → baseline 생성 → html → Hero 본문 추출 → release.sh --backfill 자동 합성) (3) 7 GitHub Release(v3.1.0·v3.1.1·v3.2.1·v3.2.2·v3.2.3·v3.3.1·v3.4.0·v3.5.0) 본문 풍부 재발행 evidence 첨부 |
| #368 | 2026-05-12 14:29 | `chore/readme-release-timeline` | docs · README 매트릭스 추가 | **docs(readme): Releases & Baseline 진화 매트릭스 추가** — 사용자 명시 "GitHub 방문자 release/baseline 진입 매트릭스" 정합. README "Releases & Baseline 진화" § 신규 — v3.0.1·v3.1.0~v3.5.0 시계열 row + 미러스톤 + baseline 폴더 링크 + release 노트 링크 + 핵심 ADR 인용 매트릭스. 사용자 비전 "프로젝트의 꽃" README 확장 정합 |
| #369 | 2026-05-12 14:37 | `feat/sync-readme-releases` | scripts · 자동 갱신 헬퍼 | **feat(scripts): sync-readme-releases.mjs — README 매트릭스 자동 갱신** — 사용자 결정 α(release/baseline 발행 시 README 매트릭스 자동 갱신) 정합. `scripts/sync-readme-releases.mjs` 신규 — release tag + baseline 폴더 매트릭스 git ls + README "Releases & Baseline 진화" § 시계열 row 자동 prepend. release.sh 후행 step에 자동 호출 합성 (ADR 0052 §X 단계 5 정합) |

### 2-2. ADR 0068 plan + Accepted + 정밀 명세서 (2026-05-12 23:30~2026-05-13 04:52 KST)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #370 | 2026-05-12 23:30 | `agent/architect/t-o-plan` | architect spec · ADR 0068 Proposed | **docs(architect): ADR 0068 plan — T-O 주문 실행 (W3 네 번째 트랙)** — 컨텍스트: T-RISK v3.5.0 마감 직후 진입. T-O는 자동매매 본체의 종착 실행 노드 — T-SIG 시그널 + T-RISK ALLOWED 게이트 통과 후 실제 KIS REST 매수/매도 주문 발행. 사전 자산(ADR 0067 T-RISK Accepted + Kafka `order.intent.v1` topic 이미 main + ADR 0024 Risk Guard + ADR 0042 Position Bootstrap + ADR 0043 KillSwitch + ADR 0044 Notification + ADR 0046 Onboarding & Credential + ADR 0048 KIS WS 41 한도 + Signal·Risk 영속 + STOMP 인프라). 결정 의제 8건 사용자 검토 위임 — A 모의/실전 분기(env 이중+사용자 동의 토글 vs env 이중만 vs 단일) / B KIS endpoint(단일 port adapter 내부 mode 분기 vs Spring profile vs reflection lookup) / C 주문 가격(시장가+지정가 vs +슬리피지 vs 지정가 only) / D 체결 추적(KIS WS H0STCNI0 실시간 vs REST polling vs dual) / E 정정/취소(사용자 수동 vs 자동+수동 vs 자동만) / F 영속(3 테이블 분리 vs 2 테이블 vs JSONB blob) / G 알림(단일 토픽 4 event vs 단일 type vs 4 토픽) / H 멱등성(Redis SETNX + DB UQ vs DB UQ만 vs 0). architect 권장 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1. fitness gap 검증(자동매매 종착 노드 필수·v1/v2 잔재 0·사용자 비전 정합·AI 호출 0). KIS-first raw 인용 인벤토리 4건 명시 — TTTC0012U·VTTC0012U BUY 6 tr_id + H0STCNI0/H0STCNI9 WS 26 컬럼(`domestic_stock_functions.py` L10097~L10884 + `domestic_stock_functions_ws.py` L389~L446). 운영 영향: DB ~140K row/year per user · Kafka 4 토픽 신규 retention 30d · STOMP 0.0017 msg/s · Redis idem:order TTL 60s · KIS REST 추가 호출 ~50/일 per user · KIS WS 구독 +1 (사용자당 ~11건 margin 30 OK) · latency <235ms 자체 + <405ms 총합. Rollback 5 시나리오(REAL 게이트 우회 결함 / 중복 주문 발행 / KIS API 5xx 누적 / H0STCNI0 미수신 누적 / 자본 손실 누적). backlog §43 신규 등록 + master §10 0068 row + ADR README. 코드 변경 0건(.md add-only) |
| #371 | 2026-05-12 23:33 | `chore/adr-0068-accepted` | architect ADR 0068 Accepted | **chore(adr): ADR 0068 Accepted (T-O A-1~H-1)** — 사용자 결정 의제 8건 모두 architect 권장안 채택 → status `Proposed` → `Accepted (2026-05-13 — 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 모두 채택)` 헤더 갱신. Phase 1·2·3 직렬 디스패치 진입. master §10 ADR 인덱스 + ADR README §6 row + tracks-roadmap.md T-O row 권위 인용 |
| #372 | 2026-05-13 04:52 | `agent/architect/t-o-precise-spec` | architect · ADR 0068 정밀 명세서 | **docs(architect): ADR 0068 §14·§15·§16·§17 정밀 명세서 보강 — T-O Phase 1·2·3 + 자본 보호 LOCK** — 사용자 명시 "확실히 오류 없이 잘 낼 수 있게 설계하라" 정합. ADR 0068 본문에 §14·§15·§16·§17 4 § 신규 추가(§13 → §18 shift). §14 Phase 1 strategy-engine 정밀 명세서 — 16 파일 매트릭스(domain 12 + consumer 12 schema) · OrderRouterKafkaListener 12 step 정밀 흐름 LOCK(각 step 실패 분기 명시) · TradingModeGateImpl 3중 게이트 정밀(4 case Either return) · **오류 risk 매트릭스 10건**(KIS 5xx/invalid JSON/rt_cd != 0/Kafka deserialize/idempotency duplicate/Redis 장애/3중 게이트/KillSwitch ON/H0STCNI0 미수신 24h/executions overflow) · KisOrderHttpAdapter raw 인용 정밀 KDoc 코드 LOCK · KisExecutionWsListener 26 컬럼 매핑 표 LOCK + 흐름 분기 LOCK · 단위 테스트 ~15건 LOCK. §15 Phase 2 backend-core 정밀 — Flyway 매트릭스 V20·V21·V22·V23 본문 + IF NOT EXISTS 의무(V18 ADR 0042 충돌 재발 회피) + FK 정책(signal_id RESTRICT, order_id CASCADE) · JPA 3쌍 + 1 ALTER + 헌법 §6 default fun 함정 회피(abstract만) · REST 4 endpoint Swagger 4단 example + 사용자 메모리 `feedback_swagger_pattern.md` 정합 · StompOrderNotifier 단일 토픽 + 4 event 코드 LOCK · 오류 risk 매트릭스 8건. §16 Phase 3 frontend 정밀 — 16 파일 매트릭스 · OrderStatusBadge 7 status 색상 매트릭스 LOCK · useStompOrder 4 event 토스트 매트릭스 · **RealTradingConsentToggle 이중 confirm UX 정밀**(1차 + 2차 confirm 자본 보호 LOCK) · **dogfooding 3 케이스**(케이스 1 모의 주문 정상 / 케이스 2 3중 게이트 BLOCKED → KIS prod 호출 0건 log evidence / 케이스 3 분할 체결 정합) · 오류 risk 매트릭스 7건. §17 통합 검증 매트릭스 — orchestrator 각 Phase 머지 후 19 검증 항목 + Phase 1 → 2 → 3 직렬 LOCK(병렬 디스패치 금지 — 자본 위험 트랙) + Rollback 통합 §10 매트릭스 9 step 정합 |

### 2-3. T-O 본격 Phase 1·2·3 (2026-05-13 05:15~06:33 KST)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| #373 | 2026-05-13 05:15 | `agent/strategy/t-o-phase1` | strategy-engine 본격 · T-O Phase 1 | **feat(strategy-engine): T-O Phase 1 — domain/order/execution + consumer/order 흐름 엔진 + 26 Unit Test (ADR 0068 §14 LOCK)** — domain 12건: `domain/.../order/OrderIntent.kt` data class(id·signalId·strategyId·userId·code·side·priceType·quantity·limitPrice?·idempotencyKey·requestedAt) + `OrderRequest.kt`(영속 도메인 — status·mode·kisOrderNo?·kisFwdgOrgno?·rejectReason?·requestedAt·acknowledgedAt?·completedAt? 추가) + `OrderExecution.kt`(분할 체결 1건) + `OrderAuditEvent.kt`(audit row) + `OrderSide` enum(BUY·SELL) + `OrderPriceType` enum(MARKET=ORD_DVSN "01"·LIMIT=ORD_DVSN "00") + `OrderStatus` enum 7단(REQUESTED·ACK·FILLED·PARTIALLY_FILLED·REJECTED·CANCELLED·EXPIRED) + `OrderAuditEventType` enum 12단(REAL_GATE_DENIED·IDEMPOTENCY_SKIPPED·KILLSWITCH_DENIED·KIS_ACK·KIS_REJECT·KIS_EXCEPTION·FILL_RECEIVED·PARTIAL_FILL·FULL_FILL·KIS_CANCEL_ACK·KIS_CANCEL_REJECT·EXECUTION_TIMEOUT·EXECUTION_OVERFLOW) + `DenyReason` sealed class(RealGateDenied·IdempotencyDuplicate·KillSwitchOn·KisRejected) + `KisOrderAck` data class. 9 port: `OrderRequestRepository` (save·findById·findByKisOrderNo·findByIdempotencyKey·updateStatus·updateKisAck·markCompleted·findOpenByUserId — 모두 abstract) · `OrderExecutionRepository` (save·findByOrderId·sumExecutedQuantityByOrder) · `OrderAuditEventRepository` (save·findByOrderId) · `KisOrderPort` (submit·cancel — Either return) · `TradingModeGate` (resolveMode — Either return) · `OrderIdempotencyGuard` (acquire — TTL param) · `OrderNotifier` (notifyRequested·notifyFilled·notifyRejected·notifyCancelled 4 fun) · `PositionUpdatePort` (applyExecution) · `KillSwitchPort` (isOn). consumer 10 컴포넌트: `OrderRouterKafkaListener` @KafkaListener(topics=["order.intent.v1"], groupId="magicjar-consumer-order") 12 step LOCK + ackMode MANUAL_IMMEDIATE + max.poll.records=1 + concurrency=1 per partition. `OrderIdempotencyGuardImpl` Redis SETNX 60s `idem:order:{key}`. `TradingModeGateImpl` 3중 게이트 4 case(envMode != REAL → MOCK fallback / !allowReal → RealGateDenied / user NULL → USER_NOT_FOUND / consentAt NULL → CONSENT_NOT_GIVEN). `KisOrderHttpAdapter` 6 tr_id 분기 + raw KDoc(domestic_stock_functions.py L10097~L10884) + 5xx 1회 retry 500ms backoff + UserKisCredentialRepository.findByUserAndAccountType. `KisExecutionWsListener` H0STCNI0/H0STCNI9 26 컬럼 매핑 LOCK + CNTG_YN 분기(1=접수/2=체결) + 분할 체결 1:N + sumExecutedQuantity 정합. `PositionUpdateAdapter` ADR 0042·0067 정합. 4 Kafka Producer: `OrderRequestedKafkaProducer` order.requested.v1 + `OrderFilledKafkaProducer` order.filled.v1 + `OrderRejectedKafkaProducer` order.rejected.v1 + `OrderCancelledKafkaProducer` order.cancelled.v1 (각 retention 30d partition by userId). `OrderTopicConfig` KafkaAdmin NewTopic 4건. **26 Unit Test** GREEN: OrderRouterKafkaListenerTest 6 케이스(intent ACK 흐름 / idempotency duplicate skip / real gate denied / killswitch denied / KIS 5xx retry then fail / KIS rt_cd != 0 reject) + TradingModeGateImplTest 4(MOCK fallback / REAL 정상 / ALLOW_REAL=false denied / CONSENT_NOT_GIVEN denied) + OrderIdempotencyGuardImplTest 3(첫 acquire / 중복 acquire / TTL 만료 후 재 acquire) + KisOrderHttpAdapterTest 5(BUY MOCK VTTC0012U / SELL REAL TTTC0011U / MARKET ORD_DVSN=01 / LIMIT ORD_DVSN=00 + ORD_UNPR / rt_cd != 0 KisRejected) + KisExecutionWsListenerTest 5(CNTG_YN=2 부분 체결 PARTIALLY_FILLED / 누적 == qty FILLED / RFUS_YN=Y REJECTED / CNTG_YN=1 ACPT_YN=3 CANCELLED / overflow audit + skip) + 기타 3 케이스. AI 호출 0 grep 검증(consumer/order + domain/order 0 lines) + inline 주석 의무. `:domain:check` + `:consumer:check` GREEN |
| #374 | 2026-05-13 05:39 | `agent/backend-core/t-o-phase2` | backend-core 본격 · T-O Phase 2 | **feat(backend-core): T-O Phase 2 — Flyway V20~V24 + JPA 3쌍 + REST 4 endpoint + StompOrderNotifier (ADR 0068 §15)** — Flyway V20(`order_request` BIGSERIAL + 18 컬럼 + idempotency_key UQ + kis_order_no UQ + CHECK 6건 + 3 idx 중 partial idx WHERE status IN open · `IF NOT EXISTS`) · V21(`order_execution` order_id FK ON DELETE CASCADE + 분할 체결 1:N + 2 idx + CHECK qty>0 + price>0 · `IF NOT EXISTS`) · V22(`order_audit_event` JSONB + event_type 12단 + order_id FK CASCADE + 1 idx · `IF NOT EXISTS`) · V23(`user_account.real_trading_consent_at` ALTER `ADD COLUMN IF NOT EXISTS` + COMMENT 부착 자본 보호 명시) · V24 spare. **V18 ADR 0042 충돌 패턴 재발 회피 LOCK** — 모든 신규 테이블 IF NOT EXISTS + 기존 테이블 확장 ADD COLUMN IF NOT EXISTS 의무(사용자 메모리 정합). FK 정책: signal_id RESTRICT(감사 정합) · order_id CASCADE. kis_execution_no UQ 미사용(KIS 일부 환경 미발급, sumExecutedQuantity 정합으로 분할 체결 멱등). JPA 3쌍: `OrderRequestJpaEntity·Repository·Adapter` (ADR 0051 default fun 회피 abstract만, `findByKisOrderNo`·`findByIdempotencyKey`·`findByUserIdOrderByRequestedAtDesc(Pageable)`·`@EntityGraph(attributePaths=["executions"]) findOpenByUserId`) + `OrderExecutionJpaEntity·Repository·Adapter` (`@Query SUM sumExecutedQuantityByOrder`) + `OrderAuditEventJpaEntity·Repository·Adapter`. `UserAccountJpaEntity` 확장(realTradingConsentAt 필드 + @Version optimistic lock). REST 4 endpoint: `OrderQueryController` 2(`GET /api/orders` 페이징 ?status·side·code·from·to·page·size · `GET /api/orders/{id}` request + executions[] + auditEvents[]) + `OrderCancelController` 1(`POST /api/orders/{id}/cancel` body={reason?}) + `RealTradingConsentController` 1(`PUT /api/user-account/real-trading-consent` body={consent: true|false}). Swagger 4단 한국어 + example 의무(사용자 메모리 `feedback_swagger_pattern.md` 정합). `OrderQueryService`(repo lookup + DTO mapping + N+1 회피) + `OrderCancelService`(status ∈ open 검증 → KisOrderPort.cancel 위임 RVSE_CNCL_DVSN_CD="02" QTY_ALL_ORD_YN="Y" → CANCELLED UPDATE + audit + STOMP + publish) + `RealTradingConsentService`(toggle ON → NOW() UPDATE / OFF → NULL UPDATE + @Version race 회피). `StompOrderNotifier` `@Component` `@KafkaListener(topics=["order.requested.v1","order.filled.v1","order.rejected.v1","order.cancelled.v1"], groupId="magicjar-api-stomp-order")` → topic discriminator → `messagingTemplate.convertAndSend("/topic/order.user.${userId}", OrderStompPayload)`. ConcurrentKafkaListenerContainerFactory concurrency=1 (STOMP race 회피). 통합 테스트 REST 4 endpoint + Order 페이징 + cancel 409 status FILLED 검증 + consent toggle race + StompOrderNotifier 4 topic → 4 event 분기. AI 호출 0 inline 주석. `:api:check` GREEN. Swagger UI 4 endpoint 스크린샷 evidence 첨부 |
| #375 | 2026-05-13 06:33 | `agent/frontend/t-o-phase3` | frontend 본격 · T-O Phase 3 | **feat(frontend): T-O Phase 3 — /orders·/orders/:id 2 페이지 + 6 컴포넌트 + 4 API hook + useStompOrder + RealTradingConsentToggle 이중 confirm (ADR 0068 §16)** — `pages/orders/OrdersPage.tsx` `/orders`(무한스크롤 + OrderFilterBar — status·side·code·date range 필터) + `pages/orders/OrderDetailPage.tsx` `/orders/:id`(단건 — OrderCard + ExecutionTable 분할 체결 시계열 + OrderAuditTimeline event_type 매트릭스 + CancelOrderButton 확인 모달). 6 컴포넌트: `OrderCard`(status badge + side · code · qty · price · createdAt) · **`OrderStatusBadge` 7 status 색상 매트릭스 LOCK**(REQUESTED `bg-blue-100 text-blue-800` "접수" · ACK `bg-blue-200 text-blue-900` "KIS 접수" · PARTIALLY_FILLED `bg-yellow-100 text-yellow-800` "부분 체결" · FILLED `bg-green-100 text-green-800` "체결 완료" · REJECTED `bg-red-100 text-red-800` "거부" · CANCELLED `bg-gray-100 text-gray-700` "취소" · EXPIRED `bg-gray-200 text-gray-800` "만료") · `ExecutionTable`(분할 체결 1:N + footer sum) · `OrderAuditTimeline`(event_type 12단 매트릭스 시계열) · `CancelOrderButton`(확인 모달 의무 — "정말 취소하시겠습니까?" + 주문 정보 표시) · **`RealTradingConsentToggle` SettingsModal 이중 confirm UX 자본 보호 LOCK**(1차 confirm "실거래 모드를 활성화합니다. 실제 자본이 사용됩니다. 동의하십니까?" + 2차 confirm "재확인: 자본 손실 risk를 이해하고 동의합니다." 둘 다 OK 시 PUT consent + 동의 시각 표시 red). 5 hook: `useOrders` `useQuery(['orders', filters], ...)` · `useOrder` `useQuery(['orders', id], ...)` · `useCancelOrder` useMutation · `useUpdateRealTradingConsent` useMutation · **`useStompOrder` `/topic/order.user.{userId}` subscribe + 4 event 토스트 색상 분기**(REQUESTED toast.info blue `주문 접수 — ${code} ${side} ${qty}주` / FILLED partial=true toast.warning yellow `부분 체결 — ${code} ${executedQty}/${qty}주 @ ${price}원` / FILLED partial=false toast.success green `체결 완료 — ${code} ${qty}주 @ ${price}원` / REJECTED toast.error red `주문 거절 — ${code}: ${reason}` / CANCELLED toast.info gray `주문 취소 — ${code} ${qty}주`) + `queryClient.invalidateQueries(['orders'])` 자동 새로고침. 라우팅 통합 (`/orders`·`/orders/:id` App.tsx) + AppHeader 메뉴 "주문" 1건 add (signals·risk·backtest 옆). `pnpm typecheck` (0 errors) + `pnpm build` GREEN. dogfooding 3 케이스 검증(케이스 1 모의 주문 정상 / 케이스 2 3중 게이트 BLOCKED → KIS prod 호출 0건 log evidence / 케이스 3 분할 체결 정합) 사용자 검증 1회 후속. 사용자 메모리 정합(`feedback_no_mock_data.md` 0건 시 "데이터 없음" · `feedback_honorific.md` 한국어 존댓말). AI 호출 0(LLM provider import 0건 frontend) |

### 2-4. v3.6.0 cut + baseline init (2026-05-13 <TBD> KST)

| PR | mergedAt | head ref | 종류 | 요약 |
|----|----------|----------|------|------|
| (가칭 #future) | 2026-05-13 <TBD> | `chore/v3.6.0-cut` | release | **release: v3.6.0 — T-O W3 네 번째 트랙 마감 (자동매매 본체 종착 노드 가동 + 3중 게이트 자본 보호 LOCK)** — orchestrator commit `<TBD>` (CHANGELOG row + master §10 ADR 인덱스 0068 갱신 + 릴리즈 노트 풍부 합성 `release.sh --backfill` ADR 0052 §X 단계 5 정합 + README 매트릭스 자동 갱신 sync-readme-releases.mjs PR #369 정합). v3.5.0 → v3.6.0 minor 미러스톤. 6 PR 묶음(#370·#371·#372·#373·#374·#375) + ADR 0068 채택(A-1~H-1) + 정밀 명세 §14·§15·§16·§17 추가. 자동매매 본체 종착 실행 노드 가동. boundary 마감 → 시그널 → risk verdict → order intent → KIS REST → ACK → 체결 통보 → STOMP latency <405ms |
| #376 | 2026-05-13 06:42 | `chore/baseline-v3.6.0-init` | baseline init | **chore(baseline): v3.6.0 init — 폴더 + CHANGELOG 5 slot prepend (PR-54)** — orchestrator init (`_workspace/baseline/v3.6.0/` 폴더 + README skeleton + 7 파일 인덱스 + CHANGELOG sub-agent slot 5 anchor 사전 발행 — architect·backend-core·market-data·frontend + orchestrator) |

---

## §3. ADR 시계열 매트릭스 (64 → 65 신규 1건)

`_workspace/arch/adr/README.md` 인덱스 정합.

### 3-1. v3.6.0 사이클 신규 ADR 1건

| ADR | 상태 | 발행 PR | 결정자 | 종류 | 핵심 결정 |
|-----|------|---------|--------|------|----------|
| **0068** | Accepted (2026-05-13 — 사용자 결정 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 모두 채택) | #370 plan / #371 Accepted / #372 §14·§15·§16·§17 정밀 명세 / #373 Phase 1 / #374 Phase 2 / #375 Phase 3 | 사용자 + architect (의제 8건 검토 위임) | T-O W3 네 번째 트랙 baseline | **T-O 주문 실행 (Risk ALLOWED → KIS REST 매수·매도 + WS H0STCNI0 체결 추적 + 사용자 수동 정정/취소 + 3 테이블 영속 + STOMP 4 event + 멱등성 Redis SETNX + 실계좌 3중 게이트)** — A-1 env × 2 + 사용자 동의 토글 (3중 게이트) / B-1 단일 OrderRouterPort + KisOrderAdapter 내부 mode 분기 / C-1 ORD_DVSN 매트릭스 시장가(01) + 지정가(00) / D-1 KIS WS H0STCNI0 실시간 listen / E-1 사용자 수동 cancel only (1차 MVP) / F-1 3 테이블 분리 (order_request + order_execution + order_audit_event) / G-1 단일 STOMP 토픽 + 4 event discriminator / H-1 Redis SETNX 60s + DB UQ 이중. 정밀 명세 §14·§15·§16·§17 추가(PR #372 사용자 "확실히 오류 없이" 정합) — Phase 1 strategy-engine 16 파일 + 12 step + 오류 risk 매트릭스 10건 · Phase 2 backend-core Flyway V20~V23 + JPA 3쌍 + REST 4 + STOMP 4 event · Phase 3 frontend 16 파일 + 색상 매트릭스 + 이중 confirm UX + dogfooding 3 케이스 · orchestrator 통합 검증 19 항목. Phase 1·2·3 직렬 디스패치(자본 위험 트랙 — 병렬 금지). end-to-end latency <235ms 자체 + <405ms 총합. KIS REST 추가 호출 ~50/일 per user + KIS WS 구독 +1(사용자당 ~11건, margin 30건 OK). order 영속 ~140K row/year per user 예측. AI 호출 0 검증(ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + 본 ADR §8). **자동매매 본체 종착 실행 노드 가동 + 3중 게이트 자본 보호 LOCK + KIS-first raw KDoc 의무(6 tr_id + 26 컬럼)** |

**v3.6.0 사이클 신규 ADR 1건**: ADR 0068 Accepted. ADR 번호 시퀀스 보존 룰(`_workspace/arch/adr/README.md`)에 따라 0058~0060·0065는 v3.6.0 시점에도 미사용 slot (후속 트랙 예약 — T-SC·T-RECON 등).

### 3-2. v3.5.0 baseline ADR 후속 갱신 (지속)

**ADR 0019 부분 supersede 갱신 의무 (지속)**:
- v3.2.3 doc cut 갱신 완료
- v3.3.0 ADR 0064 채택(boundary fanout) §2-1 매트릭스 후속
- v3.4.0 ADR 0066 채택(T-SIG signal 발행) §2-1 본체 다음 노드 진입점 후속
- v3.5.0 ADR 0067 채택(T-RISK Risk Gateway) §2-1 본체 의무 게이트 + `risk.violation.v1`·`order.intent.v1` 토픽 후속
- **v3.6.0 ADR 0068 채택(T-O Order Execution) §2-1 본체 종착 노드 + `order.requested/filled/rejected/cancelled.v1` 토픽 + 3중 게이트 자본 보호 LOCK 명시 후속**
- v3.7.x minor 진입 시 architect 단독 PR로 §2 본문 재통합

**ADR 0023 §2-1·§2-2·§2-3 본문 갱신 의무 (지속)**:
- v3.4.0 ADR 0066 Accepted → §2-1 도메인 모델 본문 갱신
- v3.5.0 ADR 0067 Accepted → §2-1·§2-2 본문에 Risk Gateway 의무 게이트 단계 추가
- v3.6.0 ADR 0068 Accepted → §2-1·§2-2 본문에 **Order Execution 종착 노드 + 3중 게이트 + signal → risk → order chain deterministic LOCK 추가**
- 본 v3.6.0 baseline 시점 ADR 0023 본문 정합 갱신은 후속 사이클 (ADR 0068 §12 영향 ADR 매트릭스 정합)

**ADR 0024 (Risk Guard chain 6단) §결정 본문 갱신 의무 (지속)**:
- v3.5.0 ADR 0067 Accepted로 §2 골격 본문에 권위 참조 + verdict path 매핑
- **v3.6.0 ADR 0068 채택으로 §2 본문에 KillSwitch 재검증 단계(OrderRouter step 4) + RealGate denied 분기 추가**
- 후속 사이클 architect 단독 PR

**ADR 0041 (Broker Reconciliation) §결정 본문 갱신 의무 (신규)**:
- **v3.6.0 ADR 0068 채택으로 §결정 본문에 본 ADR `order.filled.v1` Kafka topic이 T-RECON 진입점 baseline 명시 + KIS H0STCNI0 vs internal order_execution drift 검출 baseline 정합**
- 후속 사이클 architect 단독 PR

**ADR 0042 (Position Bootstrap) §결정 본문 갱신 의무 (지속)**:
- v3.5.0 V18 hotfix(#355) — position 테이블이 이미 존재 evidence + ADR 0067 §6-3 ALTER 정합 인용
- **v3.6.0 ADR 0068 채택으로 §결정 본문에 PositionUpdateAdapter 합류점 + BUY/SELL applyExecution 의무 + V18 ALTER 정합 LOCK 추가 + V20~V23 신규 매트릭스 비충돌 IF NOT EXISTS 패턴 적용 evidence**
- 후속 사이클 architect 단독 PR

**ADR 0043 (KillSwitch 행동 정의) §결정 본문 갱신 의무 (지속)**:
- v3.5.0 `RiskRule.KILL_SWITCH_ON` 룰 진입 정합
- **v3.6.0 ADR 0068 채택으로 §결정 본문에 OrderRouter step 4 재검증 단계(ADR 0067 ALLOWED 이후에도 부팅 후 토글 가능 → 재검증 의무) 추가**
- 후속 사이클 architect 단독 PR

**ADR 0044 (Notification System) §결정 본문 갱신 의무 (지속)**:
- v3.5.0 `risk.violation.v1` payload 매트릭스 rule 7건 cover row 추가
- **v3.6.0 ADR 0068 채택으로 §결정 본문에 `order.requested/filled/rejected/cancelled.v1` 4 topic 진입점 + P0/P1/P2 priority 매트릭스 확장 row 추가**
- 후속 사이클 architect 단독 PR

**ADR 0046 (Onboarding & Credential) §결정 본문 갱신 의무 (지속)**:
- **v3.6.0 ADR 0068 채택으로 §결정 본문에 KisOrderHttpAdapter가 `user_kis_credential.account_type=REAL` or `MOCK` 분기 의무 + REAL credential은 3중 게이트 통과 시에만 fetch 의무 + 사용자 메모리 `feedback_kis_key_realonly_for_data.md`(데이터 출처 분기 0) 정합 명시**
- 후속 사이클 architect 단독 PR

**ADR 0048 (KIS WS 41건 구독 한도) §결정 본문 갱신 의무 (지속)**:
- **v3.6.0 ADR 0068 채택으로 §결정 본문에 H0STCNI0/H0STCNI9 사용자별 1 구독 추가 → 사용자당 ~11건 매트릭스 row 추가 + margin 30건 OK 검증 evidence**
- 후속 사이클 architect 단독 PR

**ADR 0066 (T-SIG Signal Engine) §11-4 영향 ADR 갱신 (지속)**:
- v3.5.0 ADR 0067 row 추가
- **v3.6.0 ADR 0068 row 추가**

**ADR 0067 (T-RISK Risk Gateway) §11-4 영향 ADR 갱신 (신규)**:
- **v3.6.0 ADR 0068 채택으로 §11-4에 본 ADR 0068 row 추가 + §결정 본문에 `order.intent.v1` Kafka topic이 T-O 진입점 baseline LOCK 명시**
- 본 ADR 0068 채택 직후 본문 갱신은 후속 사이클

**ADR 0030 master-architecture §10 인덱스 갱신**:
- PR #371 Accepted 시점에 부분 갱신 완료(0068 row 추가)
- §C.12 Order 본문 본 ADR 권위 인용은 후속 사이클 (modify-shared 충돌 회피)

**ADR 0052 §X 보강 (PR #367)**:
- "release notes 작성 절차" 5 단계 신규 추가 — plan → baseline 생성 → html → Hero 본문 추출 → release.sh --backfill 자동 합성 (사용자 점검 흡수)

---

## §4. hotfix 패턴 — v3.6.0 사이클 0건 (정밀 명세 LOCK 효과)

v3.6.0 사이클의 hotfix는 **0건** (v3.5.0 사이클 V18 ADR 0042 충돌 hotfix 패턴 재발 회피). 사용자 메모리 `feedback_verification_strict.md` 정합 — Phase 1·2·3 모두 사전 정밀 명세서(ADR 0068 §14·§15·§16) LOCK 적용으로 sub-agent 자체 추론 0 + 오류 risk 매트릭스 사전 정의 + IF NOT EXISTS 패턴 적용 + Flyway DDL 본문 LOCK.

**v3.6.0 사이클 안정성 핵심**:
1. **ADR 0068 정밀 명세 §14·§15·§16·§17**(PR #372) — Phase 별 sub-agent 디스패치 사전 정밀 명세 LOCK + 오류 risk 매트릭스 사전 정의(10+8+7건) + Flyway IF NOT EXISTS 의무 명시(V18 충돌 재발 회피)
2. T-O Phase 1(PR #373) — domain Order/Position 12건 + 9 port + consumer 10 컴포넌트 + 4 Kafka producer + 26 Unit Test — `:domain:check` + `:consumer:check` GREEN
3. T-O Phase 2(PR #374) — Flyway V20~V24(IF NOT EXISTS 패턴 적용) + JPA 3쌍 + REST 4 endpoint + StompOrderNotifier + 통합 테스트 GREEN + `:api:check` GREEN — **부팅 시 V20·V21·V22·V23 모두 정상 적용 evidence**
4. T-O Phase 3(PR #375) — frontend 2 페이지 + 6 컴포넌트 + 5 hook(useStompOrder 4 event 토스트 매트릭스) + RealTradingConsentToggle 이중 confirm + `pnpm typecheck && pnpm build` GREEN
5. release 절차 보강(PR #367·#368·#369) — release.sh 풍부 합성 + ADR 0052 §X + README 매트릭스 자동 갱신 헬퍼 — 사용자 점검 흡수 정합

> **v3.5.0 → v3.6.0 사이클 hotfix 0건은 정밀 명세 LOCK 효과** — ADR 0068 §14·§15·§16 정밀 명세서 Phase 별 sub-agent 디스패치 사전 정의 + 오류 risk 매트릭스 사전 정의(25건) + Flyway DDL 본문 LOCK + IF NOT EXISTS 패턴 적용 = 자체 추론 0 + 재발 회피. architect spec/plan PR(ADR 0068) → 사용자 결정 의제 8건 검토 → 정밀 명세 보강 PR(#372) → 본격 구현 PR 매트릭스 3건 → release 표준 cycle 진행. ADR 0042·0067 baseline 회귀 0 + ADR 0068 도메인 본문 변경 0 — 단순 마이그레이션 자체 결함 0건.

---

## §5. backlog 처리 매트릭스 (v3.6.0 사이클)

`_workspace/backlog.md` § 단위 추적.

### 5-1. 처리 완료 §

| § | 제목 | 처리 PR | 후속 |
|---|------|---------|------|
| **§43** | T-O 주문 실행 (W3 네 번째 트랙 — ADR 0068 plan 발행) | architect PR #370 plan / #371 Accepted / #372 정밀 명세 / strategy-engine #373 Phase 1 / backend-core #374 Phase 2 / frontend #375 Phase 3 | 처리 완료. 다음 minor cleanup에서 archive |

### 5-2. 신규 등록 § (의제 후속)

| § | 제목 | 등록 PR | 트리거 |
|---|------|--------|--------|
| (보류) §44+ | T-O 후속 백로그 (D-2 polling fallback / E-2 자동 취소 worker / E-3 정정 도입 / C-2 슬리피지 / token bucket queue) | ADR 0068 §10 매트릭스 | 사용자 dogfooding 사례 누적 후 |
| (보류) §45+ | W3 잔여 트랙 (T-SC·T-NOTIFY·T-PERF·T-RECON·T-J) 등록 예정 | — | 다음 사이클 plan PR |

> §43 v3.6.0 사이클에서 처리 완료. §36 B(local branch 잔재) + §39(T-BT 모델 확장) + §40(T-SIG 후속 다채널) + §41(T-RISK) + §42 유지. 다음 minor 사이클에서 backlog archive 매트릭스 일괄 처리 권장.

### 5-3. v3.6.0 사이클 신규 보강 매트릭스 (별도 사이클 후속 LOCK)

| 영향 ADR/문서 | 갱신 내용 | 갱신 시점 |
|---|---|---|
| ADR 0023 §2-1·§2-2 | Order Execution 종착 노드 + 3중 게이트 + signal → risk → order chain deterministic LOCK 추가 | architect 단독 PR — 별도 사이클 |
| ADR 0024 §2 골격 본문 | KillSwitch 재검증 단계(OrderRouter step 4) + RealGate denied 분기 추가 | architect 단독 PR — 별도 사이클 |
| ADR 0041 §결정 본문 | `order.filled.v1` T-RECON 진입점 baseline + KIS H0STCNI0 vs internal order_execution drift 검출 baseline | architect 단독 PR — 별도 사이클 |
| ADR 0042 §결정 본문 | PositionUpdateAdapter 합류점 + V20~V23 IF NOT EXISTS 패턴 evidence | architect 단독 PR — 별도 사이클 |
| ADR 0043 §결정 본문 | OrderRouter step 4 재검증 단계 추가 | architect 단독 PR — 별도 사이클 |
| ADR 0044 §결정 본문 | 4 order.* topic 진입점 + P0/P1/P2 priority 매트릭스 확장 | architect 단독 PR — 별도 사이클 |
| ADR 0046 §결정 본문 | account_type REAL/MOCK 분기 의무 + 데이터 출처 분기 0 명시 | architect 단독 PR — 별도 사이클 |
| ADR 0048 §결정 본문 | H0STCNI0 사용자별 1 구독 매트릭스 row 추가 | architect 단독 PR — 별도 사이클 |
| ADR 0066 §11-4 | 본 ADR 0068 row 추가 | architect 단독 PR — 별도 사이클 |
| ADR 0067 §11-4 + §결정 본문 | 본 ADR 0068 row + `order.intent.v1` T-O 진입점 baseline LOCK | architect 단독 PR — 별도 사이클 |
| ADR 0019 §2-1 | 종착 노드(`order.requested/filled/rejected/cancelled.v1`) + 3중 게이트 자본 보호 LOCK 명시 | architect 단독 PR — 별도 사이클 |
| ADR 0030 master-architecture §10 | ADR 0068 row 추가 (PR #371에서 부분 갱신 완료) + §C.12 Order 본문 권위 인용 | 검증 완료 + 후속 §C.12 본문 갱신 별도 |
| `_workspace/arch/adr/README.md` §6 row | ADR 0068 `Proposed` → `Accepted` 표기 + §11 §12 후속 row | architect 단독 PR — PR #371 부분 갱신 완료 |
| `docs/architecture/tracks-roadmap.md` | T-O row v3.6.0 마감 표기 + W3 잔여(T-SC·T-NOTIFY·T-PERF·T-RECON·T-J) 진입 매트릭스 | architect 단독 PR — 별도 사이클 |
| `_workspace/backlog.md` §43 | DONE 표기 + 다음 사이클 cleanup archive | 별도 사이클 |

> 본 v3.6.0 baseline 컷 시점에는 ADR 0068 채택 직후 사이클이라 위 매트릭스 본문 갱신 미반영 — **v3.7.x minor 진입 시 architect 단독 PR로 일괄 정정** (modify-shared 충돌 회피).

---

## §6. 결론 — v3.6.0 사이클 핵심 통계

| 지표 | v3.5.0 (2026-05-12 22:42 KST) | v3.6.0 (2026-05-13 <TBD> KST) | 증감 |
|------|---------------------|---------------------|------|
| PR # 누적 | #360 | #376 | +16 |
| ADR 수 | 64 (0067 Accepted) | 65 (0068 Accepted) | +1 (실제 신규 1건) |
| ADR Superseded | 1 (0049 GHA 폐기) | 1 보존 | 0 |
| Flyway 버전 | V19 (signal.risk_status) | V24 (V20 order_request + V21 order_execution + V22 order_audit_event + V23 user_account.real_trading_consent_at ALTER + V24 spare) | +5 |
| JVM 모듈 | 5 (domain·api·batch·consumer·pattern-engine) | 5 (보존) | 0 |
| 외부 통합 (활성) | KIS + DART + Anthropic (3) — Yahoo @Deprecated | 그대로 보존 (v3.7.x 일괄 삭제 예정) | 0 |
| **W3 ★ 트랙 마감** | **3 (T-BT·T-SIG·T-RISK)** | **4 (T-O W3 네 번째 트랙 마감)** | **+1** |
| 외부 통합 추가 호출 (T-O 영역) | — | **신규 4 KIS endpoint 매트릭스: order-cash + order-rvsecncl REST + H0STCNI0/H0STCNI9 WS + 6 tr_id 분기 (TTTC/VTTC 0012U·0011U·0013U) — KIS-first raw KDoc 의무 LOCK** | 신규 가동 |
| KIS WS 구독 한도 영향 (ADR 0048 — 41건) | + H0STCNT0·H0STASP0 시세 (사용자당 ~10건) | **+ H0STCNI0/H0STCNI9 체결통보 1 구독 추가 — 사용자당 ~11건 (margin 30건 OK)** | +1 구독/user |
| Kafka 토픽 (활성) | 15 (+ `risk.violation.v1` + `order.intent.v1`) | 19 (+ `order.requested.v1` + `order.filled.v1` + `order.rejected.v1` + `order.cancelled.v1`) | +4 |
| Redis 키 namespace (활성) | + `risk_config:{userId}` + `trades:freq:...` ZSET + `daily_pnl:...` + `balance:user:{userId}` | + **`idem:order:{idempotencyKey}` TTL 60s SETNX (멱등성 H-1)** | +1 namespace |
| STOMP 토픽 (활성) | + `/topic/risk.user.{userId}` | + **`/topic/order.user.{userId}` (REQUESTED·FILLED·REJECTED·CANCELLED 4 event type discriminator · multi-tenant 격리)** | +1 |
| REST endpoint 누적 | 약 55건 | 약 59건 (+ Order 3 + RealTradingConsent 1) | +4 |
| frontend 라우트 | 10 | 12 (+ `/orders` + `/orders/:id`) | +2 |
| domain port 누적 | 18 | 27 (+ OrderRequestRepository · OrderExecutionRepository · OrderAuditEventRepository · KisOrderPort · TradingModeGate · OrderIdempotencyGuard · OrderNotifier · PositionUpdatePort · KillSwitchPort) | +9 port |
| `OrderRouterKafkaListener` 12 step LOCK | 0 | **12 step (deserialize → idempotency SETNX 60s → 3중 게이트 → KillSwitch 재검증 → order_request INSERT → STOMP REQUESTED + publish → KIS REST submit (5xx 1회 retry) → ACK 또는 REJECT 분기 → Kafka commit MANUAL_IMMEDIATE → Micrometer counter)** | 신규 baseline |
| KIS tr_id 매트릭스 (T-O) | — | **TTTC0012U (실전 BUY) · VTTC0012U (모의 BUY) · TTTC0011U (실전 SELL) · VTTC0011U (모의 SELL) · TTTC0013U (실전 정정/취소) · VTTC0013U (모의 정정/취소) · H0STCNI0 (실전 체결) · H0STCNI9 (모의 체결) — 8 tr_id KIS-first raw KDoc 의무** | +8 tr_id |
| **3중 게이트 자본 보호 LOCK** | — | **env MAGICJAR_MODE=REAL × env MAGICJAR_ALLOW_REAL=true × user_account.real_trading_consent_at IS NOT NULL — 모두 충족 시에만 KIS prod endpoint(openapi.koreainvestment.com:9443) 호출. 부재 시 즉시 REJECTED + audit REAL_GATE_DENIED + STOMP REJECTED + KIS prod 호출 0건 LOCK** | 신규 LOCK |
| T-O 영속 | 0 | order_request(~18K/year per user) + order_execution(~27K/year per user 분할 1:N) + order_audit_event(~91K/year per user) + user_account.real_trading_consent_at (V23 ALTER) | 신규 가동 |
| OrderStatus enum | — | **7단 (REQUESTED·ACK·FILLED·PARTIALLY_FILLED·REJECTED·CANCELLED·EXPIRED)** | 신규 baseline |
| OrderAuditEventType enum | — | **12단 (REAL_GATE_DENIED·IDEMPOTENCY_SKIPPED·KILLSWITCH_DENIED·KIS_ACK·KIS_REJECT·KIS_EXCEPTION·FILL_RECEIVED·PARTIAL_FILL·FULL_FILL·KIS_CANCEL_ACK·KIS_CANCEL_REJECT·EXECUTION_TIMEOUT + EXECUTION_OVERFLOW)** | 신규 baseline |
| AI 호출 (T-O 영역) | — | **0건 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 grep 검증 + inline 주석 의무)** | LOCK |
| end-to-end latency (boundary 마감 → 사용자 STOMP REQUESTED 토스트 + KIS ACK) | <175ms total (boundary 20 + signal 75 + risk 75 + STOMP 5) | **<405ms total** (boundary 20 + signal 75 + risk 75 + order intent publish 10 + OrderRouter 20 + KIS REST 200 + STOMP 5) | +230ms (order intent → KIS ACK 단계) |
| 체결 통보 latency (KIS WS H0STCNI0 → STOMP FILLED) | — | **<50ms** (KIS 호가 체결 ~즉시 + 26 컬럼 매핑 + STOMP fanout) | 신규 측정 |
| dogfooding hotfix 건수 | 1 (V18 ADR 0042 충돌) | **0 (정밀 명세 §14·§15·§16 LOCK + IF NOT EXISTS 패턴 적용 효과)** | -1 |
| 단위 테스트 신규 (T-O 영역) | — | **26건** (OrderRouterKafkaListener 6 + TradingModeGateImpl 4 + OrderIdempotencyGuardImpl 3 + KisOrderHttpAdapter 5 + KisExecutionWsListener 5 + 기타 3) | 신규 |
| GitHub Actions workflow | 0건 (v3.4.0 GHA 폐기) + `scripts/release.sh` | 0건 보존 + `release.sh` 풍부 합성(PR #367) + `scripts/sync-readme-releases.mjs` 신규(PR #369) | +1 헬퍼 |
| README 매트릭스 | 굴직 재작성 373 lines (PR #359) | + Releases & Baseline 진화 매트릭스 추가(PR #368) + 자동 갱신 헬퍼 정합 | 매트릭스 add |
| 운영 헌법 § 수 | 7 | 7 (보존) | 0 |
| Living Policy 문서 | 6 | 6 (보존) | 0 |
| GitHub Release 풍부 재발행 (사용자 점검 evidence) | v3.3.0까지 baseline-level / v3.3.1~v3.5.0 풍부도 0 | **8 release(v3.1.0·v3.1.1·v3.2.1·v3.2.2·v3.2.3·v3.3.1·v3.4.0·v3.5.0) 풍부 재발행** (PR #367) | +8 풍부 |
| ADR 0052 §X 신규 | — | **"release notes 작성 절차" 5 단계 추가** (plan → baseline 생성 → html → Hero 본문 추출 → release.sh --backfill 자동 합성) | 신규 §X |

**v3.6.0 사이클 핵심 메시지**: **T-O 주문 실행 가동 (ADR 0068 채택) — 자동매매 본체 종착 실행 노드 진입 + 3중 게이트 자본 보호 LOCK** — T-RISK ALLOWED 시그널이 Kafka `order.intent.v1` → OrderRouterKafkaListener 12 step 게이트 chain(idempotency + 3중 게이트 + KillSwitch + KIS REST + 체결 추적 + STOMP) 통과. magicJar 비전 "룰 기반 quant trading 본체"의 W3 종착 노드 + 3중 게이트 자본 보호(env × env × 사용자 동의 토글) + KIS-first raw KDoc 의무(8 tr_id + 26 컬럼) + 분할 체결 1:N cover + 멱등성 Redis SETNX 60s + DB UQ 이중 + 이중 confirm UX. 정밀 명세 §14·§15·§16·§17 추가(PR #372)로 sub-agent 자체 추론 0 + 12 step LOCK + 오류 risk 매트릭스 사전 정의(25건) + dogfooding 3 케이스. AI 호출 0 grep 검증 지속(ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8).

**다음 minor 사이클(v3.7.x 또는 v3.8.0) 의제**:
1. ADR 0019·0023·0024·0041·0042·0043·0044·0046·0048·0066·0067 본문 갱신 (T-O 합류 + V14~V24 baseline 재할당 + `order.*` 본체 종착 노드 정합 LOCK) — architect 단독 PR
2. Yahoo `YahooCandleFetcherAdapter`·관련 yaml 환경 완전 삭제 — market-data 단일 PR (v3.7.x minor 일괄)
3. **W3 잔여 트랙 진입** — T-SC(Screener) · **T-RECON(Reconciliation — `order.filled.v1` consume → KIS 잔고 vs internal drift 검출 + position 동기)** · T-NOTIFY(Slack webhook + `order.*` · `risk.violation.v1` · `signal.generated.v1` 합류 ADR 0044) · T-PERF(Sharpe·MDD·Calmar baseline) · T-J(Trading Journal — order_audit_event 시계열 + 사용자 회고)
4. strategy_eval_metric V25+ 발행 (ADR 0047 §2-6 V27 재할당 후속 — backend-core 단일 PR, V24 spare 후속)
5. backlog §29·§30·§31·§32·§33·§34·§35·§36 B·§37·§38·§39·§40·§41·§43 archive 매트릭스 정리
6. T-O 후속 — sample 주문 시나리오 dogfooding 검증(6 tr_id each 주문 evidence + 분할 체결 + RealGate denied evidence + KIS prod 호출 0건 검증) — qa 트랙 또는 사용자 dogfooding 사이클
7. MCP 서버 진입 baseline 평가 — ADR 0066 signal/strategy + ADR 0067 risk_event/position + **ADR 0068 order_request/order_execution/order_audit_event REST API**가 외부 client 노출 정합 (ADR 0027 MCP)
8. order monthly partition 후속 ADR(가칭 0086+) — order_audit_event row ≥ 5M 도달 시 발동 (5년 누적 후)
9. T-O 후속 backlog §44+ — D-2 polling fallback / E-2 자동 취소 worker / E-3 정정 도입 / C-2 슬리피지 / token bucket queue / risk-eval-pool 별도 도입
