# PR-21 instrument 테이블 KIS-first 본격 재설계 (사용자 비판 응답)

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 대상 PR 시리즈: PR-21a (market-data) · PR-21b (backend-core) — 병렬 가능 (§A 충돌 행렬 참조)
- 진입 prerequisite: PR-11~PR-20 모두 MERGED. main HEAD = PR-20 머지 결과 (EUC-KR byte 파싱 정정 + watchlist join). 대다수 종목 마스터 코드 가동 중이지만 instrument 테이블이 14 컬럼만 채워짐.
- **사용자 비판 인용 (재발 방지 의무 명시)**:
  > "kis 에서 제공받는 데이터 규격 확인 안하고 그냥 막 테이블 만들어서 꾸겨넣은거잖아 ... kis openapi 최소한 규격만봐도 이렇게 데이터가 많은데 ... 이렇게 하는게 말이되냐"
- 정합 SoT:
  - **사용자 메모리 `feedback_kis_first_policy.md`** — KIS MCP·open-trading-api 베이스 (위반 정정 의무) ★
  - **사용자 메모리 `feedback_v3_skepticism_no_regression.md`** — fitness gap 보고 의무 ★
  - **`_workspace/specs/pr-21-kis-header-raw-verification.md`** — KIS 헤더 raw 검증 보고서 (필드 73·67 + byte offset 100%) ★
  - **ADR 0017** Instrument Master 1급 시민
  - **ADR 0039** 종목 마스터 갱신 Cron (13시각 +5분)
  - **ADR 0030** master architecture v2 SoT
  - **ADR 0050** CHANGELOG 슬롯 채번룰 (PR-21이 네 번째 적용 사이클)
  - `_workspace/specs/pr-11-im1-design.md` (PR-11 baseline — 본 PR-21이 후속 보강)
  - `_workspace/backlog.md` §6 (사용자 dogfooding §6.4 SymbolDetail 페이지 내부 동작 — 본 PR-21 비범위)
- 작업 원칙 (개발 룰북 정합):
  - 룰 2 — 단일 책임 (PR-21a = market-data: KIS 파싱 본격 + Flyway V5 + KIS-first 출처 인용 강화 / PR-21b = backend-core: InstrumentJpaEntity 70+ 컬럼 보강 + InstrumentRepository 조회 메서드 추가 + Watchlist/SymbolMeta 응답 보강). frontend는 본 PR-21 비범위(데이터 모델 보강이 본질).
  - 룰 3 — 본 PR-21은 R2 (어댑터+영속) 비중. R1 도메인은 변경 0(핵심 — Instrument 도메인 모델은 simplicity 우선, 추가 메타는 JPA entity layer만).
  - 룰 4 — smoke 테스트 1~2건만 (R3 일괄은 별도 사이클).
  - 룰 5 — AI 호출 0 (본 PR-21은 W1 트랙 보강 — LLM provider import 절대 금지).

---

## §1. 사용자 비판 인정 + fitness gap 분석

### 1-1. 비판 인용

> "kis 에서 제공받는 데이터 규격 확인 안하고 그냥 막 테이블 만들어서 꾸겨넣은거잖아 ... kis openapi 최소한 규격만봐도 이렇게 데이터가 많은데 ... 이렇게 하는게 말이되냐"

### 1-2. 비판 인정 — 정확한 지적

**위반 사실**:
1. PR-11 spec §3-1 V1 baseline + PR-11 V4 ALTER 결과 instrument 테이블이 **14 컬럼**만 사용:
   - `code, isin, name, name_en, market, type, sector, listed_at, delisted_at, par_value(미존재), trading_unit(미존재), active, created_at, updated_at` + V4에서 `name_norm, theme` 추가 = **13 row 컬럼**
2. KIS 헤더 raw 검증 결과 (`pr-21-kis-header-raw-verification.md`) **KOSPI 73 + KOSDAQ 67 필드** 제공 — 이중 본 시스템이 **4 필드만 파싱**(code·isin·name·nameEn). 시장경고·거래정지·관리종목·시총규모·업종 3단·매매단위·기준가·전일거래량·상장주수·자본금·우선주·KRX 섹터 14종·신용가능·증거금비율 등 **70+ 필드 0건 활용**
3. PR-11 spec §3-2에서 V4 결정 시 KIS 헤더 정독 누락 → "한글 검색 정확도 ↑"·"alias 매핑" 정도의 협소한 설계 결정. 자동매매 본체에 필요한 **거래정지·시장경고·신용가능·증거금비율 등 매매 가드 필드 0건 검토**

### 1-3. 사용자 메모리 위반 명시

| 메모리 | 위반 사실 | PR-21 정정 |
|---|---|---|
| `feedback_kis_first_policy.md` "tr_id·endpoint·DSL 자체 추론 금지, MCP/공식 레포에서 가져오기 의무" | 종목 마스터 컬럼 결정 시 KIS 헤더 raw 정독 0건 → 14 컬럼 자체 추론 | 본 PR-21에서 KIS `.h` + Python 파서 raw fetch + byte offset 100% 검증 후 결정 |
| `feedback_v3_skepticism_no_regression.md` "자동매매 적합성 의심 lens" | "instrument 14 컬럼이 자동매매 본체에 충분한가?" 질문 누락 → 매매 가드 필드 부재 | 본 PR-21 §3에서 Tier 1·2·3·4 분류 + 자동매매 본체 필요성 lens 통과 |
| `feedback_orchestrator_active_parallel.md` (간접 — architect "직렬 권장" 답습 금지) | (해당사항 0 — 본건은 모듈 분배가 아닌 데이터 모델 함정) | 본 PR-21에서 add-add 0% 사전 검증 + 병렬 권장 유지 |

### 1-4. fitness gap 정량

| 영역 | KIS 제공 필드 수 | 현재 본 시스템 활용 | gap | gap 사유 |
|---|---|---|---|---|
| 식별 (code·name·isin·market) | 4 | 4 | 0 | OK |
| 매매 가드 (trht_yn·sltr_yn·mang_issu_yn·mrkt_alrm·crdt_able·marg_rate 등) | **15** | **0** | **15** | 자동매매 본체에 핵심 필드 미활용 |
| 분류 인덱스 (kospi200·kosdaq150·krx_sector_14·kospi100·etp_prod·prst_cls·scrt_grp 등) | **24** | **0** | **24** | screening·universe 분류 0 지원 |
| 시장 메타 (avls_scal·prdy_avls_scal·prdy_vol·stck_sdpr·매매단위·상장주수·자본금·결산월·공모가·액면가·상장일자·우선주 등) | **15** | **2** (sector·listed_at만) | **13** | 시총/거래량 기반 screening 차단 |
| 재무 (sale_account·bsop_prfi·op_prfi·thtr_ntin·roe·base_date·grp_code 등) | **7** | **0** | **7** | ROE/순이익 screening 차단 |
| 업종 분류 (bstp 3단 + kospi200_apnt + 14 KRX 섹터 yn) | **18** | **1** (sector text only — 1차 source 미정) | **17** | 업종 필터·테마 분류 차단 |
| ETP 분류 (etp_prod_cls·elw_pblc·prst_cls·vntr_issu) | 4 | 1 (type 거친 분류만) | 3 | ETF/ETN/우선주/벤처 정확 분류 차단 |
| 락·증자·이상급등·SPAC | 4 | 0 | 4 | 매매 위험 가드 0 |
| **합계 (KIS 73 KOSPI 기준)** | **73** | **6** (code·isin·name·nameEn·market·sector·listed_at = 7중 6)  | **67** | **자동매매 적합성 fitness gap 67/73 = 91.7%** |

**결론**: 현재 instrument 테이블은 자동매매 본체 부적합. T-IND·T-DSL·T-SC·T-BT·T-RISK·T-SIG 진입 시 매매 가드(거래정지·시장경고·관리종목)·screening 분류(시총·업종·KOSPI200)·재무 메타(ROE·매출액) 모두 부재로 차단. **본 PR-21은 fitness gap 91.7%를 30% 이내로 축소** (Tier 1·2 필수 + Tier 3 분류 인덱스).

---

## §2. KIS 헤더 raw 정독 결과 (요약)

상세는 `pr-21-kis-header-raw-verification.md` 참조. 핵심:

- KOSPI: `.h` typedef 73 필드 + Python 파서 70 필드(Part2) + Part1 3 = 정합
- KOSDAQ: `.h` typedef 67 필드 + Python 파서 64 필드(Part2) + Part1 3 = 정합
- byte offset 매트릭스 100% Python 파서 width 배열 정합 (mismatch 0건)
- KOSPI/KOSDAQ 차이 14건 식별 (KOSPI 단독 9 + KOSDAQ 단독 3 + 의미 다름 2)
- 종목 type 권위 매핑: `scrt_grp_cls_code` 13종 (ST·MF·RT·SC·IF·DR·EW·EF·SW·SR·BC·FE·FS)
- mst.zip 다운로드 URL: KIS Python 파서에서 직접 인용 (자체 추론 0건)

