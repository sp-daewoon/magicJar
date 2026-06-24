# 구현 plan — autopilot 일일 universe 자동 재생성 (ADR 0131)

- 작성: 2026-06-24, architect
- SoT: ADR 0131 + spec `2026-06-24-autopilot-daily-universe.md`
- 배포: orchestrator (게이트 default OFF 발행 → 검증 후 사용자/orchestrator 명시 on)
- 코드 0 (본 문서는 설계 plan). 구현은 sub-agent dispatch.

---

## 1. 라운드·에이전트별 task

### R1 — strategy-engine (1순위, 단독)

> 발굴 scorecard read → deduped TOP N 합성 → owner=AUTOPILOT codes UPDATE + 08:35 worker.

- [ ] `AutopilotUniverseRefreshService`(api/service/autopilot 신규)
  - `refresh(snapshotDate)` — `DiscoveryScorecardJpaRepository.findRowsInRange(today, today, null)` read → code별 `max(score)` dedup → score DESC·code ASC 정렬 → `take(size.coerceIn(1,40))` → owner=AUTOPILOT codes UPDATE(트랜잭션).
  - `RefreshResult`(status DISABLED|NO_SNAPSHOT|EMPTY_TOPN|UPDATED · universeSize · strategiesUpdated · droppedCodes · heldLotsRemaining).
  - fail-safe: enabled=false→DISABLED no-op · snapshot 0건→NO_SNAPSHOT skip+WARN · 빈 TOP N→EMPTY_TOPN skip+WARN.
  - 보유 lot 잔존 집계: 직전 universe(owner codes read) ∖ 신규 universe 종목 중 `strategy_position.quantity>0` 카운트 → SystemEvent(INFO).
- [ ] `StrategyJpaRepository.updateAutopilotUniverse(codesJson)` — `@Modifying @Query` JPQL UPDATE (owner='AUTOPILOT' AND status IN ('ACTIVE','BUY_BLOCKED')). **헌법 §6: abstract fun(body 0)**. codesJson = ObjectMapper.writeValueAsString(List<String>) (StrategyJpaAdapter codes 직렬화 패턴 1:1).
- [ ] `AutopilotUniverseRefreshWorker`(api/scheduler 신규) — `@Scheduled(cron="${magicjar.autopilot.universe-refresh.cron:0 35 8 * * *}", zone="Asia/Seoul")` + 거래일 게이트(`DiscoveryScorecardScheduler.isBusinessDay` 1:1) + 1일 1회 멱등(KST AtomicReference) + SystemEvent.
- [ ] ENV/yml 3건: `magicjar.autopilot.universe-refresh.{enabled:false, cron:0 35 8 * * *, size:16}`. `.env.example`·application yml 추가.
- [ ] **Flyway 0** (codes 기존 컬럼 UPDATE — 신규 마이그레이션 발행 금지).

### R2 — strategy-engine + qa (R1 후)

> 테스트 + MOCK 라이브 검증 (단위테스트≠라이브 — feedback_verification_strict).

- [ ] strategy-engine 단위 테스트:
  - dedup 산식(여러 프리셋 동일 종목 max score·정렬·TOP N take)
  - owner 격리(owner=AUTOPILOT만 codes 변경·USER/SYSTEM 무변경 단언)
  - fail-safe 3종(DISABLED·NO_SNAPSHOT·EMPTY_TOPN — codes 갱신 skip)
  - 멱등(같은 날 재실행 부작용 0)
  - 거래일 게이트(주말/휴장 skip)
- [ ] qa MOCK 라이브 검증(게이트 on):
  - 08:35 worker 발사 로그 + deduped TOP N 산출 vs scorecard today snapshot 대조
  - DB: owner=AUTOPILOT codes 갱신 확인 + **USER/SYSTEM codes 무변경**
  - consumer `ActiveStrategyCodeProvider` 가 갱신 codes read → 새 종목 BUY 평가·WS 구독(STRATEGY owner) universe 진입 확인
  - `ActiveStrategyIntradayWarmupRunner`(09:01) 새 종목 캔들 backfill 확인
  - **universe 밖 보유 lot 강제 청산 0**(hold-until-exit) + SystemEvent(held-lots) 확인
  - snapshot 0건 시 codes 갱신 skip(fail-safe) 확인

### R-aux — frontend (선택, R1 후)

- [ ] autopilot status 화면에 "오늘 universe(N종목) + 회전 종목(dropped) + held-lots(잔존)" 노출 — 관측 가시화. 후속 트랙(필수 아님).

---

## 2. 의존 그래프

```
R1 (strategy-engine 단독) → R2 (테스트 strategy ∥ 라이브 qa) → (선택) R-aux (frontend)
```

- R1 의존 0: scorecard read(영속됨)·strategy.codes write(기존 컬럼)·market_calendar(기존). 외부 의존 0.
- 병렬 불가 구간: R2 라이브 검증은 R1 머지·MOCK 배포 후. R1 단일 영역(api) add-only.

---

## 3. 검증 게이트 (PR 머지 전)

- `:api:check` + `:domain :strategy-engine :consumer` GREEN (codes UPDATE @Query 부팅 검증 — 헌법 §6 No property 부재).
- owner 격리 테스트 GREEN(USER/SYSTEM codes 무변경 단언).
- fail-safe 3종 테스트 GREEN.

---

## 4. 게이트 on 절차 (배포 — orchestrator 영역)

1. R1·R2 머지 + MOCK 배포(게이트 default OFF → 행동 변화 0 확인).
2. qa MOCK 라이브 검증 evidence(§R2) 통과.
3. 사용자/orchestrator 명시 결재 → `magicjar.autopilot.universe-refresh.enabled=true`.
4. 다음 영업일 08:35 첫 자동 refresh 라이브 관측(SystemEvent·codes·consumer read).
5. KIS 진실원 크로스체크: autopilot 일일 손익 vs KIS 잔고(메모리 feedback_kis_ground_truth_crosscheck).

---

## 5. CHANGELOG 슬롯 (사전 발행 — 본 spec PR)

- `<!-- adr-0131-slot-architect -->`(본 PR row)
- `<!-- adr-0131-slot-strategy-r1 -->`(R1 strategy)
- `<!-- adr-0131-slot-r2 -->`(R2 qa)
- `<!-- adr-0131-slot-end -->`

sub-agent 는 본인 row(`(작성: <agent>)` TBD)만 in-place edit.

---

## 6. 롤백

게이트 `magicjar.autopilot.universe-refresh.enabled=false` 1줄 → worker no-op = v3.14.0 거동 byte-동일. codes 마지막 값 잔존(무해). 스키마 변경 0(down SQL 불요).
