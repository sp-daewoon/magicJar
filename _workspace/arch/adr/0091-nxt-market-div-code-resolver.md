# ADR 0091: NXT 통합 시장코드 종목 단위 resolver

- 상태: Accepted
- 작성일: 2026-05-20
- 결정자: chart-curator (협의: market-data, architect)
- 관련: ADR 0019, ADR 0028, ADR 0062, ADR 0063, ADR 0086

## 맥락(Context)

ADR 0063은 KIS `fid_cond_mrkt_div_code`의 `UN` 값을 사용해 KRX+NXT 통합 캔들을 수집하기로 결정했다. 이후 운영 코드가 `market == KOSPI`이면 무조건 `UN`, `KOSDAQ`이면 `J`로 분기하면서 결함이 생겼다. NXT 거래 가능 종목은 시장 전체가 아니라 종목 단위로 변하며, NXT 공식 화면도 KOSPI/KOSDAQ 상품군을 함께 제공한다. 따라서 시장명만으로 `UN`을 선택하면 비대상 종목의 빈 응답이 캔들 적재 중단으로 이어질 수 있다.

KIS 공식 샘플은 `fid_cond_mrkt_div_code` 옵션을 `J:KRX`, `NX:NXT`, `UN:통합`으로 제공한다. NXT 공식 안내는 KRX 상장 종목을 NXT에서 거래할 수 있고, 08:00~20:00 확장 시간을 제공한다고 설명한다. 즉 `UN` 자체는 유효하지만, 적용 기준은 market-level이 아니라 instrument-level이어야 한다.

## 검토한 대안(Options)

| 대안 | 장점 | 단점 |
|------|------|------|
| A. 모든 캔들 REST를 `J`로 고정 | 결함 즉시 차단, 호출 안정성 높음 | NXT 과거 분봉/일봉 cover가 사라져 ADR 0063 의제 후퇴 |
| B. KOSPI=`UN`, KOSDAQ=`J` 유지 | 코드 변경 없음, 기존 ADR 0063 문구와 일치 | 사용자 지적 결함 지속, KOSDAQ NXT 거래 가능성도 반영 불가 |
| C. 종목 단위 allow-list + `J` fallback | NXT 후보만 `UN` 우선, 빈 응답 시 KRX 캔들 보존 | dedicated `nxt_tradable` 컬럼 전까지 allow-list가 완전하지 않음 |

## 결정(Decision)

대안 C를 채택한다.

1. `market == KOSPI`라는 이유만으로 `UN`을 선택하지 않는다.
2. 1차 MVP는 이미 instrument 테이블에 존재하는 `is_kospi200`, `is_kosdaq150`을 보수적 NXT allow-list로 사용한다.
3. allow-list 종목은 `UN`을 먼저 시도하고, 응답 0건이면 `J`로 재시도한다.
4. allow-list 밖 종목, `UNKNOWN`, 기타 market은 `J`를 기본값으로 사용한다.
5. 후속으로 NXT 공식 거래 가능 종목 목록을 수집해 dedicated `nxt_tradable` 또는 status-history 계열로 승격한다.

KIS-first 출처:

- KIS daily item chart raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
- KIS time daily chart raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
- NXT market overview: https://www.nextrade.co.kr/en/marketOverview/content.do
- NXT market information: https://nextrade.co.kr/en/main.do

## 결과(Consequences)

- 긍정: `KOSPI -> UN` 일괄 처리로 캔들 fetch가 빈 응답에 막히는 문제를 줄인다.
- 긍정: KOSDAQ150처럼 NXT 후보가 될 수 있는 KOSDAQ 종목도 통합 조회 대상이 된다.
- 긍정: `UN`이 실패해도 `J` fallback으로 최소 KRX 기준 캔들을 보존한다.
- 부정: `is_kospi200/is_kosdaq150`은 NXT 전체 eligible list의 완전한 대체물이 아니다.
- 후속 과제: NXT 거래 가능 종목 목록을 정식으로 수집하고, `instrument` 또는 `instrument_status_history`에 종목 단위 NXT eligible 상태를 보존한다.

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-20 | 최초 채택. `KOSPI=UN/KOSDAQ=J` market-level 분기를 종목 단위 후보 + `J` fallback 정책으로 보강. |
