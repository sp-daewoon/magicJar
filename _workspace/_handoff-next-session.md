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

### P0 — Quote hotfix (B → C 순) — 사용자 보고 "종목 검색 시 가격 미반영"

**진단 (2026-05-04)**: R2-A1까지의 KIS WS 흐름은 살아있지만 **(R-A) Redis QuoteCache writer 미연결** + **(R-B) 동적 구독 부재**라 사용자가 검색→상세 진입 시 가격이 LAST_DAY_CLOSE(stale) 또는 404로 떨어진다.

1. **B — Quote hotfix (R-A + R-B 통합, 본 사이클 진행 중)**
   - R-A: Kafka `market.tick.v1` 컨슈머 신규 → `quoteCache.putLatest` (consumer 모듈)
   - R-B: `market.subscription.command.v1` 명령 토픽 신설 + api `/quote` 호출 시 ensure subscribe 발행 + consumer dedup add
   - 영역: 메인 세션 직접 (market-data 모자) — sonnet
   - 사이즈: 2~3시간
   - 효과: 모든 종목 진입 시 1~2초 내 LIVE 가격 표시. 18 LRU·Fallback은 C에서 마감.

2. **C — R2-A3 Fallback 통합 (6 task, T18~T23) — B 직후**
   - TickFallbackEmitter + MarketSessionResolver wiring + 18종목 LRU SubscriptionManager + frontend stale 배지
   - **B에서 R-A·R-B 흡수했으니 C는 LRU·Fallback·UI 잔여만**
   - 영역: backend-core (sonnet) — opus escalation 후보
   - 사이즈: 0.5~1일

3. **R2-A2 — 호가 + 지수 (8 task, T10~T17)** — C 이후
   - H0UNASP0(호가) + H0UPCNT0(KOSPI) + H0UPANC0(지수 예상) + market.orderbook.v1 + market.index.v1 + STOMP 신규 토픽 + frontend Orderbook 컴포넌트 + Dashboard 지수 실시간
   - 영역: market-data (sonnet) + frontend (sonnet)
   - 사이즈: 1~1.5일

### P1 — 별도 점검 후보
3. **frontend 호가창·체결·외국인 UI** (이전 세션 P0-1, R2-A2와 일부 겹침 — 통합 권장)
4. **005930 D1 max=226,000 outlier 점검** — KIS REST 일봉 백필 시 잘못 들어왔을 가능성. 사용자가 차트에서 일봉 이상치 보일 시 hotfix
5. **차트 무한 스크롤 사용자 검증** (PR #130 페이징)
6. **setup wizard** (첫 진입 키 미등록 detect)

## 3. 미완·알려진 한계

| 항목 | 사유 | 대응 |
|---|---|---|
| 호가·지수 실시간 | R2-A1은 체결가만 | R2-A2 |
| TickFallbackEmitter | 미구현 (장외·끊김 시 stale tick 0) | R2-A3 |
| 18종목 LRU | R2-A1엔 take(18) 단순 절단 | R2-A3 |
| Dashboard 지수 카드 1분 refetch | STOMP 미구독 | R2-A2 |
| 005930 D1 max 226,000 outlier | KIS REST 일봉 백필 의심 | 별도 hotfix |

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