---

## §3. DB 스키마 LOCK ★ — Tier 1·2·3·4 컬럼 정의

### 3-1. 설계 원칙

1. **KIS 필드명 → 본 시스템 컬럼명 명시 매핑** — 모든 컬럼 주석에 KIS 원본 필드명 + 의미 인용 의무
2. **Tier 분류** — 자동매매 본체 우선순위 (Tier 1·2 필수 + Tier 3 선택 + Tier 4 후속)
3. **nullable 우선** — KOSPI/KOSDAQ 차이 14건 + 정적 시드 fallback(필드 부재 가능) 흡수
4. **CHECK constraint** — 코드 도메인 명확화 (scrt_grp_cls_code 13종 + market_alert 4종 등)
5. **인덱스 add-only** — Tier 1·2 필드 중 screening 빈출 컬럼만 (시총·KOSPI200·거래정지·active)
6. **JSONB 회피** — 컬럼 단순 BOOLEAN/VARCHAR(N) 직접 매핑 우선. KRX 섹터 14종은 컬럼 14개 분리(JSONB는 인덱스 비효율 + 가독성↓)

### 3-2. Tier 1 — 식별·시장 메타 (자동매매 본체 필수, KIS 핵심)

| 컬럼명 | 타입 | KIS 필드 | KOSPI/KOSDAQ | 의미·정합 사유 |
|---|---|---|---|---|
| `code` | VARCHAR(8) PK | (Part1 mksc_shrn_iscd 9byte → 6자리) | 양쪽 | (기존) 단축코드 — 6자리 |
| `isin` | VARCHAR(12) | (Part1 stnd_iscd) | 양쪽 | (기존) 표준코드 |
| `name` | VARCHAR(80) | (Part1 hts_kor_isnm) | 양쪽 | (기존) 한글종목명 |
| `name_en` | VARCHAR(120) | (외부 source — KIS는 한글만 제공) | 외부 | (기존) 영문명 (1차 빈값 OK — 영문 검색 후속) |
| `market` | VARCHAR(10) NOT NULL | (KIS 파일 분리 — KOSPI/KOSDAQ) | 양쪽 | (기존) KOSPI·KOSDAQ·KONEX·UNKNOWN |
| `type` | VARCHAR(20) NOT NULL | scrt_grp_cls_code 매핑 | 양쪽 | (기존) STOCK·ETF·ETN·PREFERRED·WARRANT·REIT·DR (DR 신규) |
| `securities_group` | VARCHAR(2) | scrt_grp_cls_code | 양쪽 | **신규** — 권위 분류 (ST·MF·RT·SC·IF·DR·EW·EF·SW·SR·BC·FE·FS) — type 매핑 source |
| `listed_at` | DATE | stck_lstn_date | 양쪽 | (기존) 상장일자 — 백테스트 기간 분리 |
| `delisted_at` | DATE | (sync diff) | 양쪽 | (기존) 상장폐지일 (DELETE 금지) |
| `active` | BOOLEAN | (sync diff) | 양쪽 | (기존) 거래중 |
| `name_norm` | VARCHAR(80) | (정규화 — 본 시스템 자체) | 양쪽 | (V4) 검색 정규화 |
| `theme` | VARCHAR(200) | (외부 source) | 외부 | (V4) 1차 NULL — 후속 |

**Tier 1 합계**: 13 컬럼 (기존 11 + V4 2). 본 PR-21에서 추가 1 (`securities_group`).

### 3-3. Tier 2 — 매매 가드 (자동매매 본체 필수, 주문 안전망)

**거래정지·관리·시장경고·신용**

| 컬럼명 | 타입 | KIS 필드 | KOSPI/KOSDAQ | 의미 |
|---|---|---|---|---|
| `trading_halt` | BOOLEAN | trht_yn (Y/N) | 양쪽 | **신규** — 거래정지 (true 시 매매 차단) |
| `liquidation` | BOOLEAN | sltr_yn | 양쪽 | **신규** — 정리매매 (true 시 매수 차단) |
| `management_stock` | BOOLEAN | mang_issu_yn | 양쪽 | **신규** — 관리종목 (위험 가드) |
| `market_alert` | VARCHAR(2) | mrkt_alrm_cls_code | 양쪽 | **신규** — 시장경고 (00·01·02·03 — 03=투자위험) |
| `market_alert_warning` | BOOLEAN | mrkt_alrm_risk_adnt_yn | 양쪽 | **신규** — 시장경고위험 예고 |
| `false_disclosure` | BOOLEAN | insn_pbnt_yn | 양쪽 | **신규** — 불성실공시 |
| `backdoor_listing` | BOOLEAN | byps_lstn_yn | 양쪽 | **신규** — 우회상장 |
| `short_overheated` | BOOLEAN | ssts_hot_yn | 양쪽 | **신규** — 공매도 과열 |
| `abnormal_runup` | BOOLEAN | stange_runup_yn | 양쪽 | **신규** — 이상급등 |
| `investment_caution` | BOOLEAN | invt_alrm_yn | KOSDAQ만 (NULL on KOSPI) | **신규** — 투자주의환기 (KOSDAQ) |
| `ex_rights_type` | VARCHAR(2) | flng_cls_code | 양쪽 | **신규** — 락구분 (00·01:권리락·02:배당락 등) |
| `face_value_change` | VARCHAR(2) | fcam_mod_cls_code | 양쪽 | **신규** — 액면가변경 (00·01:분할·02:병합) |
| `capital_increase` | VARCHAR(2) | icic_cls_code | 양쪽 | **신규** — 증자구분 (00·01:유상·02:무상·03:유무상) |
| `credit_able` | BOOLEAN | crdt_able | 양쪽 | **신규** — 신용주문 가능 |
| `credit_days` | SMALLINT | crdt_days | 양쪽 | **신규** — 신용기간 (일) |
| `margin_rate` | SMALLINT | marg_rate (3자리 정수) | 양쪽 | **신규** — 증거금 비율 (%) |
| `short_overheated_status` | VARCHAR(1) | short_over_cls_code | 양쪽 | **신규** — 단기과열 (0·1:지정예고·2:지정·3:지정연장) |

**Tier 2 합계**: 17 컬럼 신규.

### 3-4. Tier 3 — 분류 인덱스 (screening·universe 분류 — 선택)

**시총 규모·업종·KRX 섹터·제조업·SPAC·ETP**

