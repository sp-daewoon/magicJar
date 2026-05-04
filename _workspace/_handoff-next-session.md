# 다음 세션 진입 시 — Handoff (작성: 2026-05-04 R2-A1 직후)

> 새 세션이 첫 진입 시 본 문서를 먼저 읽어 컨텍스트를 빠르게 잡으세요.

## 1. 현재 상태 (2026-05-04 R2-A1 종료 직후)

### Git
- `main` HEAD: `a2f4a75` (fix(ops): build-and-deploy.sh dev override hotfix)
- 직전 머지 3건:
  - `5c82780` docs(track2): R2-A plan
  - `9c7193b` feat(market-data): T2 R2-A1 KIS 시세 WS 체결가 (H0UNCNT0)
  - `a2f4a75` fix(ops): build-and-deploy.sh dev override hotfix
- 보존 tag: `v2.0.0` · 보존 브랜치: `release/v2`

### 환경 (R2-A1 검증 완료)
- 9 컨테이너 healthy, profile = `dev,real-kis`, MOCKTICK=false
- KIS WS 연결 OK (`ws://ops.koreainvestment.com:21000`), approval_key 발급 정상 (TTL 23h)
- Subscribe: 체결통보 H0STCNI0/9 + 체결가 H0UNCNT0 × 5종목 (005930·000660·035720·005380·051910)
- Kafka `market.tick.v1` ≈ 14 tick/sec 흐름
- M1 분봉 mock 잔재 32,600행 cleanup 완료 (D1 일봉은 보존)
- redis `quote:last:*` flush 완료

### 검증 완료된 흐름 (변동 없음)
- 종목 상세 005930 메타 정상
- 헤더 검색 + LRU 20 + Enter 진입
- KOSPI·KOSDAQ 지수 카드 (REST 1분 refetch)
- 차트 cursor 페이징

## 2. 다음 사이클 우선순위

### B·C·E·D 완료 (2026-05-04) — Quote 흐름 + Backfill 안정화

**B (PR #137)**: Redis QuoteCache writer 연결 + 동적 구독 명령 토픽. 모든 종목 진입 시 1~6초 내 LIVE 표시.

**C (PR #138)**: R2-A3 잔여 — `MarketSessionController` + `TickFallbackEmitter` + `SubscriptionManager` LRU + `useMarketSession`.

**E (PR #139)**: `useSymbolQuote` STOMP topic↔code mismatch guard — wrong code 가격 표시 사고 차단.

**D (PR #N — 본 사이클)**: 동적 일봉 backfill — `MarketCandleBackfillCommandEvent` + api `Publisher`(QuoteController + DailyCandleBackfillScheduler 16:30 KST) + batch `Listener`(KisRestPort fetch + DB saveAll, 멱등 dedup 60s + DB max(openTime) ≥ 어제 skip). 라이브 검증: 한국석유 004090 진입 → 100봉 saved (2025-12-04 ~ 2026-05-04). 알려진 한계: KIS REST 100봉 limit — 1년 full backfill은 페이징 후속.

### P0 — R2-A2 호가 + 지수 (8 task, T10~T17)

- **H0UNASP0**(호가) + H0UPCNT0(KOSPI) + H0UPANC0(지수 예상) + market.orderbook.v1 + market.index.v1 + STOMP 신규 토픽
- frontend: Orderbook 컴포넌트 + Dashboard 지수 실시간 STOMP 구독
- 영역: market-data (sonnet) + frontend (sonnet)
- 사이즈: 1~1.5일
- plan: `docs/superpowers/plans/2026-05-04-track2-r2-quote-ws.md` §4 T10~T17

### P1 — 별도 점검 후보

3. **frontend 호가창·체결·외국인 UI** (R2-A2 통합)
4. **005930 D1 max=226,000 outlier 점검** (KIS REST 일봉 백필 의심)
5. **차트 무한 스크롤 사용자 검증** (PR #130 페이징)
6. **setup wizard** (첫 진입 키 미등록 detect)
7. **SubscriptionEvictedEvent → STOMP `/topic/notifications` fanout** (LRU eviction 시 사용자 toast — 현재 in-process publish만)

### P1 — 별도 점검 후보
3. **frontend 호가창·체결·외국인 UI** (이전 세션 P0-1, R2-A2와 일부 겹침 — 통합 권장)
4. **005930 D1 max=226,000 outlier 점검** — KIS REST 일봉 백필 시 잘못 들어왔을 가능성. 사용자가 차트에서 일봉 이상치 보일 시 hotfix
5. **차트 무한 스크롤 사용자 검증** (PR #130 페이징)
6. **setup wizard** (첫 진입 키 미등록 detect)

## 3. 미완·알려진 한계

| 항목 | 사유 | 대응 |
|---|---|---|
| 호가·지수 실시간 | R2-A1은 체결가만 | R2-A2 |
| ~~TickFallbackEmitter~~ | ~~미구현~~ | ✅ R2-A3 C 머지 완료 |
| ~~18종목 LRU~~ | ~~take(18) 단순 절단~~ | ✅ R2-A3 SubscriptionManager 머지 |
| Dashboard 지수 카드 1분 refetch | STOMP 미구독 | R2-A2 |
| 005930 D1 max 226,000 outlier | KIS REST 일봉 백필 의심 | 별도 hotfix |
| LRU eviction toast | SubscriptionEvictedEvent in-process만 | 후속 (P1) |

## 4. 사용자 액션 필요 (외부 자원)

- **없음** — 키 정상

## 5. 정책 재확인 (memory)

| 정책 | 메모리 |
|---|---|
| Mock 데이터 사용 금지 | `feedback_no_mock_data.md` |
| 데이터 조회 = 실전 키 / 주문 = 모의·실전 분리 | 정책 정합 |
| 자율 실행 (사용자 확인 = 아키·외부자원·블로킹상충) | `feedback_autonomy.md` |
| 존댓말 의무 | `feedback_honorific.md` |
| 개발일지 매일 (`/devlog`) | `feedback_devlog_html.md` |
| QA on-demand (ADR 0016 7개 트리거) | `feedback_qa_on_demand.md` |
| 시크릿 평문 노출 시 재발급 + 응답에 재출력 금지 | `feedback_secret_handling.md` |
| Worktree 정리 의무 | `feedback_worktree_cleanup.md` |

## 6. 다음 세션 첫 명령 권장

```
"B(quote hotfix) 머지 후 C(R2-A3 Fallback 통합) 진입"
```

또는

```
"R2-A2 진입 — 호가 + KOSPI/KOSDAQ 지수 실시간"
```

## 7. 운영 명령 변경 (a2f4a75)

`scripts/build-and-deploy.sh`가 default로 `--env-file .env` + `docker-compose.dev.yml` override 자동 포함.
- 기본: `scripts/build-and-deploy.sh consumer-app` → `dev,real-kis`로 부팅
- mock 의도 시: `scripts/build-and-deploy.sh --mock`

## 8. 참조 문서

- 마스터 아키텍처: `docs/architecture/master-architecture.md`
- T2 R2-A plan (본 사이클): `docs/superpowers/plans/2026-05-04-track2-r2-quote-ws.md`
- T2 R1+R2 통합 plan: `docs/superpowers/plans/2026-05-02-track2-market-session-and-data-mgmt.md`
- ADR 0033 (3-tier fallback) · 0035 (KIS HTTP 정밀화 + 시세 trId 표 Appendix A)
- 캔들 정책 v2: `docs/architecture/candle-data-policy-v2.md`
- KIS API 정리: `docs/operations/kis-api-reference.md`
- 초기 설치: `docs/operations/initial-setup.md`
