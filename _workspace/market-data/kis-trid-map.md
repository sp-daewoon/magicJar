# KIS OpenAPI tr_id 사용 목록 (Phase B-2)

## REST 시세

| 기능 | 경로 | tr_id | 호출 클래스 |
|------|------|-------|-------------|
| 현재가 | `/uapi/domestic-stock/v1/quotations/inquire-price` | `FHKST01010100` | `KisQuoteApi.fetchCurrentPrice` |
| 일봉 차트 | `/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice` | `FHKST03010100` | `KisQuoteApi.fetchDailyCandles` |

## REST 주문 (모의/실 분기)

| 기능 | 경로 | mock tr_id | real tr_id | 호출 클래스 |
|------|------|-----------|-----------|-------------|
| 매수(현금) | `/uapi/domestic-stock/v1/trading/order-cash` | `VTTC0802U` | `TTTC0802U` | `KisOrderApi.placeOrder(BUY)` |
| 매도(현금) | `/uapi/domestic-stock/v1/trading/order-cash` | `VTTC0801U` | `TTTC0801U` | `KisOrderApi.placeOrder(SELL)` |

## OAuth

| 기능 | 경로 | 주석 |
|------|------|------|
| 토큰 발급 | `POST /oauth2/tokenP` | `KisTokenStore.issueToken`. 23h 캐시. |

## WebSocket

| tr_id | 의미 | 호출 클래스 |
|-------|------|-------------|
| `H0STCNT0` | 실시간 체결가 | `KisWsClient.subscribe(code)` |

## 미사용(후속 도입 예정)

| tr_id | 의미 | 비고 |
|-------|------|------|
| `H0STASP0` | 실시간 호가 | 파서 추가 필요 |
| `TTTC8434R` / `VTTC8434R` | 잔고 조회 | order-exec 통합 시 필요 |
| `FHKST03010200` | 분봉 | strategy-engine 요구 시 |
| `POST /oauth2/Approval` | WS approval_key | 현재 setApprovalKey 수동 주입 |
| `POST /uapi/hashkey` | 주문 hashkey | 일부 trId 강제 시 도입 |