| 컬럼명 | 타입 | KIS 필드 | KOSPI/KOSDAQ | 의미 |
|---|---|---|---|---|
| `industry_large` | VARCHAR(4) | bstp_larg_div_code | 양쪽 | **신규** — 지수업종 대분류 |
| `industry_medium` | VARCHAR(4) | bstp_medm_div_code | 양쪽 | **신규** — 지수업종 중분류 |
| `industry_small` | VARCHAR(4) | bstp_smal_div_code | 양쪽 | **신규** — 지수업종 소분류 |
| `kospi200_sector` | VARCHAR(1) | kospi200_apnt_cls_code | KOSPI만 | **신규** — KOSPI200 섹터 (0·1:건설·...·B:커뮤니케이션) |
| `avls_scale` | VARCHAR(1) | avls_scal_cls_code | 양쪽 (의미 다름) | **신규** — 시총규모 (KOSPI: 0·1대·2중·3소 / KOSDAQ: 0·1·KOSDAQ100·2·KOSDAQmid300·3·KOSDAQsmall) |
| `is_kospi100` | BOOLEAN | kospi100_issu_yn | KOSPI만 | **신규** — KOSPI100 |
| `is_kospi50` | BOOLEAN | kospi50_issu_yn | KOSPI만 | **신규** — KOSPI50 |
| `is_kospi200` | BOOLEAN | (kospi200_apnt_cls_code != '0') | KOSPI만 | **신규** — KOSPI200 (derived) |
| `is_kosdaq150` | BOOLEAN | ksq150_nmix_yn | KOSDAQ만 | **신규** — KOSDAQ150 |
| `is_krx` | BOOLEAN | krx_issu_yn | 양쪽 | **신규** — KRX 종목 |
| `is_krx100` | BOOLEAN | krx100_issu_yn | 양쪽 | **신규** — KRX100 |
| `is_krx300` | BOOLEAN | krx300_issu_yn | 양쪽 | **신규** — KRX300 |
| `krx_sector_car` | BOOLEAN | krx_car_yn | 양쪽 | **신규** — KRX 자동차 |
| `krx_sector_semicon` | BOOLEAN | krx_smcn_yn | 양쪽 | **신규** — KRX 반도체 |
| `krx_sector_bio` | BOOLEAN | krx_bio_yn | 양쪽 | **신규** — KRX 바이오 |
| `krx_sector_bank` | BOOLEAN | krx_bank_yn | 양쪽 | **신규** — KRX 은행 |
| `krx_sector_energy_chem` | BOOLEAN | krx_enrg_chms_yn | 양쪽 | **신규** — KRX 에너지화학 |
| `krx_sector_steel` | BOOLEAN | krx_stel_yn | 양쪽 | **신규** — KRX 철강 |
| `krx_sector_media_comm` | BOOLEAN | krx_medi_cmnc_yn | 양쪽 | **신규** — KRX 미디어통신 |
| `krx_sector_construction` | BOOLEAN | krx_cnst_yn | 양쪽 | **신규** — KRX 건설 |
| `krx_sector_securities` | BOOLEAN | krx_scrt_yn | 양쪽 | **신규** — KRX 증권 |
| `krx_sector_ship` | BOOLEAN | krx_ship_yn | 양쪽 | **신규** — KRX 선박 |
| `krx_sector_insurance` | BOOLEAN | krx_insu_yn | 양쪽 | **신규** — KRX 보험 |
| `krx_sector_transport` | BOOLEAN | krx_trnp_yn | 양쪽 | **신규** — KRX 운송 |
| `is_manufacturing` | BOOLEAN | mnin_cls_code_yn | KOSPI만 | **신규** — 제조업 |
| `is_low_liquidity` | BOOLEAN | low_current_yn | 양쪽 | **신규** — 저유동성 |
| `is_governance_index` | BOOLEAN | sprn_strr_nmix_issu_yn | KOSPI만 | **신규** — 지배구조지수 |
| `is_spac` | BOOLEAN | etpr_undt_objt_co_yn | 양쪽 | **신규** — SPAC |
| `is_venture` | BOOLEAN | vntr_issu_yn | KOSDAQ만 | **신규** — 벤처기업 |
| `is_sri_index` | BOOLEAN | sri_nmix_yn | KOSPI만 | **신규** — SRI 지수 |
| `etp_product_type` | VARCHAR(1) | etp_prod_cls_code | 양쪽 | **신규** — ETP 분류 (0·1:투자회사형·2:수익증권형·3:ETN·4:손실제한ETN·5:상장형수익증권) |
| `is_elw_publisher` | BOOLEAN | elw_pblc_yn | KOSPI만 | **신규** — ELW 발행 |
| `preferred_stock_type` | VARCHAR(1) | prst_cls_code | 양쪽 | **신규** — 우선주 (0·1:구형·2:신형) |
| `group_code` | VARCHAR(3) | grp_code | 양쪽 | **신규** — 그룹사 코드 |

**Tier 3 합계**: 33 컬럼 신규. **JSONB 회피 결정**: KRX 섹터 14종 BITMAP/JSONB 후보 검토 → **분리 BOOLEAN 채택** (인덱스 효율·screening DSL 가독성·디버깅 용이성). 14 BOOLEAN은 PostgreSQL row 부담 무시할만한 수준 (1byte each + null bitmap).

### 3-5. Tier 4 — 시장 메타 + 재무 (선택, post-MVP 후보)

**기준가·매매단위·시총·재무·자본**

| 컬럼명 | 타입 | KIS 필드 | KOSPI/KOSDAQ | 의미 | Tier4 사유 |
|---|---|---|---|---|---|
| `base_price` | NUMERIC(15,0) | stck_sdpr (9자리) | 양쪽 | **신규** — 주식 기준가 | T-SC 진입 시 필요 |
| `trading_unit_normal` | INTEGER | frml_mrkt_deal_qty_unit (5자리) | 양쪽 | **신규** — 정규시장 매매단위 | 주문 시 필요 (T-O 트랙) |
| `trading_unit_overtime` | INTEGER | ovtm_mrkt_deal_qty_unit | 양쪽 | **신규** — 시간외 매매단위 | T-O 트랙 |
| `prev_volume` | BIGINT | prdy_vol (12자리) | 양쪽 | **신규** — 전일 거래량 | T-IND·T-SC 거래량 필터 |
| `face_value` | NUMERIC(15,0) | stck_fcam (12자리) | 양쪽 | **신규** — 액면가 | 분할 분석 |
| `listed_shares` | BIGINT | lstn_stcn (15자리, 천 단위) | 양쪽 | **신규** — 상장주수 (천) | 시총 검증 |
| `capital` | NUMERIC(25,0) | cpfn (21자리) | 양쪽 | **신규** — 자본금 | 재무 분석 |
| `settlement_month` | SMALLINT | stac_month (mm) | 양쪽 | **신규** — 결산월 | 재무 시즌 분석 |
| `public_offering_price` | NUMERIC(10,0) | po_prc (7자리) | 양쪽 | **신규** — 공모가 | IPO 분석 |
| `market_cap_prev` | BIGINT | prdy_avls_scal (9자리, 억) | 양쪽 | **신규** — 전일기준 시가총액(억) | T-SC 시총 필터 핵심 |
| `sales_amount` | BIGINT | sale_account (9자리, 억) | 양쪽 | **신규** — 매출액 (Tier 4 — 재무) | T-IND post-MVP |
| `operating_profit` | BIGINT | bsop_prfi (9자리, 억) | 양쪽 | **신규** — 영업이익 | T-IND post-MVP |
| `ordinary_profit` | BIGINT | op_prfi (9자리, 억) | 양쪽 | **신규** — 경상이익 | T-IND post-MVP |
| `net_income` | BIGINT | thtr_ntin (5자리, 억) | 양쪽 | **신규** — 당기순이익 | T-IND post-MVP |
| `roe` | NUMERIC(7,2) | roe (9자리 — 부호 포함, 소수 2자리) | 양쪽 | **신규** — ROE | T-IND post-MVP 핵심 |
| `financial_base_date` | DATE | base_date (yyyymmdd) | 양쪽 | **신규** — 재무 기준년월 | 재무 데이터 신뢰성 |
| `credit_limit_exceeded` | BOOLEAN | co_crdt_limt_over_yn | 양쪽 | **신규** — 회사신용한도 초과 | 위험 가드 보조 |
| `collateral_lendable` | BOOLEAN | secu_lend_able_yn | 양쪽 | **신규** — 담보대출 가능 | T-O 트랙 |
| `short_lendable` | BOOLEAN | stln_able_yn | 양쪽 | **신규** — 대주(공매도) 가능 | T-O 공매도 |

**Tier 4 합계**: 19 컬럼. 모두 본 PR-21에서 add (단, parser는 Tier 1·2·3 우선 + Tier 4는 sync 시점에 채움). **본 PR-21 Tier 4 add-only column 채택** — ALTER TABLE 한 번에 ALL 처리 (V5·V6 분리 회피, 시드 데이터 충실성↑).

### 3-6. 컬럼 합계 + V5 LOC 추정

| Tier | 컬럼 수 (신규) | 누계 |
|---|---|---|
| 기존 (V1·V4) | 13 | 13 |
| Tier 1 신규 | 1 (securities_group) | 14 |
| Tier 2 신규 | 17 | 31 |
| Tier 3 신규 | 33 | 64 |
| Tier 4 신규 | 19 | 83 |
| **합계** | **70 신규** | **83** |

**최종 컬럼 수: 83** (KIS KOSPI 73 + KOSDAQ 67 + system 메타 = 본 시스템 83 흡수). **fitness gap 91.7% → 13% 이내 (자동매매 본체 + post-MVP 영역 모두 흡수)**.

### 3-7. 기존 컬럼 정정 — 0건

**중요**: 본 PR-21은 **add-only 보강** (기존 컬럼 변경 0). 기존 14 컬럼 그대로 유지 (PR-11·PR-19·PR-20 가동 중인 코드 호환 보장).

---

## §4. Flyway V5 마이그레이션 LOCK ★ — `V5__instrument_kis_first_expansion.sql`

### 4-1. 위치

`api/src/main/resources/db/migration/V5__instrument_kis_first_expansion.sql` — PR-21a market-data 단독 발행.

### 4-2. SQL 명세

