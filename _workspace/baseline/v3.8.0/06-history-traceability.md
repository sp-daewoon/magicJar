# 06 — PR · ADR · 기능 추적성 매트릭스 (v3.7.0 → v3.8.0)

> **작성자**: architect (PR-37 baseline v3.8.0)
> **시점**: 2026-05-22 v3.8.0 태그 컷 직후 (release commit `<TBD>`, `release/v3.8.0` 이미지 immutable)
> **범위**: v3.7.0 (2026-05-19 KST) → v3.8.0 (2026-05-22 KST 시점) 사이 **1 patch 릴리즈(v3.7.1) + 86+ PR + ADR 신규 6건(0091·0092·0093·0094·0095·0096) + ADR amend 3건(0080·0085·0087) + ADR Superseded 1건(0085) + 운영 헌법 §9·§10 신설 + 8 → 7 sub-agent reshuffle (chart-curator 해고 + qa Visual 격상 + ai-engineer 유지 + sre 영입 거절) + P0 OPS 6/6 LOCK + Stage 2 Cleanup + v3.8.0 cut 차단 hotfix 3건**.

본 문서는 **PR·ADR·기능 3축 추적성 매트릭스**다. raw 출처:
- `git log v3.7.0..main --oneline` (worktree 직접 실행 — 134 commits)
- `git tag --list "v3.7.*"` 결과 인용 (v3.7.1 1건)
- `CHANGELOG.md` 시계열 row
- `_workspace/arch/adr/README.md` 인덱스 (90 → 96, 0091·0092·0093·0094·0095·0096 신규 6건 + 0080·0085·0087 amend)
- `_workspace/backlog.md` § 단위 (운영 단계 transition + P0 OPS 6건 매트릭스)

---

## §1. v3.7.0 → v3.8.0 minor 사이 patch 시계열

| 버전 | 일자 | 핵심 미러스톤 | 주요 ADR | 주요 PR | 사용자 결재 |
|------|------|---------------|----------|---------|------------|
| v3.7.0 | 2026-05-19 | 시스템 점검 + Phase 보강 + 운영 헌법 §1-A-extended·§2 6단계 baseline | 0088·0089·0090 + 헌법 §1-A-extended | #548~#575+ | "시스템 점검부터 차근차근 + W4 진입 안 함" |
| v3.7.1 | 2026-05-19 | persistence-shared JpaRepository scan 누락 hotfix + SettingsModal MOCK 키 신규 등록 POST 분기 | 0088 baseline fix | #583·#584·#586 | "v3.7.0 첫 hotfix 흡수" |
| **v3.8.0** | **2026-05-22** | **운영 단계 진입 baseline (개발 단계 종착 + 7 actor reshuffle + qa Visual 격상 + P0 OPS 6/6 LOCK)** | **0091·0092·0093·0094·0095·0096 + 운영 헌법 §9·§10 신설** | **#587~#661+** | **"이제 개발의 전반은 다 되어가기때문에 시스템 운영 단계 및 부분 고도화로 갈 준비를 해야해" + "큐에이도 지금 필요한데... 스크린샷 등 하면서 테스트 시킬라했어"** |

---

## §2. v3.8.0 사이클 라운드별 PR 매트릭스 (v3.7.1 patch → v3.8.0 cut)

### 2-1. 사이클 1 — v3.7.1 patch hotfix 3건 (2026-05-19 KST)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| #583 | (architect/v3.7.1-hotfix) | architect hotfix | v3.7.1 persistence-shared JpaRepository scan 누락 fix |
| #584 | (api,consumer/jparepositories-unified) | backend-core hotfix | v3.7.1 #2 — Application JpaRepositories 통합 scan (api·consumer·persistence-shared) |
| #586 | (frontend/settingsmodal-mock-key-post) | frontend hotfix | SettingsModal MOCK 키 신규 등록 POST 분기 (v3.7.1 dogfooding evidence) |

→ **v3.7.1 minor 정합 patch** (시스템 점검 baseline 후 첫 운영 dogfooding evidence 흡수)

### 2-2. 사이클 2 — 캔들 stale 재발 + KIS 토큰 통합 + NXT resolver (2026-05-19·20 KST)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| #587 | (chart-curator/audit) | chart-curator audit | 캔들 12-30 stale 재발 root cause 진단 (사용자 dogfooding evidence + 이전 fix 매트릭스 검증) |
| #588 | (backend-core/b-2-api-candle-refresh-pagination) | backend-core | B-2 api CandleRefreshService cursor 페이지네이션 동조 fix |
| #589 | (architect/e-skill-trap-8-10-hedging-14) | architect | E chart-curator §함정 #8·#10 + 햇징 §14 + ADR 0086 §14 amend (PR #587 regression 영구 차단) |
| #590 | (chart-curator/b-1-consumer-pagination-restore) | chart-curator | B-1 consumer KIS adapter cursor 페이지네이션 복원 (PR 2cbd4e8 검증된 패턴 1:1 재적용) |
| #592 | (backend-core/t-1234-kis-token-unify) | backend-core | T-1·2·3·4 KIS 토큰 cache prefix 통합 + 동적 TTL + 분산락 + C-2 Account cache 5s 단축 (502 root cause fix) |
| #593 | (frontend/c-3-c-5-polling-immediate-refetch) | frontend | C-3 5s polling + C-5 toggle immediate refetch (자본 시각화 즉시성) |
| #594 | (backend-core/t-1234-c-2-account-cache) | backend-core | merge — C-2 Account cache 후속 |
| #595 | (chart-curator/symbolmetaservice-currentprice) | chart-curator hotfix | SymbolMetaService currentPrice 매핑 hotfix — SK하이닉스 1,840,000 vs HTS 1,719,000 dogfooding fix |
| #597 | (api·consumer·domain/kis-inquire-price-raw) | architect | 좌측 시세창 가격 path = candle DB 1d single source + NXT 야간 휴리스틱 + endpoint inquire-daily-itemchartprice 교체 |

