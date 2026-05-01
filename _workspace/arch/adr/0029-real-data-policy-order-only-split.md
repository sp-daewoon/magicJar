# ADR 0029: 데이터는 실 KIS·외부 API 베이스 — Dev/Prd 분리는 OrderSender만

- 상태: Accepted
- 작성일: 2026-05-01
- 결정자: architect (사용자 직접 명시)
- 관계: ADR 0006(이중 게이트 mode), ADR 0028(v1.0.1 베이스라인)

## 맥락

v1.0.1 시점까지의 흐름은 데모 가시성 우선이라 다음 mock 발행기를 main에 두고 운영했다:

| Mock | 위치 | 동작 |
|------|------|------|
| MockTickGenerator | consumer-app | 1초 5종목 가짜 시세 publish |
| MockNewsGenerator | consumer-app | 30초 1건 가짜 감성 분석 publish |
| candle V7·V8 시드 | api Flyway | 005930·000660 일봉/분봉 합성 |

사용자 의도는 다음:
> "거의 목업으로 하는 거 같은데 실제 데이터 받아서 쓰는 건 안 되는지? 실제 KIS 사용하고 dev/prd 분리는 **주문만** 다르면 된다. 주문만 분리하고 데이터는 다 실 데이터 베이스로."

→ 데이터(시세·뉴스·캔들·공시·종목마스터)는 모두 실 KIS·외부 API에서. **유일한 dev/prd 차이는 OrderSender의 주문 대상**.

## 검토한 대안

| 대안 | 데이터 | 주문 | 적합성 |
|------|--------|------|------|
| A. dev=mock·prd=real 양쪽 분리 | dev에선 mock 시세, prd에선 실 시세 | dev=모의, prd=실 | dev에서 백테스트·매매 검증이 prd 데이터와 다르므로 거짓 안전 |
| **B. 데이터는 실 통일·OrderSender만 분리** | 모두 실 KIS·외부 API | dev=KIS 모의계좌, prd=KIS 실계좌 | 사용자 의도 정합 + 매매 일지·백테스트 연속성 |
| C. 전체 mock·전체 real 분리 | 전 환경에 mock 또는 real 둘 중 하나 | 동일 | 운영자 부담 큼, dev에서 안전 검증 어려움 |

## 결정

**옵션 B 채택.**

### 1. 데이터 정책 (모든 환경 동일)
- **시세 (REST)**: 실 KIS REST API — `RealKisRestAdapter` 이미 구현됨 (PR #32)
- **시세 (실시간 WS)**: 실 KIS WebSocket — `RealKisWebSocketAdapter` 신규 (다음 PR)
- **캔들**: 실 KIS REST + DailyCandleBackfillJob (이미 구현됨)
- **뉴스 본문**: pyworker 네이버 RSS 크롤 (이미 구현됨)
- **공시**: pyworker OPENDART API (이미 구현됨)
- **종목 마스터**: 실 KIS 종목마스터 → 일 1회 동기화 (다음 PR)
- **AI 감성 분석**: **사용 계획 미확정 (사용자 보류 2026-05-01)** — Mock·실 양쪽 모두 OFF 유지

### 2. Mock 발행기 정책
- 기본값 **OFF** (`enabled=false`)
- 키 부재·offline 데모 환경에서만 명시 ON (`MAGICJAR_MARKET_MOCKTICK_ENABLED=true`)
- 코드는 그대로 보존 (offline dev 가치 유지) — 단 yml 기본값만 OFF

### 3. OrderSender 분리 (이미 ADR 0006 이중 게이트로 구현됨)
- **dev/staging**: `MAGICJAR_MODE=MOCK` + `MAGICJAR_ALLOW_REAL=false` → KIS 모의계좌 주문
- **prd**: `MAGICJAR_MODE=REAL` + `MAGICJAR_ALLOW_REAL=true` 둘 다 명시해야 실계좌 주문
- 둘 중 하나라도 false면 paper(no-op)로 fallback
- 매매일지·백테스트는 mode와 무관하게 실 데이터 기반으로 영구 기록

### 4. v2.0.0 베이스라인 게이트 갱신 (ADR 0028 보강)
v2.0.0 컷 조건: 다음 4개 충족
1. KIS 모의 키 SET + 실 KIS REST·WS 시세 수신 검증
2. RealKisWebSocketAdapter 1종목 1전략 24h 가동 (이미 ADR 0028에 명시)
3. **본 ADR 정책 정합** — mock 기본 OFF + OrderSender 분리만으로 검증 통과
4. 매매일지에 24h 동안 기록된 실 데이터 기반 entry 존재

## 비-결정

- **dev에서 mock 시세 사용 옵션**: 본 ADR 정책상 mock은 키 부재 시 비상용. dev 표준은 실 KIS 모의서버 시세
- **AI LLM 활성화**: 별도 ADR 후보. 사용 계획 확정 시 재검토
- **데이터 보존 정책**: ADR 0030 후보 — TimescaleDB·티어드·watchlist 화이트리스트

## 결과

- ✅ MVP 데모 가시성 → "실 데이터 운영 + 안전한 주문 분리" 단계 진입
- ✅ 매매일지·백테스트가 실 데이터 베이스로 → prd 진입 시 검증 연속성
- ✅ Mock 발행기는 보존 (코드 삭제 안 함) — 향후 offline 데모·CI에서 재활용
- ⚠ AI 감성 분석은 명시 보류 → 카드 빈 상태 유지 (다음 결정까지)

## 운영 즉시 적용

```bash
# .env
MAGICJAR_MARKET_MOCKTICK_ENABLED=false
MAGICJAR_MARKET_MOCKNEWS_ENABLED=false
KIS_MOCK_APP_KEY=...
KIS_MOCK_APP_SECRET=...
KIS_ACCOUNT_CANO=...
NAVER_NEWS_RSS_URL=...
OPENDART_API_KEY=...
# ANTHROPIC_API_KEY=  ← 보류
```

```bash
./scripts/build-and-deploy.sh
# - 캔들 endpoint → 실 KIS REST 응답
# - 뉴스 카드 → "AI 분석 뉴스 없음" (정상 — 사용자 보류 결정)
# - 실시간 시세 → KIS WebSocket 어댑터 도입 시점부터 흐름 (다음 PR)
```

## 참고

- 사용자 보고: "지금 거의 목업으로 하는 것 같은데 실제 데이터 받아서 쓰는 건 안 되는지" (2026-05-01)
- 사용자 결정: "B인데 LLM 감성분석은 제외, 사용 계획 미확정" (2026-05-01)