```sql
-- V5__instrument_kis_first_expansion.sql
-- ADR 0017·0039 정합 + 사용자 비판 응답 (KIS-first 정책 위반 정정)
-- KIS open-trading-api 종목마스터정보(코스피).h + 종목마스터정보(코스닥).h + kis_kospi_code_mst.py + kis_kosdaq_code_mst.py
-- raw 검증 출처: _workspace/specs/pr-21-kis-header-raw-verification.md
-- 컬럼 70개 add-only ALTER. 기존 컬럼 수정 0건.

-- =============================================================================
-- Tier 1 (식별·시장 메타 보강) — 1 컬럼
-- =============================================================================
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS securities_group VARCHAR(2);
COMMENT ON COLUMN instrument.securities_group IS 'KIS scrt_grp_cls_code — ST/MF/RT/SC/IF/DR/EW/EF/SW/SR/BC/FE/FS (.h L9~L14)';

-- =============================================================================
-- Tier 2 (매매 가드) — 17 컬럼
-- =============================================================================
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS trading_halt BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS liquidation BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS management_stock BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS market_alert VARCHAR(2);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS market_alert_warning BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS false_disclosure BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS backdoor_listing BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS short_overheated BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS abnormal_runup BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS investment_caution BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS ex_rights_type VARCHAR(2);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS face_value_change VARCHAR(2);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS capital_increase VARCHAR(2);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS credit_able BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS credit_days SMALLINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS margin_rate SMALLINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS short_overheated_status VARCHAR(1);

-- =============================================================================
-- Tier 3 (분류 인덱스) — 33 컬럼
-- =============================================================================
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS industry_large VARCHAR(4);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS industry_medium VARCHAR(4);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS industry_small VARCHAR(4);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS kospi200_sector VARCHAR(1);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS avls_scale VARCHAR(1);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_kospi100 BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_kospi50 BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_kospi200 BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_kosdaq150 BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_krx BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_krx100 BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_krx300 BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_car BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_semicon BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_bio BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_bank BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_energy_chem BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_steel BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_media_comm BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_construction BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_securities BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_ship BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_insurance BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS krx_sector_transport BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_manufacturing BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_low_liquidity BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_governance_index BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_spac BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_venture BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_sri_index BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS etp_product_type VARCHAR(1);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS is_elw_publisher BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS preferred_stock_type VARCHAR(1);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS group_code VARCHAR(3);

-- =============================================================================
-- Tier 4 (시장 메타 + 재무) — 19 컬럼
-- =============================================================================
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS base_price NUMERIC(15,0);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS trading_unit_normal INTEGER;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS trading_unit_overtime INTEGER;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS prev_volume BIGINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS face_value NUMERIC(15,0);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS listed_shares BIGINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS capital NUMERIC(25,0);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS settlement_month SMALLINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS public_offering_price NUMERIC(10,0);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS market_cap_prev BIGINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS sales_amount BIGINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS operating_profit BIGINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS ordinary_profit BIGINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS net_income BIGINT;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS roe NUMERIC(7,2);
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS financial_base_date DATE;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS credit_limit_exceeded BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS collateral_lendable BOOLEAN DEFAULT false;
ALTER TABLE instrument ADD COLUMN IF NOT EXISTS short_lendable BOOLEAN DEFAULT false;

-- =============================================================================
-- CHECK constraint (코드 도메인 명확화)
-- =============================================================================
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_securities_group
    CHECK (securities_group IS NULL OR securities_group IN ('ST','MF','RT','SC','IF','DR','EW','EF','SW','SR','BC','FE','FS'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_market_alert
    CHECK (market_alert IS NULL OR market_alert IN ('00','01','02','03'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_ex_rights_type
    CHECK (ex_rights_type IS NULL OR ex_rights_type IN ('00','01','02','03','04','05','06','99'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_face_value_change
    CHECK (face_value_change IS NULL OR face_value_change IN ('00','01','02','99'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_capital_increase
    CHECK (capital_increase IS NULL OR capital_increase IN ('00','01','02','03','99'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_etp_product_type
    CHECK (etp_product_type IS NULL OR etp_product_type IN ('0','1','2','3','4','5'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_preferred_stock_type
    CHECK (preferred_stock_type IS NULL OR preferred_stock_type IN ('0','1','2'));
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_short_overheated_status
    CHECK (short_overheated_status IS NULL OR short_overheated_status IN ('0','1','2','3'));

-- type CHECK 보강 (기존 V1 chk_instrument_type에 'DR' 추가)
ALTER TABLE instrument DROP CONSTRAINT IF EXISTS chk_instrument_type;
ALTER TABLE instrument ADD CONSTRAINT chk_instrument_type
    CHECK (type IN ('STOCK','ETF','ETN','PREFERRED','WARRANT','REIT','DR','UNKNOWN'));

-- =============================================================================
-- 인덱스 add-only — Tier 1·2·3 빈출 screening 컬럼만
-- =============================================================================
-- 매매 가드 빠른 차단용
CREATE INDEX IF NOT EXISTS idx_instrument_trading_halt ON instrument (trading_halt) WHERE trading_halt = true;
CREATE INDEX IF NOT EXISTS idx_instrument_management_stock ON instrument (management_stock) WHERE management_stock = true;
CREATE INDEX IF NOT EXISTS idx_instrument_market_alert ON instrument (market_alert) WHERE market_alert IS NOT NULL AND market_alert != '00';

-- screening universe 분류용
CREATE INDEX IF NOT EXISTS idx_instrument_securities_group ON instrument (securities_group);
CREATE INDEX IF NOT EXISTS idx_instrument_kospi200 ON instrument (is_kospi200) WHERE is_kospi200 = true;
CREATE INDEX IF NOT EXISTS idx_instrument_kosdaq150 ON instrument (is_kosdaq150) WHERE is_kosdaq150 = true;
CREATE INDEX IF NOT EXISTS idx_instrument_industry_medium ON instrument (industry_medium) WHERE industry_medium IS NOT NULL;

-- 시총 기반 screening
CREATE INDEX IF NOT EXISTS idx_instrument_market_cap_prev ON instrument (market_cap_prev DESC NULLS LAST);
```

**LOC 추정**: 약 110 line (주석 포함). 단순 ALTER 70 + CHECK 9 + INDEX 8 = 87 statement.

### 4-3. V5 적용 시점

PR-21a 머지 시점에 api 모듈 부팅 시 Flyway 자동 적용. PostgreSQL 16에서 ADD COLUMN nullable + DEFAULT는 metadata-only 변경이라 즉시 완료(테이블 lock 1초 미만 — PG 11+ optimization).

### 4-4. 회귀 검증

- 기존 컬럼 0건 변경 → PR-11~PR-20 코드 호환 보장
- type CHECK 'DR' 추가는 add-only (확장 — 기존 row enum 영향 0)
- DEFAULT false는 기존 row가 신규 컬럼에 false 값을 자동으로 가짐 (NULL 회피 — Tier 2 매매 가드는 false default로 안전)

---

## §5. InstrumentJpaEntity 보강 LOCK ★ — backend-core 영역

### 5-1. 위치

`api/.../persistence/InstrumentJpaEntity.kt` — backend-core 단독 modify-own.

### 5-2. 보강 형식 (add-only field 70개)

기존 14 필드 + 신규 70 필드 = 84 필드. Kotlin data class 한계(generated equals/hashCode/toString 50+ 필드 시 가독성↓)를 고려해 **regular class** 유지 (현재도 regular class).