→ **W3 자동매매 본체 dogfooding hardening + KIS 토큰 통합 + NXT resolver candidates**

### 2-3. 사이클 3 — ADR 0091·0092 + chart limit + autotrading hardening (2026-05-20 KST)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| (PR-NXT) | (architect/adr-0091-nxt-resolver) | architect | **ADR 0091 Accepted — NXT 통합 시장코드 종목 단위 resolver** (KOSPI=UN/KOSDAQ=J market-level 분기 폐기 + is_kospi200/is_kosdaq150 allow-list + J fallback) |
| (PR-StratEng) | (strategy/share-jvm-evaluator) | strategy-engine | **ADR 0092 Accepted — strategy-engine 라이브러리 모듈 분리** (DSL parser/evaluator 공유 + API 백테스트·screener HOLD stub 제거 + consumer 실시간 평가 단일 evaluator) |
| #597 hotfix series | (api·consumer/kis-token-related) | backend-core | KIS 토큰 빈 userId hotfix (cache pollution + cross-user leak 차단) |
| (A·B·C·D 사이클) | (backend-core·market-data·frontend) | autotrading hardening | A T-ACCT Dashboard 텍스트 5s 정정 + QuickLink DRY · B T-MAN idempotencyKey 강화 + DTO validation · C OrderOutbox timeout + FOR UPDATE SKIP LOCKED + ObjectProvider 정정 · D Rate Limit Phase 2 마감 + try-finally drift 차단 |
| #609 | (chart-curator/chart-candle-history-limit-spec) | chart-curator | Chart Candle History Limit + Lazy Backfill spec 발행 |
| (PR-0093) | (architect/adr-0093-chart-limit) | architect | **ADR 0093 Proposed — 차트 캔들 히스토리 limit + Lazy Backfill + 사용자 입력 봉수** |
| (Phase 3·4·5) | (backend-core·frontend·chart-curator) | implementation | Phase 3 chart MAX_PAGES property + /candles limit cap 5000 · Phase 4 chart CandleCountSelector UI + localStorage · Phase 5 LWC 가드 3단 + max cap toast + 한국석유 dogfooding |
| (autotrading hardening series) | (backend-core/orchestrator) | autotrading | KillSwitchTogglePort Bean 미등록 (자본 path 차단 — 부팅 실패) hotfix · PositionBootstrapRunner A 의제 3중 게이트 trigger · B 의제 retry 메커니즘 (KIS transient 흡수) |
| (PR-EGW00201) | (backend-core/EGW00201-burst-fix) | backend-core | EGW00201 burst 흡수 — KIS rate limit acquire/WebClient timeout 통합 확대 |

→ **ADR 0091·0092·0093 W3 보강 + autotrading hardening 본격**

