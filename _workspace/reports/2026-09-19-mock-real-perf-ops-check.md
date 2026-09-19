# MOCK/REAL 대개편 성능·운영 실측 (2026-09-19 22:08 KST)

> 사용자 지시: "코드만 보면 안 되고 성능에 대한 이슈성 전반 체크하고 더 진행."
> 대개편 PR #1214~#1224가 실제 가동 시스템에 준 영향을 **측정**으로 확인.

## 1. 측정 환경

- 시각: 2026-09-19(토) 22:08 KST — **장 완전 마감**(시세 tick 0 상태).
- 가동: 전체 docker healthy(api·consumer·postgres·kafka·redis·prometheus·grafana 등).
- **주의**: api/consumer 컨테이너는 **약 2시간 전 빌드** — 대개편 PR(#1214~#1224, 최근 1시간 내 머지)은
  아직 가동 컨테이너에 **미반영**. 아래 baseline은 "대개편 직전" 상태 측정치다.

## 2. 실측 결과 (현재 가동 = 대개편 직전 baseline)

| 지표 | 측정값 | 판정 |
|---|---|---|
| api ERROR (30m) | 0건 | ✅ |
| consumer ERROR (30m) | 0건 | ✅ |
| consumer WARN (30m) | WS 재연결 2·hts-id 미설정 2 (소량) | ⚠ 검토 |
| HikariCP active/pending | 0 / 0 | ✅ DB 커넥션 병목 없음 |
| consumer 힙 사용 | 223 MB | ✅ 여유 |
| consumer GC pause | 총 3.03s / 31h, max 23ms | ✅ GC 압박 없음 |
| Prometheus 타깃 | api·consumer·batch docker 전부 UP | ✅ |

## 3. WARN 2건 분석

### (a) WS heartbeat 무신호 → 재연결 (정상)
- 패턴: `heartbeat 무신호 11s(timeout=10s) → dispose → 재연결 스케줄 attempt=1 delay=0ms → 0.2s 내 연결 성공`.
- 빈도: 2~6분 간격, 매번 **즉시 복구**(attempt=1). 재연결 루프·backoff 폭주 **없음**.
- 원인: 토요일 야간 = 장 마감으로 tick 0 → heartbeat(tick 기반) 자연 무신호 → idle 재연결 사이클.
- **판정: 정상.** 대개편과 무관(현 컨테이너는 대개편 전 코드). approval_key 발급도 매 재연결 성공.

### (b) MAGICJAR_KIS_WS_HTS_ID 미설정 → 체결통보 subscribe skip
- 이미 알려진 항목(GAP-1 인접). MOCK/REAL 무관하게 체결통보 WS(H0STCNI0/9)가 안 뜸.
- 현재는 REST sync로 체결 반영되므로 자본 무결성 영향 없음. 실시간 체결 알림만 부재.
- **판정: 기존 미설정 이슈.** 별도 결정 필요(hts-id 설정 or GAP-1 세션 도입).

## 4. 대개편 변경의 성능 특성 (정적 분석 — 재배포 후 재측정 필요)

| 변경 | hot path | 부하 특성 |
|---|---|---|
| ConsumerActiveTradingModeAdapter | 주문마다(TradingModeGate) | **2s TTL 캐시** → burst에도 2초당 1 DB read |
| ActiveAccountTypeResolver | 체결 sync마다 | 내부적으로 위 2s TTL 캐시 재사용 → 추가 DB 0 |
| resolveWsCredentialOwnerId | WS 재연결/구독 시 | env 설정 있으면 DB read 0(현 환경). 미설정 시만 findAllByAccountType 1회 |

- 모두 **저빈도 + 캐시**. baseline(active=0, GC 3s/31h) 대비 무시할 부하로 예상.
- 단 **실증은 재배포 후 동일 지표 재측정으로 완결**해야 함(추측 금지 원칙).

## 5. 재배포 후 재측정 (2026-09-19 22:13 — 대개편 반영)

최신 main(#1214~#1224) 재배포 후 동일 지표 재측정:

| 지표 | baseline(전) | 재배포 후 | 판정 |
|---|---|---|---|
| V102 마이그레이션 | — | 적용 성공(success=t) | ✅ |
| system_config.active_trading_mode | — | 'MOCK'(안전 기본값 백필) | ✅ |
| 기동 ERROR (api/consumer) | 0 | 0 | ✅ 기동 무결성 |
| HikariCP active/pending | 0/0 | 0/0 | ✅ 무회귀 |
| consumer 힙 | 223 MB | 287 MB(부팅 직후) | ✅ 동급 |
| WS 연결 | 성공 | 성공(approval 발급) | ✅ 자동연결 경로 정상 |
| GET /api/system-config activeTradingMode | — | 'MOCK' 노출 | ✅ 새 API 실동작 |

**한계(사용자 확인)**: 주말 장 마감 → 실 시세/주문 부하 미측정. 본 재측정은 **기동 무결성 + 정적 성능**까지.
실 부하(주문 TPS·시세 tick 처리·체결 지연)는 **평일 장중 별도 재측정** 필요.

**결론**: 대개편 코드는 기동·정적 성능 무회귀 실증. 새 API/WS 자동연결 실동작 확인. 실주문 0.