```kotlin
@Entity
@Table(name = "instrument")
class InstrumentJpaEntity(
    @Id @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "isin", length = 12) val isin: String? = null,
    @Column(name = "name", length = 80, nullable = false) val name: String,
    @Column(name = "name_en", length = 120) val nameEn: String? = null,
    @Column(name = "market", length = 10, nullable = false) val market: String,
    @Column(name = "type", length = 20, nullable = false) val type: String,
    @Column(name = "sector", length = 40) val sector: String? = null,
    @Column(name = "name_norm", length = 80) val nameNorm: String? = null,
    @Column(name = "theme", length = 200) val theme: String? = null,
    @Column(name = "listed_at") val listedAt: LocalDate? = null,
    @Column(name = "delisted_at") var delistedAt: LocalDate? = null,
    @Column(name = "active", nullable = false) var active: Boolean = true,

    // Tier 1 신규 (1 컬럼)
    @Column(name = "securities_group", length = 2) var securitiesGroup: String? = null,

    // Tier 2 매매 가드 (17 컬럼)
    @Column(name = "trading_halt", nullable = false) var tradingHalt: Boolean = false,
    @Column(name = "liquidation", nullable = false) var liquidation: Boolean = false,
    @Column(name = "management_stock", nullable = false) var managementStock: Boolean = false,
    @Column(name = "market_alert", length = 2) var marketAlert: String? = null,
    @Column(name = "market_alert_warning", nullable = false) var marketAlertWarning: Boolean = false,
    @Column(name = "false_disclosure", nullable = false) var falseDisclosure: Boolean = false,
    @Column(name = "backdoor_listing", nullable = false) var backdoorListing: Boolean = false,
    @Column(name = "short_overheated", nullable = false) var shortOverheated: Boolean = false,
    @Column(name = "abnormal_runup", nullable = false) var abnormalRunup: Boolean = false,
    @Column(name = "investment_caution", nullable = false) var investmentCaution: Boolean = false,
    @Column(name = "ex_rights_type", length = 2) var exRightsType: String? = null,
    @Column(name = "face_value_change", length = 2) var faceValueChange: String? = null,
    @Column(name = "capital_increase", length = 2) var capitalIncrease: String? = null,
    @Column(name = "credit_able", nullable = false) var creditAble: Boolean = false,
    @Column(name = "credit_days") var creditDays: Short? = null,
    @Column(name = "margin_rate") var marginRate: Short? = null,
    @Column(name = "short_overheated_status", length = 1) var shortOverheatedStatus: String? = null,

    // Tier 3 분류 인덱스 (33 컬럼)
    @Column(name = "industry_large", length = 4) var industryLarge: String? = null,
    @Column(name = "industry_medium", length = 4) var industryMedium: String? = null,
    @Column(name = "industry_small", length = 4) var industrySmall: String? = null,
    @Column(name = "kospi200_sector", length = 1) var kospi200Sector: String? = null,
    @Column(name = "avls_scale", length = 1) var avlsScale: String? = null,
    @Column(name = "is_kospi100", nullable = false) var isKospi100: Boolean = false,
    @Column(name = "is_kospi50", nullable = false) var isKospi50: Boolean = false,
    @Column(name = "is_kospi200", nullable = false) var isKospi200: Boolean = false,
    @Column(name = "is_kosdaq150", nullable = false) var isKosdaq150: Boolean = false,
    @Column(name = "is_krx", nullable = false) var isKrx: Boolean = false,
    @Column(name = "is_krx100", nullable = false) var isKrx100: Boolean = false,
    @Column(name = "is_krx300", nullable = false) var isKrx300: Boolean = false,
    @Column(name = "krx_sector_car", nullable = false) var krxSectorCar: Boolean = false,
    @Column(name = "krx_sector_semicon", nullable = false) var krxSectorSemicon: Boolean = false,
    @Column(name = "krx_sector_bio", nullable = false) var krxSectorBio: Boolean = false,
    @Column(name = "krx_sector_bank", nullable = false) var krxSectorBank: Boolean = false,
    @Column(name = "krx_sector_energy_chem", nullable = false) var krxSectorEnergyChem: Boolean = false,
    @Column(name = "krx_sector_steel", nullable = false) var krxSectorSteel: Boolean = false,
    @Column(name = "krx_sector_media_comm", nullable = false) var krxSectorMediaComm: Boolean = false,
    @Column(name = "krx_sector_construction", nullable = false) var krxSectorConstruction: Boolean = false,
    @Column(name = "krx_sector_securities", nullable = false) var krxSectorSecurities: Boolean = false,
    @Column(name = "krx_sector_ship", nullable = false) var krxSectorShip: Boolean = false,
    @Column(name = "krx_sector_insurance", nullable = false) var krxSectorInsurance: Boolean = false,
    @Column(name = "krx_sector_transport", nullable = false) var krxSectorTransport: Boolean = false,
    @Column(name = "is_manufacturing", nullable = false) var isManufacturing: Boolean = false,
    @Column(name = "is_low_liquidity", nullable = false) var isLowLiquidity: Boolean = false,
    @Column(name = "is_governance_index", nullable = false) var isGovernanceIndex: Boolean = false,
    @Column(name = "is_spac", nullable = false) var isSpac: Boolean = false,
    @Column(name = "is_venture", nullable = false) var isVenture: Boolean = false,
    @Column(name = "is_sri_index", nullable = false) var isSriIndex: Boolean = false,
    @Column(name = "etp_product_type", length = 1) var etpProductType: String? = null,
    @Column(name = "is_elw_publisher", nullable = false) var isElwPublisher: Boolean = false,
    @Column(name = "preferred_stock_type", length = 1) var preferredStockType: String? = null,
    @Column(name = "group_code", length = 3) var groupCode: String? = null,

    // Tier 4 시장 메타 + 재무 (19 컬럼)
    @Column(name = "base_price", precision = 15, scale = 0) var basePrice: java.math.BigDecimal? = null,
    @Column(name = "trading_unit_normal") var tradingUnitNormal: Int? = null,
    @Column(name = "trading_unit_overtime") var tradingUnitOvertime: Int? = null,
    @Column(name = "prev_volume") var prevVolume: Long? = null,
    @Column(name = "face_value", precision = 15, scale = 0) var faceValue: java.math.BigDecimal? = null,
    @Column(name = "listed_shares") var listedShares: Long? = null,
    @Column(name = "capital", precision = 25, scale = 0) var capital: java.math.BigDecimal? = null,
    @Column(name = "settlement_month") var settlementMonth: Short? = null,
    @Column(name = "public_offering_price", precision = 10, scale = 0) var publicOfferingPrice: java.math.BigDecimal? = null,
    @Column(name = "market_cap_prev") var marketCapPrev: Long? = null,
    @Column(name = "sales_amount") var salesAmount: Long? = null,
    @Column(name = "operating_profit") var operatingProfit: Long? = null,
    @Column(name = "ordinary_profit") var ordinaryProfit: Long? = null,
    @Column(name = "net_income") var netIncome: Long? = null,
    @Column(name = "roe", precision = 7, scale = 2) var roe: java.math.BigDecimal? = null,
    @Column(name = "financial_base_date") var financialBaseDate: LocalDate? = null,
    @Column(name = "credit_limit_exceeded", nullable = false) var creditLimitExceeded: Boolean = false,
    @Column(name = "collateral_lendable", nullable = false) var collateralLendable: Boolean = false,
    @Column(name = "short_lendable", nullable = false) var shortLendable: Boolean = false,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(code = "", name = "", market = "UNKNOWN", type = "UNKNOWN")
}
```

**핵심**:
- 기존 14 필드 그대로 유지 (변경 0)
- 신규 70 필드 add-only — `var` 사용 (sync 시 update 가능)
- DEFAULT 처리 — Boolean은 `false`, 기타는 `nullable`
- protected constructor 정합 (JPA reflection)
- `@JdbcTypeCode(SqlTypes.JSON)` 미사용 (JSONB 회피)

### 5-3. ConsumerInstrumentJpaEntity·BatchInstrumentJpaEntity 정합

```bash
$ find . -name "*Instrument*JpaEntity*.kt" | grep -v test
api/.../persistence/InstrumentJpaEntity.kt           # 본 PR-21 보강 대상 (backend-core)
batch/.../persistence/BatchInstrumentJpaEntity.kt    # batch read 전용 — 본 PR-21에서 read에 필요한 필드만 보강 (market-data가 보강 — 영역 분배)
consumer/.../persistence/ConsumerInstrumentJpaEntity.kt  # consumer read 전용 — 본 PR-21에서 read 필요 필드만 (market-data가 보강)
```

**영역 분배 LOCK**:
- **api 모듈 InstrumentJpaEntity** — backend-core 단독 (모든 70+ 컬럼 매핑 + Repository 활용)
- **batch 모듈 BatchInstrumentJpaEntity** — market-data 단독 (sync write 시 활용 + 추가 컬럼 보강)
- **consumer 모듈 ConsumerInstrumentJpaEntity** — market-data 단독 (read 위주 — 필요 컬럼만 보강 검토)

### 5-4. domain 레이어 — Instrument 데이터 클래스 변경 0

```kotlin
// domain/.../instrument/Instrument.kt — 변경 0 (architect 결정 — domain simplicity 우선)
```

**근거**: 70+ 필드 흡수가 도메인 모델을 부풀리면 다른 모듈(consumer·batch)에서 사용 부담 증가. 도메인 모델은 식별·시장·type만 (현재 그대로). KIS-first 70+ 필드는 **JPA entity layer에서 영속**되며, 필요 시 backend-core가 Repository에서 직접 조회 (도메인 모델 통과 없이 entity → response DTO 직변환). T-IND·T-DSL 진입 시 도메인 모델 보강 검토 (post-MVP).

---

## §6. KisInstrumentMasterAdapter 파싱 본격 LOCK ★ — market-data 영역

### 6-1. 위치

`batch/.../infrastructure/kis/KisInstrumentMasterAdapter.kt` — market-data 단독 modify-own (PR-11a + PR-20 hotfix 가동 중인 코드).

### 6-2. 파싱 매트릭스 — Python 파서 width 배열 1:1 Kotlin 변환

**KIS-first 출처 인용 의무** (sub-agent 코드 inline 주석에 raw URL 명시):
- `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py`
- `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py`
- `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스피).h`
- `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스닥).h`

### 6-3. 파싱 코드 골격 (LOCK)

