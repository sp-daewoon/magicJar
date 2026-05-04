# ADR 0035: KIS REST/WS HTTP 정밀화 (EGW00202 hotfix + hashkey 표준)

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M)
- master §위치: `docs/architecture/master-architecture.md` v2 §외부.KIS·§3-1
- 트랙: T2 (W1)
- 선행: ADR 0003 (KIS 채택), ADR 0034

---

## 1. 맥락

1번 트랙 dogfooding(2026-05-02)에서 발견:

- 사용자가 동일 KIS 모의 키·동일 endpoint로 `curl` 호출 → 정상 응답.
- magicJar `RealKisRestAdapter` (Spring `RestClient` 기반) 호출 → `EGW00202 GW 라우팅 오류` 빈발.
- 차이 의심: 헤더 케이스(`appkey` vs `appKey`), Content-Type charset, hashkey 누락, User-Agent, Accept 헤더, body 직렬화 trailing whitespace·정렬·null 필드 등.

운영 가시성을 갖고 표준화한다.

## 2. 결정

### 2-1. raw HTTP 로깅 의무

`RealKisRestAdapter` 모든 요청·응답을 raw로 로깅 (dev profile · `MAGICJAR_KIS_RAW_LOG=true` 시):

```
[KIS-REQ] POST /uapi/domestic-stock/v1/trading/order-cash
  headers: { Content-Type: application/json; charset=UTF-8, authorization: Bearer ***, appkey: ***, appsecret: ***, tr_id: TTTC0011U, custtype: P, hashkey: <hash> }
  body: {"CANO":"50158197","ACNT_PRDT_CD":"01","PDNO":"005930","ORD_DVSN":"01","ORD_QTY":"1","ORD_UNPR":"0"}
[KIS-RES] 200 OK rt_cd=0 msg1=정상처리되었습니다
```

비밀 마스킹 의무 (appkey·appsecret·계좌·KIS_HTS_ID).

### 2-2. 헤더 표준

KIS 공식 doc 정합 (정확한 케이스):

| 헤더 | 값 | 비고 |
|---|---|---|
| `Content-Type` | `application/json; charset=UTF-8` | charset 명시 |
| `authorization` | `Bearer <accessToken>` | 소문자 |
| `appkey` | KIS app key | 소문자 |
| `appsecret` | KIS app secret | 소문자 |
| `tr_id` | TR ID (`TTTC0011U` 등) | 소문자 + underscore |
| `custtype` | `P` (개인) | 1자 |
| `hashkey` | KIS hashkey (POST body의 hash) | 주문·체결 등 mutation에서 의무 |
| `User-Agent` | `magicjar/<version>` | 명시 |

### 2-3. hashkey 발급 절차

POST 호출 전:
1. `POST /uapi/hashkey` body=요청 body → `HASH` 응답 받음.
2. 본 요청 헤더에 `hashkey: <HASH>` 추가.
3. body는 hashkey 발급 시 사용한 body와 **byte 단위 동일**해야 함 — 직렬화 순서·whitespace 모두 동일. `ObjectMapper`의 `INDENT_OUTPUT=false` + 필드 순서 deterministic 의무.

### 2-4. body 직렬화 표준

```kotlin
val mapper = jacksonObjectMapper().apply {
    setSerializationInclusion(JsonInclude.Include.NON_NULL)
    configure(SerializationFeature.INDENT_OUTPUT, false)
    configure(SerializationFeature.WRITE_NULL_MAP_VALUES, false)
}
```

모든 KIS 호출은 본 mapper 단일 사용. `RestClient`는 `MappingJackson2HttpMessageConverter` 명시 주입.

### 2-5. EGW00202 hotfix 구체

발견 가능 원인 후보 5건 + 진단 절차:

1. **헤더 케이스 mismatch** — `appKey` (camelCase) → `appkey` (소문자) 정정.
2. **hashkey 누락** — POST 호출에서 의무.
3. **User-Agent 누락** — KIS 라우터가 클라 식별 못 함.
4. **Content-Type charset 누락** — `application/json` → `application/json; charset=UTF-8`.
5. **body 직렬화 차이** — null 필드 포함, 정렬 비결정성. mapper 표준화.

raw HTTP 로그 + curl 비교 1회로 원인 식별 후 fix.

## 3. 결과·영향

- W1 진입 시 `RealKisRestAdapter` 본 표준 적용 + dogfooding으로 EGW00202 0회 검증.
- 단위 테스트: WireMock으로 KIS 응답 mock + 헤더·body byte 검증.
- W3 진입 시 실 KIS 모의 호출 풀 e2e — EGW 0건 확인 후 R3 머지.

## 4. 검토 대안

- **(a) raw 로그 끄고 추정** — dogfooding 비용↑.
- **(b) HTTP client lib 교체 (OkHttp 등)** — 기존 1번 트랙 구현 회귀, 운영 부담↑. 거부.
- **(c) RestClient 유지 + 표준화 + raw 로깅 (채택)** — 진단·운영 정합.

## 5. References

- master-architecture.md §외부 매트릭스, §3-1
- ADR 0003 (KIS OpenAPI 채택)
- ADR 0034 (REJECTED Audit — KIS 에러 audit)
- `_workspace/_archive/dogfooding/2026-05-02.md` (EGW00202 발견)
- KIS 공식 doc: https://apiportal.koreainvestment.com/

---

## Appendix A — KIS 실시간 시세 trId 표 (T2 R2-A 추가, 2026-05-04)

WS subscribe frame body `input.tr_id` + `input.tr_key` 매핑. GitHub source(`koreainvestment/open-trading-api/examples_llm/domestic_stock`) 검증 결과.

| 함수 | tr_id | tr_key | encrypted | 응답 컬럼 |
|---|---|---|---|---|
| 체결가 (통합) `ccnl_total` | `H0UNCNT0` | 종목코드(6자리) | N | 47 |
| 호가 (통합) `asking_price_total` | `H0UNASP0` | 종목코드(6자리) | N | ~58 |
| 지수 체결 `index_ccnl` | `H0UPCNT0` | 지수코드(KOSPI=`0001`, KOSDAQ=`1001`) | N | 30 |
| 지수 예상체결 `index_exp_ccnl` | `H0UPANC0` | 지수코드 | N | 30 |
| 장운영(통합) `market_status_total` | `H0UNMKO0` | 종목코드(6자리) | N | 10 |
| 체결통보 `ccnl_notice` | `H0STCNI0`(real) / `H0STCNI9`(demo) | HTS ID | **Y** (AES) | — |

**중요**: 시세 5종은 평문 — AES iv/key 분기는 체결통보(`H0STCNI*`)에만 적용. `KisWsRouter`(R2-A1)는 trId별 분기 처리.

approval_key 발급: `POST /oauth2/Approval` body `{grant_type:"client_credentials", appkey, secretkey}` → response `{approval_key:"..."}`. WS subscribe frame header에 첨부.
