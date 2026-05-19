---
id: T-20260519-1815-worktree-agent-ab2b6ace55358a88a
agent: architect
model: opus
branch: agent/architect/p1-4-bucket4j-spec-amend
status: DONE
startedAt: 2026-05-19T18:15:54.434+09:00
completedAt: 2026-05-19T18:45:00.000+09:00
pr: TBD
parentPhase: P1-4 ADR 0037 amend
---

# 목표
P1-4 ADR 0037 Bucket4j spec amend

## 결과물 체크리스트
- [x] ADR 0037 amend 본문 — `_workspace/arch/adr/0037-kis-rate-limit-enforcement.md` §1-1 spec drift evidence + §2-1·§2-2 amend + §5 잔여 의제 Phase 1·2·3 + §6 변경 이력 신설
- [x] KisRateLimiterPort domain port spec — spec §2-2 Kotlin port 본문 LOCK (`acquire(priority, timeoutMillis)` + `onTpsExceeded()` + `onSuccess()` + `KisCallPriority` enum + 2 Exception)
- [x] Bucket4j-Redis vs Resilience4j 선택 — 옵션 A (Bucket4j-Redis 단일 bucket) 채택 · Resilience4j 거부 (분산 미지원 share 분할 risk) · spec §2-1 + ADR §2-1 LOCK
- [x] EGW00201/HTTP 429 backoff 매트릭스 — spec §2-4 + ADR §2-2 정밀화 (1초 fixed backoff + 1회 재시도 + Circuit Breaker OPEN 5분/50%)
- [x] spec drift 10 req/s 정정 — spec §3 정정 매트릭스 (코드 주석 15+ 위치 + sleep(50ms)→sleep(100ms) 또는 acquire 도입 · architect 본 PR 운영 코드 0 touch · market-data Phase 2 dispatch backlog)

## 의존성
- ADR 0088 Proposed (persistence-shared 패턴) — 본 ADR adapter 모듈 위치 옵션 b 1:1 재사용 cross-ref
- ADR 0085·0087 (chart-curator·backend-core 페르소나) — cross-review 1차 lens 매트릭스 정합

## 마지막 활동
2026-05-19T18:15:54.446+09:00 — 작업 시작
2026-05-19T18:45:00.000+09:00 — 5 deliverable 모두 완료 · PR 생성 진행

## 비고

본 PR은 docs only — Kotlin/TS/SQL 0 변경. Phase 1 backend-core dispatch는 사용자 결재 후 별도 사이클.