```kotlin
// PR-21 본격 보강 — KIS open-trading-api raw 검증 정합 (pr-21-kis-header-raw-verification.md)
// KOSPI Part2 widths (70 필드, Python 파서 L54~L68 정합)
private val KOSPI_PART2_WIDTHS = intArrayOf(
    2, 1, 4, 4, 4,           // scrt_grp_cls, avls_scal, bstp_larg/medm/smal
    1, 1, 1, 1, 1,           // mnin, low_current, sprn_strr, kospi200_apnt, kospi100
    1, 1, 1, 1, 1,           // kospi50, krx, etp_prod, elw_pblc, krx100
    1, 1, 1, 1, 1,           // krx_car, krx_smcn, krx_bio, krx_bank, etpr_undt(SPAC)
    1, 1, 1, 1, 1,           // krx_enrg_chms, krx_stel, short_over, krx_medi_cmnc, krx_cnst
    1, 1, 1, 1, 1,           // krx_fnnc_svc(dummy), krx_scrt, krx_ship, krx_insu, krx_trnp
    1, 9, 5, 5, 1,           // sri_nmix, stck_sdpr, frml_mrkt_deal, ovtm_mrkt_deal, trht
    1, 1, 2, 1, 1,           // sltr, mang, mrkt_alrm, mrkt_alrm_risk_adnt, insn_pbnt
    1, 2, 2, 2, 3,           // byps_lstn, flng, fcam_mod, icic, marg_rate
    1, 3, 12, 12, 8,         // crdt_able, crdt_days, prdy_vol, stck_fcam, stck_lstn_date
    15, 21, 2, 7, 1,         // lstn_stcn, cpfn, stac_month, po_prc, prst_cls
    1, 1, 1, 1, 9,           // ssts_hot, stange_runup, krx300, kospi, sale_account
    9, 9, 5, 9, 8,           // bsop_prfi, op_prfi, thtr_ntin, roe, base_date
    9, 3, 1, 1, 1            // prdy_avls_scal, grp_code, co_crdt_limt_over, secu_lend_able, stln_able
)

// KOSDAQ Part2 widths (64 필드, Python 파서 L54~L68 정합)
private val KOSDAQ_PART2_WIDTHS = intArrayOf(
    2, 1,                    // scrt_grp_cls, avls_scal
    4, 4, 4, 1, 1,           // bstp_larg/medm/smal, vntr_issu, low_current
    1, 1, 1, 1, 1,           // krx, etp_prod, krx100, krx_car, krx_smcn
    1, 1, 1, 1, 1,           // krx_bio, krx_bank, etpr_undt, krx_enrg_chms, krx_stel
    1, 1, 1, 1, 1,           // short_over, krx_medi_cmnc, krx_cnst, invt_alrm, krx_scrt
    1, 1, 1, 1, 9,           // krx_ship, krx_insu, krx_trnp, ksq150_nmix, stck_sdpr
    5, 5, 1, 1, 1,           // frml_mrkt_deal, ovtm_mrkt_deal, trht, sltr, mang
    2, 1, 1, 1, 2,           // mrkt_alrm, mrkt_alrm_risk_adnt, insn_pbnt, byps_lstn, flng
    2, 2, 3, 1, 3,           // fcam_mod, icic, marg_rate, crdt_able, crdt_days
    12, 12, 8, 15, 21,       // prdy_vol, stck_fcam, stck_lstn_date, lstn_stcn, cpfn
    2, 7, 1, 1, 1,           // stac_month, po_prc, prst_cls, ssts_hot, stange_runup
    1, 9, 9, 9, 5,           // krx300, sale_account, bsop_prfi, op_prfi, thtr_ntin
    9, 8, 9, 3, 1,           // roe, base_date, prdy_avls_scal, grp_code, co_crdt_limt_over
    1, 1                     // secu_lend_able, stln_able
)

private const val KOSPI_PART2_LENGTH = 227   // sum of KOSPI_PART2_WIDTHS
private const val KOSDAQ_PART2_LENGTH = 221  // sum of KOSDAQ_PART2_WIDTHS

private fun parseLine(line: String, market: InstrumentMarket): KisInstrumentRaw? {
    // PR-20 byte-단위 정합 그대로 — line은 cp949(EUC-KR)로 디코딩된 String
    val lineBytes = line.toByteArray(EUC_KR)
    val part2Length = if (market == InstrumentMarket.KOSPI) KOSPI_PART2_LENGTH else KOSDAQ_PART2_LENGTH
    if (lineBytes.size < 21 + part2Length) return null  // 최소 part1(21) + part2

    // Part1: byte 단위 (PR-20 정합)
    val rawCode = String(lineBytes, 0, 9, EUC_KR).trim()
    val code = rawCode.take(6).padStart(6, '0').takeIf { it.length == 6 && it.all(Char::isDigit) } ?: return null
    val isin = String(lineBytes, 9, 12, EUC_KR).trim().ifBlank { null }
    val nameBytes = lineBytes.size - part2Length - 21
    val name = String(lineBytes, 21, nameBytes, EUC_KR).trim()
    if (name.isBlank()) return null

    // Part2: width 배열로 substring (cp949 char 단위 OK — Part2는 ASCII numeric/code)
    val part2Start = 21 + nameBytes
    val widths = if (market == InstrumentMarket.KOSPI) KOSPI_PART2_WIDTHS else KOSDAQ_PART2_WIDTHS
    val parts = mutableListOf<String>()
    var offset = part2Start
    for (w in widths) {
        parts.add(String(lineBytes, offset, w, EUC_KR).trim())
        offset += w
    }

    return if (market == InstrumentMarket.KOSPI) {
        parseKospiPart2(code, isin, name, parts)
    } else {
        parseKosdaqPart2(code, isin, name, parts)
    }
}

private fun parseKospiPart2(code: String, isin: String?, name: String, p: List<String>): KisInstrumentRaw {
    return KisInstrumentRaw(
        code = code, isin = isin, name = name, market = InstrumentMarket.KOSPI,
        // Tier 1
        securitiesGroup = p[0].ifBlank { null },
        type = mapType(p[0], p.getOrNull(54)),  // scrt_grp_cls + prst_cls
        // Tier 2 매매 가드
        tradingHalt = p[34] == "Y",
        liquidation = p[35] == "Y",
        managementStock = p[36] == "Y",
        marketAlert = p[37].ifBlank { "00" },
        marketAlertWarning = p[38] == "Y",
        falseDisclosure = p[39] == "Y",
        backdoorListing = p[40] == "Y",
        shortOverheated = p[55] == "Y",
        abnormalRunup = p[56] == "Y",
        investmentCaution = false,  // KOSDAQ 단독 — KOSPI에서는 항상 false
        exRightsType = p[41].ifBlank { "00" },
        faceValueChange = p[42].ifBlank { "00" },
        capitalIncrease = p[43].ifBlank { "00" },
        creditAble = p[45] == "Y",
        creditDays = p[46].toShortOrNull(),
        marginRate = p[44].toShortOrNull(),
        shortOverheatedStatus = p[22].ifBlank { "0" },
        // Tier 3 분류
        avlsScale = p[1].ifBlank { null },
        industryLarge = p[2].ifBlank { null },
        industryMedium = p[3].ifBlank { null },
        industrySmall = p[4].ifBlank { null },
        isManufacturing = p[5] == "Y",
        isLowLiquidity = p[6] == "Y",
        isGovernanceIndex = p[7] == "Y",
        kospi200Sector = p[8].ifBlank { null },
        isKospi100 = p[9] == "Y",
        isKospi50 = p[10] == "Y",
        isKrx = p[11] == "Y",
        etpProductType = p[12].ifBlank { null },
        isElwPublisher = p[13] == "Y",
        isKrx100 = p[14] == "Y",
        krxSectorCar = p[15] == "Y",
        krxSectorSemicon = p[16] == "Y",
        krxSectorBio = p[17] == "Y",
        krxSectorBank = p[18] == "Y",
        isSpac = p[19] == "Y",
        krxSectorEnergyChem = p[20] == "Y",
        krxSectorSteel = p[21] == "Y",
        krxSectorMediaComm = p[23] == "Y",
        krxSectorConstruction = p[24] == "Y",
        // p[25] = krx_fnnc_svc dummy — 무시
        krxSectorSecurities = p[26] == "Y",
        krxSectorShip = p[27] == "Y",
        krxSectorInsurance = p[28] == "Y",
        krxSectorTransport = p[29] == "Y",
        isSriIndex = p[30] == "Y",
        preferredStockType = p[54].ifBlank { null },
        isKrx300 = p[57] == "Y",
        isKospi200 = p[8].isNotBlank() && p[8] != "0",  // derived from kospi200_apnt_cls_code
        isKosdaq150 = false,
        isVenture = false,
        // Tier 4 시장 메타 + 재무
        basePrice = p[31].toBigDecimalOrNull(),
        tradingUnitNormal = p[32].toIntOrNull(),
        tradingUnitOvertime = p[33].toIntOrNull(),
        prevVolume = p[47].toLongOrNull(),
        faceValue = p[48].toBigDecimalOrNull(),
        listedAt = parseYyyymmdd(p[49]),  // stck_lstn_date
        listedShares = p[50].toLongOrNull(),
        capital = p[51].toBigDecimalOrNull(),
        settlementMonth = p[52].toShortOrNull(),
        publicOfferingPrice = p[53].toBigDecimalOrNull(),
        salesAmount = p[58].toLongOrNull(),
        operatingProfit = p[59].toLongOrNull(),
        ordinaryProfit = p[60].toLongOrNull(),
        netIncome = p[61].toLongOrNull(),
        roe = p[62].toBigDecimalOrNull(),
        financialBaseDate = parseYyyymmdd(p[63]),
        marketCapPrev = p[64].toLongOrNull(),
        groupCode = p[65].ifBlank { null },
        creditLimitExceeded = p[66] == "Y",
        collateralLendable = p[67] == "Y",
        shortLendable = p[68] == "Y",
    )
}

// KOSDAQ는 위치가 다름 — 별도 mapping 함수 (parseKosdaqPart2)
// 핵심 차이:
//  KOSDAQ는 mnin·sprn·kospi200_apnt·kospi100·kospi50 부재(5필드 빠짐)
//  KOSDAQ에 vntr_issu_yn·invt_alrm_yn·ksq150_nmix_yn 단독(3필드 추가)
//  → KOSDAQ part2 widths가 64개, KOSPI는 70개

private fun mapType(scrtGrpClsCode: String, prstClsCode: String?): InstrumentType =
    when (scrtGrpClsCode) {
        "ST" -> if (prstClsCode == "1" || prstClsCode == "2") InstrumentType.PREFERRED else InstrumentType.STOCK
        "EF", "FE" -> InstrumentType.ETF
        "MF", "BC", "SC", "IF" -> InstrumentType.STOCK
        "RT" -> InstrumentType.REIT
        "DR" -> InstrumentType.DR  // 신규 enum
        "EW", "SW", "SR" -> InstrumentType.WARRANT
        "FS" -> InstrumentType.STOCK  // 외국주권
        else -> InstrumentType.UNKNOWN
    }

private fun parseYyyymmdd(s: String): LocalDate? {
    if (s.length != 8 || !s.all(Char::isDigit) || s == "00000000") return null
    return try {
        LocalDate.of(s.substring(0, 4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt())
    } catch (e: Exception) { null }
}
```