### 2-4. 사이클 4 — system event log P1·P2·P3 (2026-05-20·21 KST)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| #621 (commit ade93d1·6a86e32·5ac7430·8deb37c·c8a8f0f) | (backend-core/system-event-p1) | backend-core | **system event log P1** — Flyway V44 + SystemEvent enum + SystemEventLogger port + SystemEventJpaEntity + Repository + Kafka producer + LoggerAdapter (4·2 test 통합) |
| (P1 emit point) | (backend-core/system-event-emit-points) | backend-core | OrderReconciliationWorker FIRED+RESOLVED · BalanceReconcileService DRIFT_DETECTED · KisWebSocketBridge WS_DISCONNECTED+RECONNECTED correlationId |
| (P2 backend) | (backend-core/system-event-p2-api) | backend-core | SystemEvent REST API list + PATCH viewed/resolved · Kafka → STOMP fanout 분기 |
| (P2 frontend) | (frontend/system-event-p2-frontend) | frontend | system-event REST API client + types · useSystemEventList REST hook 30s polling fallback · useSystemEventStompSubscription hook (5 test) · NotificationCenter 벨 + 드롭다운 (1 test) · /system Dashboard 페이지 · App routes /system + Header NotificationCenter 통합 |
| (P2.1 fix) | (backend-core/system-event-p2-1-fix) | backend-core | SystemEventStompNotifier — StompEventPersister 호출 (PR #621 패턴 정합) · SystemEventQueryService findList filter 정합 (totalCount·unreadCount 정확) |
| (system event hardening) | (orchestrator/system-event-dashboard-hardening) | orchestrator | system event dashboard flow hardening merge |
| (P3 plan) | (architect/system-event-p3-plan) | architect | system event log P3 implementation plan (재정의 — 의제 B + Gap 1 + catalog) |

→ **운영 가시화 영역 본격 발행** (P0-OPS-2 Prometheus + P1 system event log 동반 가동)

### 2-5. 사이클 5 — account balance STOMP push + WS health indicator + 자본 path hotfix series (2026-05-20·21 KST)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| (backend/account-balance-stomp-push) | (backend-core/balance-stomp-push) | backend-core | account balance STOMP push (polling → push 전환 backend) |
| (frontend/account-balance-stomp-subscribe) | (frontend/balance-stomp-subscribe) | frontend | account balance STOMP subscribe (polling → push 전환 frontend) |
| #4aba93d3 | (backend-core/order-request-source) | backend-core hotfix | order_request consumer entity source 매핑 누락 (T-MAN Phase 1 PR #510 회귀) |
| #603915fe | (backend-core/kis-ws-h0stcni9-aes) | backend-core hotfix | KIS WS H0STCNI9 라우팅 + AES-256-CBC 복호화 (체결통보 처리 회복) |
| #ed5d62a6 | (backend-core/accountquery-cache-key) | backend-core hotfix | AccountQueryService cache key accountType 분리 (REAL/MOCK 충돌 + EGW00201 burst root cause) |
| #b6d17fbc | (backend-core/order-filled-stomp-qty) | backend-core hotfix | order FILLED stomp_event qty/executedQty 매핑 누락 fix (사용자 dogfooding 0주 toast) |
| #ac5d9274 | (frontend/ws-indicator) | frontend | WS 접속 상태 indicator + 끊김 toast (헤더 영구 표시) |
| #4fceb5d6·#1568637f | (frontend/backend-core/order-name) | frontend + backend-core | OrderCard·OrderDetailPage 종목명 표시 + OrderListItemDto·OrderDetailDto name 필드 + instrument join (hotfix) |
| #9799760b | (backend-core/markresolved-viewed) | backend-core hotfix | markResolved 시 viewed_at 동반 마킹 (사용자 mental model 정합) |
| #368555ec | (frontend/kis-ws-health-indicator) | frontend | KIS WS health indicator + 끊김 toast (STOMP indicator와 분리) |
| #c1f37fe5 | (frontend/delayed-data-label-fix) | frontend hotfix | 지연 데이터 라벨 fall-through 결함 + KRX 운영 시간 매트릭스 확장 |
| #640 | (backend-core/kis-egw00201-systemevent) | backend-core hotfix | KIS EGW00201 분기 + SystemEvent KIS_RATELIMIT P1 활성 (502 root cause fix) |
| #775c87fe | (api/kis-token-self-heal) | api hotfix | KIS 토큰 EGW00123 self-heal (cache evict + 1회 재발급) |
| #828e7a7c | (frontend/backfill-toast-3-fix) | frontend hotfix | backfill toast 결함 3건 (qty=null skip + copy 정정 + lastSeenAt 갱신) |
| #643 | (frontend/positions-data-source) | frontend hotfix | PositionsPage data source 정합 (account/positions 사용, 사용자 dogfooding 빈 화면) |
| #644 | (market-data/ws-reconnected-emit) | market-data hotfix | KisWebSocketBridge WS_RECONNECTED emit 누락 fix (사용자 dogfooding 거짓 끊김 표시) |
| #645 | (market-data/ws-first-connect-emit) | market-data hotfix | KIS WS startup 첫 연결 WS_CONNECTED emit (PR #644 한계 — 거짓 끊김 영구 잔존) |

→ **자본 path hardening + 운영 가시화 hotfix series** (dogfooding evidence 누적)

### 2-6. 사이클 6 — 운영 단계 transition (ADR 0094) + KillSwitch race-safe (2026-05-21 KST)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| #7ba7cda6 | (architect/ops-stage-transition-master-plan) | architect | **운영 단계 transition master plan** (audit + agent reshuffle + transition) |
| #22182b03 | (architect/adr-0094-amend) | architect | **ADR 0094 amend (Accepted)** — orchestrator 자율 결재 — 8→7 + qa visual 격상 + ai-engineer 유지 + sre 영입 거절 |
| #647 (commit caa7ea08) | (architect/killswitch-race-safe-policy) | architect | KillSwitch race-safe policy (A+B+C — ADR 0043·0070 amend) |
| #648 (commit 3ef5cb78) | (consumer/killswitch-race-safe-guard) | backend-core (consumer hotfix) | **KillSwitch race-safe guard (A+B+C — pending+grace+strike)** 본격 hotfix consumer |

→ **운영 단계 진입의 결정적 turning point** (사용자 위임 결재로 ADR 0094 amend Accepted)

### 2-7. 사이클 7 — P0 OPS 6/6 LOCK (2026-05-21 KST)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| #649 (commit 8544ba21) | (qa/visual-baseline) | qa | **Visual QA 첫 셋업 — Playwright + 5 시나리오 baseline** (v3.8.0 운영 baseline P0) ★ qa 격상 후 첫 dispatch |
| #649 (commit 26f83f39) | (backend-core/p0-ops-1-2-observability) | backend-core | **P0-OPS-1·2 — logback JSON + Actuator/Prometheus + Grafana** (v3.8.0 운영 baseline P0) |
| #650 (task ref T-20260521-2157) | (backend-core/p0-ops-logback-actuator) | backend-core | P0-OPS-1·2 task archive |
| (commit b89eed6b) | (architect/adr-0095-slo-sla) | architect | **ADR 0095 Accepted — P0-OPS-3 SLO/SLA + alert routing** (v3.8.0 운영 baseline P0) |
| (commit 49f1bc31) | (backend-core/p0-ops-5-runbook) | backend-core | **P0-OPS-5 — runbook 5 시나리오** (v3.8.0 운영 baseline P0) |
| (commit 60045dc1) | (backend-core/p0-ops-6-persistence-monitoring) | backend-core | **P0-OPS-6 — persistence monitoring + SLO 측정 source wiring + Grafana SLO dashboard** (v3.8.0 운영 baseline P0 완료★) |
| (commit b1aab9e9) | (architect/adr-0096-dr-backup) | architect | **ADR 0096 Accepted — P0-OPS-4 DR/backup ADR + pg_dump cron** (v3.8.0 운영 baseline P0) |

→ **P0 OPS 6건 모두 마감 LOCK** (logback JSON + Actuator + Prometheus + Grafana + SLO/SLA + DR/backup + runbook + persistence monitoring)

### 2-8. 사이클 8 — Stage 2 종합 정리 + qa.md 등록 + chart-curator archive (2026-05-21 KST)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| #655 (commit 5472a304) | (architect/stage2-cleanup) | architect | **Stage 2 종합 정리** (ADR audit + 헌법 + SoT + 메모리 보고서 + CHANGELOG drift + backlog + qa.md 등록 + chart-curator archive + orphan decision audit) — v3.8.0 cut 전 LOCK |
| (commit b84f23de) | (chore/stage2-task-done) | architect | T-20260521-2258-stage2-cleanup DONE — PR #655 |

→ **v3.8.0 cut 전 토큰·메모리 정제 LOCK** (사용자 명시 "v3.8.0 cut 전에 Phase C + qa 셋업 완료 + ★★★ ADR·헌법 정리를 한번 했으면 좋겠어")

### 2-9. 사이클 9 — qa 첫 실전 임무 + v3.8.0 cut 차단 hotfix 3건 (2026-05-22 KST)

| PR | head ref | 종류 | 요약 |
|----|----------|------|------|
| #656 (commit 026e16e0) | (qa/first-mission-report) | qa | **qa 첫 실전 임무 보고서** — 종목상세 현재가 결함 hunt (Visual QA 첫 가동) |
| (commit a19634f4) | (orchestrator/stale-cleanup) | orchestrator | stale task·ADR drift·untracked 잔재 일괄 정리 |
| #659 (commit 9f935092) | (market-data/kis-tps-burst-fix) | market-data hotfix | **KIS_TPS_EXCEEDED LIVE root cause hotfix** — LiveDailyCandleScheduler 종목 간 200ms sleep 마진 (burst 완화) |
| #659 (commit 6b9353e5) | (market-data/kis-tps-comprehensive) | market-data hotfix | **KIS_TPS_EXCEEDED LIVE 종합 fix** — H1 페이지 burst 완화 + H4 EGW00201 1초 backoff+재시도 + priority=BACKFILL 분리 |
| #660 (commit 4f9e7079) | (backend-core/qa-first-mission-p0-hotfix) | backend-core hotfix | **QA 첫 임무 P0 hotfix** — NXT 30% 휴리스틱 + stale 30분 검증 |
| #661 (commit 2635e6fe) | (market-data/kis-inquire-daily-ccld-block-fix) | market-data hotfix | **consumer KIS inquire-daily-ccld `.block()` reactor-http-nio ERROR fix** (v3.8.0 cut 차단 P0) — `.publishOn(Schedulers.boundedElastic())` 2 어댑터 합류 |

→ **v3.8.0 cut 차단 사항 모두 해소** (qa Visual 첫 실전 임무 + KIS TPS burst 종합 fix + reactor thread ERROR fix)

### 2-10. v3.8.0 cut + baseline init (2026-05-22 KST)

| PR | 종류 | 요약 |
|----|------|------|
| (commit da9a7077) | orchestrator baseline init | **chore(orchestrator): baseline v3.8.0 init — 폴더 + 5 slot + README skeleton** — `_workspace/baseline/v3.8.0/` 폴더 + 5 sub-agent slot anchor 사전 prepend (architect/backend-core/market-data/frontend) |
| (현 PR #37) | architect | **docs(baseline): PR-37 architect — v3.8.0 baseline 4 파일 (README + 01-architecture + 06-history + 07-policy)** — 본 PR |
| (가칭 PR-37+) | backend-core·market-data·frontend | sub-agent 영역 baseline (02·03·04·05) — orchestrator 통합 dispatch 매트릭스 |
| (가칭 #future-release) | release | **release: v3.8.0 — 운영 단계 진입 baseline (개발 단계 종착)** — orchestrator release commit + CHANGELOG row + master §10 ADR 인덱스 0091·0092·0093·0094·0095·0096 갱신 + release.sh 풍부 합성 (ADR 0052 §X 정합) + README 매트릭스 자동 갱신 sync-readme-releases.mjs |

---

## §3. ADR 시계열 매트릭스 (90 → 96 신규 6건 + amend 3건 + Superseded 1건)

`_workspace/arch/adr/README.md` 인덱스 정합.

### 3-1. v3.8.0 사이클 신규 ADR 6건

| ADR | 상태 | 발행 일자 | 결정자 | 종류 | 핵심 결정 |
|-----|------|----------|--------|------|----------|
| **0091** | **Accepted** | 2026-05-20 | architect + market-data | NXT 통합 시장코드 resolver | **NXT 통합 시장코드 종목 단위 resolver** — KOSPI=UN/KOSDAQ=J market-level 분기 폐기 + is_kospi200/is_kosdaq150 allow-list + J fallback. ADR 0063 보강. `InstrumentMasterRefreshJob` NXT resolver 합류 + `KisRestCandleAdapter` market div resolver + frontend SymbolChart market div LOCK |
| **0092** | **Accepted** | 2026-05-20 | architect + strategy-engine | strategy-engine 모듈 분리 | **strategy-engine 라이브러리 모듈 분리** — DSL parser/evaluator 공유 (API 백테스트·screener HOLD stub 제거 + consumer 실시간 평가 단일 evaluator). 신규 8 JVM 모듈 (domain·pattern-engine·indicator-impl·**strategy-engine**·persistence-shared·api·batch·consumer). ADR 0054·0083·0088 패턴 1:1 재사용 |
| **0093** | **Proposed (Phase 3·4·5 머지 합류)** | 2026-05-20 | architect + chart-curator (당시) + backend-core + frontend | 차트 캔들 히스토리 limit | **차트 캔들 히스토리 limit + Lazy Backfill + 사용자 입력 봉수** — chart-curator spec 발행 + backend-core MAX_PAGES property + /candles limit cap 5000 + frontend CandleCountSelector UI + Phase 5 LWC 가드 3단 + 한국석유 dogfooding |
| **0094** | **Accepted (사용자 위임 결재, amend 2026-05-21)** | 2026-05-21 | 사용자 (orchestrator 자율 위임) + architect | **운영 단계 transition** | **개발 단계 종착 + 운영·점진 고도화 단계 진입** — sub-agent 8 → 7 reshuffle (해고 chart-curator·격상 qa Visual "품질 헌터"·유지 ai-engineer·거절 sre · backend-core P0 OPS 6건 인수) · 운영 헌법 §9·§10 신설 (SLO/SLA·on-call·DR·운영 ADR 양식 3 슬롯·7 hierarchy LOCK) · spec 2건 동반 발행 (`2026-05-21-chart-curator-handover.md` + `2026-05-21-qa-visual-uplift.md`) · ADR 0080·0087 amend + ADR 0085 supersede |
| **0095** | **Accepted** | 2026-05-21 | architect | P0-OPS-3 SLO/SLA | **P0-OPS-3 — SLO/SLA + alert routing matrix** — 5 SLO LOCK (S-1 시그널→주문 P95<5s · S-2 KIS WS>99% · S-3 account p95<500ms · S-4 KIS rate<1건/5분 · S-5 KillSwitch<1건/일) + severity 4단 routing (P4 INFO/P3 WARN/P2 ERROR/P1 CRITICAL — ADR 0084 정합) + Prometheus alert sketch 10건 + KillSwitch 자동 ON 검토 S-1·S-5 critical 한정 (ADR 0043 race-safe 정합) + Slack 단일 channel (ADR 0044 reuse) + 운영 ADR 양식 3 슬롯 첫 적용 사례 |
| **0096** | **Accepted** | 2026-05-21 | architect | P0-OPS-4 DR/backup | **P0-OPS-4 — DR/backup 절차** — PostgreSQL pg_dump daily 03:00 KST + 7일 retention (`_workspace/backups/postgres/daily/YYYY-MM-DD.dump`) + Redis snapshot 03:30 KST + 3일 retention + AOF 거부 + Kafka offset N/A · RPO 24h + RTO 30분 LOCK · 분기 DR drill + qa Visual E2E 자동 재현 · 스크립트 `scripts/backup/{pg-dump-daily,redis-snapshot}.sh` + 사용자 가이드 `docs/operations/backup-setup.md` · 운영 ADR 양식 두 번째 적용 사례 |

### 3-2. v3.8.0 사이클 ADR amend 3건

| ADR | 상태 | amend 사유 |
|-----|------|-----------|
| **0080** | **Accepted (amended by ADR 0094, 2026-05-21)** | 8 → 7 actor reshuffle (chart-curator 해고) + qa Visual 격상. opus 4.7 LOCK 유지 |
| **0085** | ~~**Superseded by ADR 0094 (2026-05-21)**~~ | chart-curator 신규 에이전트 영입 → 해고. 책임 이관 (LWC v4 → frontend / 캔들 SLA → market-data). 페르소나 "안정빌드"는 frontend·market-data 보수 lens로 흡수 보존. spec: `_workspace/specs/2026-05-21-chart-curator-handover.md` |
| **0087** | **Proposed (amended by ADR 0094)** | 7 actor 페르소나 매트릭스 (chart-curator 보수 흡수 → frontend·market-data + qa Visual "품질 헌터" 신규 + ai-engineer 균형 추가). 페르소나 5 매트릭스 LOCK |

### 3-3. v3.8.0 사이클 운영 헌법 진화 (architect 단독 영역)

| § | 상태 | 사유 |
|---|------|-----|
| **§9 (운영 단계 룰)** | **신설** | ADR 0094 §4-2 본문화. 6 항목 (9-1 SLO/SLA 5건 · 9-2 변경 관리 · 9-3 on-call rotation · 9-4 backup/DR · 9-5 신규 ADR 양식 3 슬롯 · 9-6 7 hierarchy LOCK) |
| **§9-1** | 신설 | SLO 5건 baseline + Redis hit ratio·DB pool 활용률 2건 P1 amend 후보. 측정 source = Prometheus query 1순위 (ADR 0095) |
| **§9-2** | 신설 | 변경 관리 — PR > 50 LOC 또는 자본 path → rollback plan 의무 + canary dev→staging→REAL 30분 dwell + frontend 핵심 화면 변경 시 qa Visual 자동 dispatch |
| **§9-3** | 신설 | on-call rotation — 1차 사용자 · 2차 backend-core · 3차 architect + 영역 sub-agent |
| **§9-4** | 신설 | backup/DR — ADR 0096 단일 권위. PostgreSQL daily + Redis snapshot + RPO 24h/RTO 30분 + 분기 DR drill |
| **§9-5** | 신설 | 신규 ADR 양식 3 슬롯 (SLO 영향 · rollback plan · 관측 매트릭스 + Visual 회귀 시나리오). ADR 0095·0096 첫·두 번째 적용 사례 |
| **§9-6** | 신설 | 7 hierarchy LOCK — chart-curator 해고 + qa Visual 격상 + ai-engineer 유지 + sre 영입 거절 |
| **§10 (운영 메모 재배치)** | 신설 | architect/sub-agent/orchestrator/사용자 책임 매트릭스 명시. qa는 §9-2·§9-4 추가 정독 의무 |

---

## §4. PR ↔ ADR ↔ 기능 매핑 매트릭스 (v3.8.0 사이클 핵심)

| 영역 | PR | ADR | 기능 | 매핑 |
|------|----|-----|------|-----|
| v3.7.1 patch | #583·#584·#586 | ADR 0088 baseline fix | persistence-shared JpaRepository 통합 scan + SettingsModal MOCK POST | v3.7.0 → v3.7.1 첫 운영 dogfooding evidence 흡수 |
| 캔들 stale 재발 hardening | #587·#588·#589·#590·#595 | ADR 0086 §14 amend | cursor 페이지네이션 복원 + chart-curator §함정 #8·#10 + 햇징 §14 amend | SK하이닉스 + 한국석유 dogfooding evidence — chart-curator 해고 직전 마지막 안정화 |
| KIS 토큰 통합 | #592·#593·#594·#775c87fe | — | T-1·2·3·4 cache prefix 통합 + 동적 TTL + 분산락 + Account cache 5s 단축 + EGW00123 self-heal + 빈 userId hotfix | 502 root cause fix + 자본 path 보호 |
| ADR 0091 NXT | (NXT PR series) | **0091 Accepted** | KOSPI=UN/KOSDAQ=J 폐기 + is_kospi200/is_kosdaq150 allow-list | NXT 통합 종목 단위 resolver — ADR 0063 보강 |
| ADR 0092 strategy-engine | (strategy/share-jvm-evaluator) | **0092 Accepted** | DSL parser·evaluator 공유 jar + HOLD stub 제거 + ConsumerStrategyLookup·ConsumerSignal test coverage | API 백테스트·screener + consumer 실시간 단일 evaluator LOCK |
| ADR 0093 chart limit | #609·(Phase 3·4·5) | **0093 Proposed** | MAX_PAGES property + /candles limit cap 5000 + CandleCountSelector UI + Phase 5 LWC 가드 3단 | 한국석유 dogfooding 한계 영구 차단 |
| autotrading hardening A·B·C·D | (4 PR 묶음) | ADR 0067·0068·0084 정합 | A T-ACCT 텍스트 5s · B T-MAN idempotencyKey · C OrderOutbox timeout + FOR UPDATE SKIP LOCKED · D Rate Limit Phase 2 마감 | 자본 path 추가 hardening |
| system event log P1·P2·P3 | (commit ade93d1·6a86e32·5ac7430·8deb37c·c8a8f0f + P2 + P3) | — (별도 ADR 후보) | Flyway V44 + SystemEvent enum + SystemEventLogger port + SystemEventJpaEntity + Repository + Kafka producer + 4·2 test + emit point 5건 + REST + STOMP fanout + /system Dashboard + NotificationCenter | 운영 가시화 영역 본격 발행 |
| account balance STOMP push | (backend + frontend 묶음) | — | account balance STOMP push (polling → push 전환) | 자본 시각화 즉시성 LOCK |
| WS health indicator + 끊김 toast | #368555ec·#ac5d9274·#644·#645 | — | KIS WS health indicator + 끊김 toast 영구 표시 + WS_DISCONNECTED·WS_RECONNECTED·WS_CONNECTED emit | 운영 가시화 + 사용자 dogfooding 거짓 끊김 표시 fix |
| 자본 path hotfix series | #4aba93d3·#603915fe·#ed5d62a6·#b6d17fbc·#9799760b·#640·#828e7a7c·#643 | ADR 0067·0068·0070 정합 | order_request source · KIS WS H0STCNI9 + AES-256-CBC · AccountQuery accountType 분리 · order FILLED qty/executedQty · markResolved viewed_at · KIS EGW00201 + SystemEvent · backfill toast 3건 · positions data source | T-O + T-MAN + 운영 dogfooding evidence 흡수 |
| **ADR 0094 운영 단계 transition** | #7ba7cda6·#22182b03·#647·#648 | **0094 Accepted (사용자 위임 결재)** | **8 → 7 reshuffle + KillSwitch race-safe (A+B+C — pending+grace+strike) + 운영 헌법 §9·§10 신설** | **본 baseline 사이클의 결정적 turning point** |
| **P0 OPS 6/6 LOCK** | #649·#650·(b89eed6b)·(49f1bc31)·(60045dc1)·(b1aab9e9) | **0095·0096 Accepted** | **P0-OPS-1·2 logback JSON + Actuator/Prometheus + Grafana · P0-OPS-3 ADR 0095 SLO/SLA · P0-OPS-4 ADR 0096 DR/backup · P0-OPS-5 runbook 5 시나리오 · P0-OPS-6 persistence monitoring + SLO 측정 source wiring + Grafana SLO dashboard** | **운영 baseline P0 6/6 LOCK** |
| qa Visual 격상 + 첫 임무 | #649 (qa baseline) · #656 (첫 실전 임무) | ADR 0094 §2-2-B | Playwright + 5 시나리오 baseline + 종목상세 현재가 결함 hunt 첫 임무 evidence | "품질 헌터" 페르소나 가동 |
| **Stage 2 Cleanup** | #655 | — | **ADR audit + 헌법 + SoT + 메모리 보고서 + CHANGELOG drift + backlog + qa.md 등록 + chart-curator archive + orphan decision audit** | v3.8.0 cut 전 토큰·메모리 정제 LOCK |
| **v3.8.0 cut 차단 hotfix 3건** | #659·#660·#661 + 9f935092 | — | KIS_TPS_EXCEEDED LIVE 종합 fix (200ms sleep + EGW00201 1초 backoff + priority=BACKFILL) + QA 첫 임무 P0 NXT 30% 휴리스틱 + stale 30분 검증 + consumer KIS inquire-daily-ccld `.publishOn(boundedElastic)` 2 어댑터 | v3.8.0 cut 차단 사항 모두 해소 |

---

## §5. 사용자 결정 누적 매트릭스 (v3.8.0 사이클 신규)

| 의제 | 사용자 결정 | 정합 ADR/헌법 |
|------|------------|---------------|
| **운영 단계 진입** | "이제 개발의 전반은 다 되어가기때문에 시스템 운영 단계 및 부분 고도화로 갈 준비를 해야해" | **ADR 0094 Accepted** + 운영 헌법 §9·§10 신설 |
| qa Visual "품질 헌터" 격상 | "큐에이도 지금 필요한데 그냥 단순 에이전트가 아닌 확실한 큐에이가 필요하거든. 스크린샷 등 하면서 테스트 시킬라했어" | ADR 0094 §2-2-B + qa.md 신규 발행 + spec `2026-05-21-qa-visual-uplift.md` |
| chart-curator 해고 결정 | "오케스트라 믿고 맡길께. 최고 훌륭한 팀원으로 다시 구성해줘" (자율 위임) | ADR 0094 amend — chart-curator 해고 + 책임 이관 spec `2026-05-21-chart-curator-handover.md` |
| sre 영입 거절 | "최고 훌륭한 팀원" → 기존 actor 강화 우선 + 운영 부담 ↑ 회피 | ADR 0094 §2-2 — sre 영입 거절 + P0 OPS 6건 backend-core 인수 |
| ai-engineer 유지 결정 | 사용자 명시 동의 없음 → 안전 default = 보존 | ADR 0094 §2-2 — ai-engineer 유지 (W4 진입 시 가동) |
| W4 진입 시점 | "W4 진입 안 함" 명시 유지 | T-AI·T-MCP 트랙 v3.8.0 진입 0 LOCK |
| Stage 2 종합 정리 | "v3.8.0 cut 전에 Phase C + qa 셋업 완료 + ★★★ ADR·헌법 정리를 한번 했으면 좋겠어. 지금 인덱스, 헌법 adr 많이 변화가 있었을 수도 있고 코드에만 있고 정리안된 것들을 찾아서 싹 정리해서 다시 토큰, 메모리 정제한다음 다시 깨끗한 마음으로 3.8.0 릴리즈부터는 토큰도 적절하게 읽어드릴 수 있을 수 있잖아? 그렇지?" | PR #655 Stage 2 Cleanup (architect 단독 11 정리 항목) |
| 분기 DR drill 의무 | qa Visual E2E 5 시나리오 자동 재현 + 사용자 결재 + RPO/RTO 실측 evidence | ADR 0096 §6-2 + 운영 헌법 §9-4 |
| 사용자 1회 작업 | `crontab -e` 4 라인 추가 (`docs/operations/backup-setup.md` §2) | ADR 0096 baseline 머지 후 사용자 의무 |

---

## §6. v3.7.0 → v3.8.0 누적 통계 매트릭스

| 지표 | v3.7.0 | v3.8.0 | 증감 |
|------|--------|--------|------|
| PR # 누적 | #575+ | #661+ (cut 직전) | +86+ |
| Patch 누적 (이 사이클) | 0 | 1 (v3.7.1) | +1 |
| Minor 누적 (전체) | 7 | 8 | +1 |
| ADR 수 | 90 | 96 | +6 |
| ADR 신규 (v3.8.0) | — | 6 (0091·0092·0093·0094·0095·0096) | +6 |
| ADR amend (v3.8.0) | — | 3 (0080·0085·0087) | +3 |
| ADR Superseded (v3.8.0) | — | 1 (0085 → 0094) | +1 |
| JVM 모듈 | 7 | **8** (+ strategy-engine ADR 0092) | +1 |
| sub-agent 수 | 8 | **7** (chart-curator 해고 + qa 격상 + ai-engineer 유지 + sre 거절) | -1 (재배치) |
| sub-agent 페르소나 | 2 | **5** (보수 흡수 + 진보 유지 + 균형 신규 + 방어 신규) | +3 |
| Flyway 버전 | V42 | V44+ | +2+ |
| Kafka 토픽 (활성) | 23+ | 25+ | +2+ |
| STOMP 토픽 | 8+ | 10+ | +2+ |
| Redis namespace | 다수 | + 3+ (unified prefix + accountType 분리 + system event dedup) | +3+ |
| REST endpoint | 약 80+ | 약 90+ | +10+ |
| Frontend 라우트 | 16+ | 17+ | +1+ (/system) |
| Domain port | 35+ | 38+ | +3+ |
| 운영 헌법 § | 8 (7 + §1-A-extended) | **10** (+ §9 6 항목 + §10) | +2 § |
| SLO 정의 | 0 | **5 baseline** + 2 P1 amend | +5 |
| Prometheus alert rule | 0 | **10** | +10 |
| Backup 자동화 | 0 | **2** | +2 |
| RPO/RTO 정의 | 0 | **RPO 24h + RTO 30분 LOCK** | +1 |
| Runbook 시나리오 | 0 | **5** | +5 |
| Visual QA 시나리오 | 0 | **5** (Playwright baseline) | +5 |
| W3 트랙 마감 | 9+ | 12+ (+ ADR 0091·0092·0093 + system event log P1·P2·P3) | +3+ |
| **운영 흐름 작동률** | 100% LOCK (persistence-shared P0-A 회복) | **100% LOCK 유지** + persistence monitoring | LOCK |
| AI 호출 (자동매매 본체) | 0 LOCK | **0 LOCK 유지** | 0 |
| GitHub Actions workflow | 0 + 3 헬퍼 | 0 + 3 헬퍼 (보존) | 0 |
| 외부 통합 (활성) | 6 | 6 (보존 — W4 진입 0) | 0 |
| dogfooding 결함 fix cycle (자본 path 직결 hotfix series) | 0 | 8+ (KIS WS AES + order entity source + accountType cache + order FILLED qty + markResolved viewed + KIS EGW00201 + backfill toast + positions data source) | +8+ |
| hotfix PR 누적 (v3.8.0 사이클) | 1 (#557) | 15+ (cut 차단 hotfix 3건 포함) | +14+ |
| sub-agent 페르소나 cycle | 7 (v3.7.0) | + 5+ (qa Visual 첫 임무 + market-data 보수 흡수 dispatch + frontend 보수 흡수 dispatch + backend-core 진보 P0 OPS 6건 인수 + ai-engineer 보존) | +5+ |
| 외부 코드 리뷰 흡수 누적 | 13 | 13 + Stage 2 11 항목 self-audit (사용자 LIVE dogfooding evidence 흡수) | LOCK |

---

## §7. v3.8.0 baseline 사이클 핵심 메시지

**운영 단계 진입 baseline (개발 단계 종착 + 7 actor reshuffle + qa Visual "품질 헌터" 격상 + chart-curator 해고 + sre 영입 거절 + ai-engineer 유지 + P0 OPS 6/6 LOCK + Stage 2 Cleanup + cut 차단 hotfix 3건)** — v3.7.0 baseline (시스템 점검 + Phase 보강 + 운영 헌법 §1-A-extended·§2 6단계) → v3.7.1 patch (persistence-shared JpaRepository scan 누락 hotfix) → v3.8.0 (ADR 0094 amend 사용자 위임 결재로 8→7 actor reshuffle + qa Visual 격상 + ADR 0085 supersede + 운영 헌법 §9·§10 신설 + P0 OPS 6건 마감 + ADR 0091·0092·0093 W3 보강 + Stage 2 Cleanup + v3.8.0 cut 차단 hotfix 3건). **운영 단계 진입** — fitness gap 기준이 "개발 단계" → "운영 단계"로 LOCK 전환 (SLO/SLA·on-call·DR/backup·runbook·persistence monitoring 모두 본문화). **W4(T-AI) 진입 0** — 사용자 명시 정합. ai-engineer는 유지(W4 진입 시 가동) + sre 영입 거절 (P0 OPS 6건 backend-core 인수). v3.8.0 = W3 자동매매 본체 안정화 LOCK + 7 sub-agent hierarchy + 8 JVM 모듈 매트릭스 + 페르소나 5 매트릭스 + 운영 헌법 §9·§10 + P0 OPS 6/6 LOCK + SLO 5 baseline + RPO 24h/RTO 30분 + 분기 DR drill 의무 + qa Visual E2E 자동 재현 종합 LOCK 사이클. **다음 v3.8.x patch 후보** = DR drill 분기 1회 evidence + P0 OPS 6건 dogfooding 누적 + ADR 0093 chart limit Phase 4·5 dogfooding. **다음 v3.9.0 minor 후보** = W4(T-AI·T-MCP) 진입 또는 W5(T-LEARN) 진입 결정 의제. AI 호출 0 LOCK 지속.
