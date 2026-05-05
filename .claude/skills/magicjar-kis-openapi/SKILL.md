---
name: magicjar-kis-openapi
description: 한국투자증권 KIS OpenAPI(REST + WebSocket) 클라이언트를 구현·확장·수정·보완·재구성한다. 토큰 발급/갱신, 시세 조회, 실시간 체결·호가 구독, 주문 요청, DART/뉴스 수집까지 market-data 에이전트의 모든 외부 연동 작업이 이 스킬의 범위. "KIS 붙여줘", "시세 연동", "실시간 체결 구독", "API 엔드포인트 추가"도 포함.
---

# magicjar-kis-openapi — KIS OpenAPI 통합

한국투자증권 KIS OpenAPI는 magicJar가 **유일하게 의존하는 증권사 외부 시스템**이다. 실패하면 전체 서비스가 멈추므로, 인증·레이트리밋·재시도를 철저히 감싼다.

## 필수 설정

- 발급: https://apiportal.koreainvestment.com (본인 확인·모의투자 신청 필요)
- 환경변수
  - `KIS_APP_KEY` / `KIS_APP_SECRET` — 실계좌용
  - `KIS_MOCK_APP_KEY` / `KIS_MOCK_APP_SECRET` — 모의투자용
  - `KIS_ACCOUNT_CANO` / `KIS_ACCOUNT_PRDT` — 계좌번호·상품코드
- 베이스 URL
  - 실계좌: `https://openapi.koreainvestment.com:9443`
  - 모의계좌: `https://openapivts.koreainvestment.com:29443`

## 인증 흐름

1. `POST /oauth2/tokenP` → 액세스 토큰 발급 (유효 24시간, 앱키당 동시 1개 권장)
2. 요청마다 `Authorization: Bearer {token}`, `appkey`, `appsecret`, `tr_id` 헤더
3. 주문·계좌 조회는 `hashkey` 추가: `POST /uapi/hashkey` 로 해시 생성

**토큰 관리 규칙**
- Redis에 `kis:token:{mode}` 키로 캐시, TTL = 발급 만료 1시간 전
- 만료 임박 시 백그라운드 갱신, 실패 시 기존 토큰으로 폴백
- 401 응답 받으면 즉시 강제 재발급 1회

## 주요 엔드포인트(초기 MVP)

| 기능 | 경로 | 비고 |
|------|------|------|
| 현재가 조회 | `/uapi/domestic-stock/v1/quotations/inquire-price` | `tr_id=FHKST01010100` |
| 일봉/분봉 | `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` | 최대 100건 |
| 체결 내역 | `/uapi/domestic-stock/v1/quotations/inquire-ccnl` | |
| 잔고 조회 | `/uapi/domestic-stock/v1/trading/inquire-balance` | `tr_id=TTTC8434R`(실), `VTTC8434R`(모의) |
| 주문(현금매수) | `/uapi/domestic-stock/v1/trading/order-cash` | `tr_id=TTTC0802U`/`VTTC0802U` |

## WebSocket 실시간

- Base: `ws://ops.koreainvestment.com:21000` (실), `ws://ops.koreainvestment.com:31000` (모의)
- 접속키 발급: `POST /oauth2/Approval` (REST 호출 → `approval_key`)
- 구독 예: 실시간 체결가 `H0STCNT0`, 호가 `H0STASP0`
- 동시 구독 상한: **약 40종목** (공식 명시, 프로덕션에선 30개로 버퍼)

## 레이트리밋 방어

- REST: 공식 20회/초 → Bucket4j로 **15회/초**로 제한
- 주문 계열은 초당 5회로 별도 버킷
- 초과 시 큐잉 + 최대 2초 대기, 초과 지속 시 실패 반환 + `_workspace/market-data/rate-limit.log`에 기록

## 재시도 전략 (Resilience4j)

- 네트워크/5xx: 지수 백오프 (200ms → 400ms → 800ms, 최대 3회)
- 4xx 중 401만 토큰 재발급 후 1회 재시도, 나머지 4xx는 즉시 실패
- CircuitBreaker: 5분 내 10회 이상 실패 시 30초 open

## DART/뉴스 보조 수집

- DART Open API: `https://opendart.fss.or.kr/api/list.json` — API 키 `DART_API_KEY`
- 네이버 금융 뉴스: 공식 RSS 우선, 없으면 HTML 파싱(User-Agent 명시, 수집 주기 ≥ 5분)
- 수집 결과는 `NewsItem` 도메인 모델로 정규화 후 저장

## 재실행/업데이트 지침

- 기존 엔드포인트 클라이언트는 보존, 신규 기능만 추가 메소드로.
- KIS 공식 문서 변경(필드·tr_id) 발견 시 WebFetch로 재확인 후 수정.
- **주문 계열 변경은 항상 모의계좌로 먼저 검증.** 실계좌 회귀 테스트 금지.
- 토큰 관리 구조 변경 시 `_workspace/market-data/api-map.md` 업데이트.