**핵심 LOCK**:
1. **Python 파서 width 배열 1:1 변환** — `KOSPI_PART2_WIDTHS` 70 항목 + `KOSDAQ_PART2_WIDTHS` 64 항목, 합계 byte 정합 검증
2. **byte-단위 substring 유지** (PR-20 정합) — Part2도 byte 단위 (cp949 ASCII numeric은 1byte 1char라 char 단위 가능하지만 안전하게 byte 단위)
3. **scrt_grp_cls_code 권위 매핑** — `mapType()` 자체 추론 폐기, 헤더 정의 13종 매핑
4. **prst_cls_code로 우선주 판별** — 코드 5번째 자리 휴리스틱 폐기
5. **Y/N → Boolean** 일관 변환
6. **sale_account·roe 등 음수 표기 안전**: BigDecimal 변환 (precision 9·소수 2자리)
7. **listed_at parsing 본격** — yyyymmdd → LocalDate (이전 PR-11에서는 null 처리)

### 6-4. KisInstrumentRaw 도메인 모델 신규 (consumer 모듈)

`consumer/.../infrastructure/kis/KisInstrumentRaw.kt` 또는 batch 모듈로 이동(현재 batch에 KisInstrumentMasterAdapter 가동 중) — 80+ 필드 data class.

```kotlin
// batch/.../infrastructure/kis/KisInstrumentRaw.kt — 신규 또는 보강
data class KisInstrumentRaw(
    val code: String,
    val isin: String?,
    val name: String,
    val market: InstrumentMarket,
    val type: InstrumentType,
    val securitiesGroup: String?,
    // Tier 2
    val tradingHalt: Boolean,
    val liquidation: Boolean,
    // ... 70+ 필드
    val listedAt: LocalDate?,
)
```

**또는** `Instrument` 도메인 클래스 보강 검토 — architect 결정: **신규 `KisInstrumentRaw` data class** (Instrument 도메인 simplicity 유지). InstrumentSyncJob이 KisInstrumentRaw → InstrumentJpaEntity 변환 시 모든 필드 매핑.

### 6-5. InstrumentSyncJob.diff() 보강

```kotlin
// batch/.../job/instrument/InstrumentSyncJob.kt — 보강
private fun upsertEntity(raw: KisInstrumentRaw, existing: InstrumentJpaEntity?): InstrumentJpaEntity {
    return if (existing != null) {
        // UPDATE — 모든 70+ 필드 변경 가능 (sync마다 KIS 최신 반영)
        existing.apply {
            // Tier 1
            isin = raw.isin
            name = raw.name
            type = raw.type.name
            securitiesGroup = raw.securitiesGroup
            // Tier 2 매매 가드 — 매 sync 갱신 (거래정지·관리종목 변동 추적)
            tradingHalt = raw.tradingHalt
            liquidation = raw.liquidation
            // ... 70+ 필드 모두 갱신
            updatedAt = Instant.now()
        }
    } else {
        // INSERT — 모든 필드 fresh
        InstrumentJpaEntity(/* 모든 필드 */)
    }
}
```

---

## §7. 종목 type 분류 정정 (`detectType` 폐기)

### 7-1. 폐기 대상 — 자체 추론 lens

```kotlin
// 폐기 — KisInstrumentMasterAdapter.detectType(name, code)
// 이름 substring("KODEX"·"TIGER"·"ETN" 등) 기반 추측
```

### 7-2. 정정 — KIS-first

```kotlin
// 신규 — mapType(scrtGrpClsCode, prstClsCode) (§6-3 LOCK 정합)
// scrt_grp_cls_code 13종 직접 매핑
```

### 7-3. enum 보강 (`InstrumentType`)

```kotlin
// domain/.../instrument/Instrument.kt — InstrumentType enum 보강
enum class InstrumentType {
    STOCK, ETF, ETN, PREFERRED, WARRANT, REIT,
    DR,           // 신규 — 주식예탁증서 (KIS scrt_grp_cls=DR)
    UNKNOWN,
}
```

**add-only enum value** — 기존 코드 호환 보장.

### 7-4. CHECK 보강 (V5 정합)

V5에서 `chk_instrument_type` 정정 (§4-2 SQL 정합) — 'DR' 추가.

---

## §8. KOSDAQ 차이 흡수

### 8-1. 통합 entity 전략

- KOSPI 단독 9 필드 + KOSDAQ 단독 3 필드 = 모두 nullable 컬럼 (§3-2~§3-5 정합)
- parser가 market 따라 다른 byte offset 사용 (§6-3 widths 배열 분리)
- DB row는 통합 instrument 테이블 (market='KOSPI'·'KOSDAQ'·'KONEX'로 구분)

### 8-2. 검증

- KOSPI 종목 row → `is_kospi100·is_kospi50·is_kospi200·kospi200_sector·is_governance_index·is_manufacturing·is_sri_index·is_elw_publisher` 채워짐 / `is_kosdaq150·is_venture·investment_caution` = false
- KOSDAQ 종목 row → 반대 패턴

### 8-3. 1차 비범위

- KONEX 종목 마스터 — KIS 미지원 (KOSPI/KOSDAQ만). 본 PR-21 비범위 (UNKNOWN 처리). post-MVP 검토.

---

## §9. 사용자 dogfooding 결함 흡수 + 추가 노출 검토

### 9-1. PR-20 하위 흡수 (이미 처리)

- byte 파싱 정정 ✅
- watchlist 종목명 표시 ✅

### 9-2. PR-21 추가 노출 후보 (backend-core 작업)

| Watchlist 응답 보강 | 핵심 사유 |
|---|---|
| name (있음) | PR-20 기처리 |
| market (있음) | PR-20 기처리 |
| **type** (신규) | 사용자가 ETF/우선주/REIT 식별 가능 |
| **market_cap_prev** (시총) | watchlist row에 시총 표시 (사용자 직관) |
| **trading_halt·management_stock** | 매매 위험 가드 노출 (사용자가 즉시 인지) |
| **kospi200_sector·industry_medium** | 업종 컬럼 (사용자 친화) |

**LOCK**: WatchlistService 응답 DTO `WatchlistEntryDto`에 추가 필드 6개 신규 (backend-core 단독). frontend WatchlistRow.tsx는 본 PR-21 비범위 (UI 변경은 별도 사이클 — 룰 2 단일 책임).

### 9-3. SymbolDetailPage 헤더 보강 후보

`/symbol/{code}` API 응답(SymbolMetaService)에 type·KIS 메타 보강 — backend-core 단독. 본 PR-21에서 응답 schema만 보강, UI 표시는 별도 사이클.

### 9-4. backlog §6 dogfooding §6.4 (사용자 미루기)

사용자 메모리 정합 — SymbolDetail 페이지 내부 동작은 본 PR-21 비범위 (UI 변경 없이 데이터 모델만).

---

## §10. 비범위 (post-MVP 후보)

| 항목 | 사유 | 후속 트랙 |
|---|---|---|
| 재무 (Tier 4 sales/profit/roe) UI 표시 | T-IND 진입 시 fundamental screening | T-IND |
| KONEX 종목 마스터 | KIS 미제공 | post-MVP (KRX 직접) |
| frontend Watchlist/SymbolDetail UI 변경 | 룰 2 단일 책임 | 별도 사이클 |
| instrument_alias NAVER·FNGUIDE source | KIS 미제공 — 외부 source | post-MVP |
| KIS WS 종목 마스터 변경 streaming | 본 시스템은 cron sync만 (ADR 0039) | post-MVP |
| 일별 갱신 cron 보강 | ADR 0039 13 cron 가동 중 | OK (현재 정합) |

---

## §11. 트레이드오프 (architect 자율 결정 사유)

| 결정 | 채택 | 대안 | 사유 |
|---|---|---|---|
| 컬럼 70 추가 vs JSONB 1 컬럼 | **70 컬럼 분리** | JSONB | 인덱스 효율 (Tier 2 매매 가드는 부분 인덱스 핵심) + screening DSL 가독성↑ + PostgreSQL row 부담 무시 (1byte each + null bitmap) |
| KRX 섹터 14종 BITMAP vs BOOLEAN 14 | **BOOLEAN 14** | BITMAP integer 1 | 인덱스 직관성 (`WHERE krx_sector_bio = true`) + Kotlin entity 가독성. BITMAP은 사용자 검색 DSL 작성 시 불편 |
| domain `Instrument` 보강 vs entity-only | **entity-only** | domain 70+ 필드 | 도메인 simplicity 유지 — consumer·batch에서 사용 부담 차단. T-IND 진입 시 도메인 보강 검토 |
| Tier 4 (재무) 본 PR-21 포함 vs 분리 | **포함** | V5 → V6 분리 | ALTER TABLE 한 번 + nullable이라 비용 무시. 분리 시 sync 코드를 두 번 보강해야 함 |
| `KisInstrumentRaw` 신규 vs `Instrument` 보강 | **신규** | Instrument 보강 | 도메인 모델 simplicity. raw는 KIS 입력 layer에 한정 |
| InstrumentType enum 'DR' 추가 vs 'STOCK' 통합 | **'DR' 추가** | STOCK 묶음 | 주식예탁증서는 분기 행동 다름(주식과 다른 권리). 명시 |
| `var` vs `val` (entity 신규 필드) | **var** | val | sync마다 update 필요 (거래정지·시장경고 변동) |
| CHECK constraint 8건 추가 vs 미추가 | **추가** | DB 신뢰만 | 데이터 무결성 보장 (KIS 응답 변형 시 빠른 발견) |
| 인덱스 8건 추가 vs 0건 | **추가** | 0건 | screening·매매 가드 빈출 — 부분 인덱스로 부담 최소 |

---

## §12. 발견 함정 + 위험

### 12-1. KOSPI/KOSDAQ part2 width 배열 mismatch ★

**risk**: 70 KOSPI vs 64 KOSDAQ widths 차이로 sub-agent가 width 배열 잘못 사용 → 모든 필드 offset shift → 데이터 깨짐.

**처리**: §6-3 LOCK widths 배열 그대로 코드에 반영 + smoke test 1건 (KOSPI 실 종목 005930 + KOSDAQ 035720 필드 검증).

### 12-2. Part1 한글명 가변 길이 ★

**risk**: 종목명이 한글 5자(10byte) vs 20자(40byte)로 다름. PR-20에서 정정한 `lineBytes.size - 228` 계산 KOSPI / `- 222` KOSDAQ 정합 의무.

**처리**: §6-3 코드에 `nameBytes = lineBytes.size - part2Length - 21` 명시.

### 12-3. yyyymmdd "00000000" 처리

**risk**: stck_lstn_date·base_date 미상장/미공개 종목에서 "00000000" 또는 공백 → DATE 변환 실패.

**처리**: §6-3 `parseYyyymmdd()` 헬퍼에서 "00000000" 검출 시 null 반환.

### 12-4. roe 음수 표기

**risk**: roe (자기자본이익률)는 음수 가능 (예: "-15.30"). KIS는 9byte 문자열 (부호 + 숫자 + 소수점).

**처리**: `BigDecimal(s)` 직접 변환 (Kotlin `toBigDecimalOrNull` 음수 부호 지원).

### 12-5. Flyway V5 이미 사용 중인지 검증

**risk**: V5 번호 충돌 — 본 worktree에 V1·V2·V3·V4만 있음 (확인 완료).

**처리**: V5 신규 발행 OK. PR-21a sub-agent 검증 의무.

### 12-6. 정적 시드 fallback 데이터 부족

**risk**: 정적 시드 JSON(KOSPI 200·KOSDAQ 100·ETF 20)에는 70+ 필드 0건. KIS 미가용 환경에서 시드 row는 거래정지·시총 등 부재.

**처리**: 시드 row는 Tier 1만 채움 (code·name·market·type·sector·listedAt). Tier 2~4 모두 default false/null. 사용자 흐름에서 mock 합성 0 (`feedback_no_mock_data.md` 정합 — 시드는 시스템 부팅 path만, 사용자 path는 KIS sync 결과 기준).

### 12-7. KIS 응답 컬럼 추가/변경 risk

**risk**: KIS open-trading-api가 향후 컬럼 추가 시 width 배열 미정합 → 파싱 깨짐.

**처리**: KIS 응답 검증 로직 (smoke test에 005930 시총·거래정지 등 알려진 값 검증) + 운영 함정 가드 (sync 후 row count 급변 알림 — post-MVP).

### 12-8. PR-11 spec V4 결정 누락 흔적

PR-11 spec §3-2에서 V4 결정 시 KIS 헤더 정독 0건 → 본 PR-21에서 정정. **architect SKILL 강화 의무** (§16) — KIS-first raw fetch 의무 명시.

### 12-9. ConsumerInstrumentJpaEntity·BatchInstrumentJpaEntity 정합

**risk**: 같은 instrument 테이블을 3개 entity가 매핑 (api·batch·consumer). 신규 70 컬럼 추가 시 entity 3개 모두 보강 필요? 또는 read-only 모듈은 부분 매핑?

**처리**: 영역 분배 LOCK — api 모듈 entity는 backend-core 단독(전체 매핑), batch·consumer 모듈 entity는 market-data 단독(필요 컬럼만 보강 — sync write에 필요한 필드 범위). 각 entity는 read 시 미매핑 컬럼은 무시하므로 호환.

### 12-10. backlog §11 SymbolControllerTest mock 정합

본 PR-21은 instrument 테이블 보강이라 SymbolControllerTest와 직접 충돌 0. backlog §11 별도 처리.

---

## §13. 사용자 응답 — 비판 인정 + 재발 방지

### 13-1. 비판 인정

> "kis 에서 제공받는 데이터 규격 확인 안하고 그냥 막 테이블 만들어서 꾸겨넣은거잖아"

**인정합니다.** PR-11 spec §3-2 V4 결정 시 KIS 헤더 raw 정독 누락 → 14 컬럼 자체 추론. 정확한 지적이며, 사용자 메모리 `feedback_kis_first_policy.md`·`feedback_v3_skepticism_no_regression.md` 위반.

### 13-2. 재발 방지

1. **본 PR-21에서 fitness gap 91.7% → 13% 축소** (§3·§4 LOCK)
2. **architect SKILL 강화** (§16) — spec 산출 시 외부 데이터 규격 raw fetch 의무 명시
3. **KIS-first raw fetch 의무 ADR/SKILL 보강** — 향후 모든 sub-agent에게도 동일 의무
4. **본 PR-21 산출물(`pr-21-kis-header-raw-verification.md`) baseline** — KIS 헤더 raw 검증 보고서가 시스템 SoT (KOSPI 73 + KOSDAQ 67 + 차이 14건 100% 인용)

### 13-3. 향후 동일 패턴 방지

- 새 KIS endpoint 사용 시 → KIS open-trading-api 레포 raw fetch 의무 (architect SKILL §보강)
- 새 외부 API 사용 시 → 공식 문서/스키마 raw fetch 의무 (orchestrator SKILL 정합)
- 자체 추론 0 정책 — sub-agent 디스패치 명세에 명시 의무

---

## §14. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | PR-21 instrument 테이블 KIS-first 본격 재설계 v1 발행. 사용자 비판 인정 + fitness gap 91.7% 정량화 + KIS 헤더 raw 검증 보고서 baseline + V5 마이그레이션 LOCK(70 컬럼 add-only ALTER + 8 CHECK + 8 인덱스) + InstrumentJpaEntity 보강 LOCK(83 필드 entity) + KisInstrumentMasterAdapter 본격 파싱 LOCK(KOSPI 70 widths + KOSDAQ 64 widths Python 1:1 정합) + scrt_grp_cls_code 권위 매핑 13종 + InstrumentType 'DR' enum 신규 + KOSPI/KOSDAQ 차이 14건 통합 entity 흡수 + 트레이드오프 9건 명시. (architect, opus 4.7 1M) |
