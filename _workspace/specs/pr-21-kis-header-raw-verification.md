# PR-21 KIS 종목 마스터 헤더 raw 검증 보고서

- 작성: architect (opus 4.7 1M)
- 작성일: 2026-05-07
- 검증 범위: KIS open-trading-api 공식 레포 KOSPI/KOSDAQ `.h` 헤더 + Python 파서(`kis_kospi_code_mst.py`·`kis_kosdaq_code_mst.py`)
- 출처:
  - `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스피).h` (raw fetch 99 lines)
  - `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/종목마스터정보(코스닥).h` (raw fetch 89 lines)
  - `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kospi_code_mst.py` (108 lines)
  - `https://github.com/koreainvestment/open-trading-api/blob/main/stocks_info/kis_kosdaq_code_mst.py` (104 lines)
- 검증 일시: 2026-05-07 (worktree에 raw fetch — `/tmp/kis-headers/{kospi,kosdaq}.h`, `/tmp/kis-headers/{kospi,kosdaq}_py.py`)
- **사용자 비판 응답 — KIS-first 정책 위반(자체 추론 의심) 정정 의무**: 본 보고서가 그 입증 산출물

---

## §1. 검증 방법

1. KIS 공식 GitHub 레포에서 raw 헤더 4 파일 fetch (URL 인코딩 + curl `-sL`)
2. `.h` typedef 구조체의 모든 필드를 byte 길이 + 순서 그대로 추출 (architect 자체 추론 0건)
3. Python 파서의 `field_specs` width 배열과 `.h` 헤더의 byte 길이를 1:1 비교 → mismatch 검출
4. KOSPI/KOSDAQ 차이 식별 (한쪽에만 존재하는 필드)
5. mst.zip 다운로드 URL은 Python 파서에서 직접 추출 (자체 추론 0건)
   - KOSPI: `https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip` (kis_kospi_code_mst.py L17)
   - KOSDAQ: `https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip` (kis_kosdaq_code_mst.py L17)
   - encoding: cp949 (Python 파서 L38 `encoding="cp949"`) — Java/Kotlin에서는 `EUC-KR` 별칭으로 호환

---

## §2. KOSPI 종목 마스터 헤더 — `.h` typedef 100% 추출 (Python 파서 width 정합)

### §2-1. Part1 (가변 길이 head) — 단축코드·표준코드·한글명

Python 파서 L40~L46에 따르면:
```python
rf1 = row[0:len(row) - 228]   # part1 = 헤더부터 -228byte까지 (가변)
rf1_1 = rf1[0:9].rstrip()     # 단축코드 9byte
rf1_2 = rf1[9:21].rstrip()    # 표준코드 12byte (= ISIN)
rf1_3 = rf1[21:].strip()      # 한글명 (가변 — 일반적으로 40byte)
rf2 = row[-228:]              # part2 = 마지막 228byte (고정)
```

| 필드 | byte 길이 | char offset (cp949 변환 후) | 의미 | 출처 |
|---|---|---|---|---|
| `mksc_shrn_iscd` | 9 | 0~9 | **단축코드** (종목코드 6자리 + 우측 공백 패딩) | `.h` L6 + py L41 |
| `stnd_iscd` | 12 | 9~21 | **표준코드 (ISIN)** | `.h` L7 + py L42 |
| `hts_kor_isnm` | (가변) | 21~ (-228) | **한글종목명** (cp949 한글 2byte) | `.h` L8 + py L43 |

**중요**: Part1은 byte 단위 substring **불필요** — Python 파서가 cp949로 디코딩 후 char 인덱스로 슬라이싱. Java/Kotlin 동일 패턴 가능. 단 한글명은 가변 길이라 `len(row) - 228`로 자동 계산.

### §2-2. Part2 (고정 길이 tail 228byte) — Python 파서 width 배열 70개 정합

Python 파서 L54~L68 `field_specs` (총 70 필드, sum=227byte + 줄바꿈 1byte = 228byte):

```python
field_specs = [2, 1, 4, 4, 4,           # 5
               1, 1, 1, 1, 1,           # 10
               1, 1, 1, 1, 1,           # 15
               1, 1, 1, 1, 1,           # 20
               1, 1, 1, 1, 1,           # 25
               1, 1, 1, 1, 1,           # 30
               1, 9, 5, 5, 1,           # 35
               1, 1, 2, 1, 1,           # 40
               1, 2, 2, 2, 3,           # 45
               1, 3, 12, 12, 8,         # 50
               15, 21, 2, 7, 1,         # 55
               1, 1, 1, 1, 9,           # 60
               9, 9, 5, 9, 8,           # 65
               9, 3, 1, 1, 1            # 70
              ]
```

| # | 필드 (`.h` 변수명) | width | Part2 char offset | 의미 (헤더 주석) | Python 파서 컬럼명 |
|---|---|---|---|---|---|
| 1 | `scrt_grp_cls_code` | 2 | 0~2 | 증권그룹구분코드 (ST·MF·RT·SC·IF·DR·EW·EF·SW·SR·BC·FE·FS) | 그룹코드 |
| 2 | `avls_scal_cls_code` | 1 | 2~3 | 시가총액 규모 (0:제외 1:대 2:중 3:소) | 시가총액규모 |
| 3 | `bstp_larg_div_code` | 4 | 3~7 | 지수업종 대분류 | 지수업종대분류 |
| 4 | `bstp_medm_div_code` | 4 | 7~11 | 지수업종 중분류 | 지수업종중분류 |
| 5 | `bstp_smal_div_code` | 4 | 11~15 | 지수업종 소분류 | 지수업종소분류 |
| 6 | `mnin_cls_code_yn` | 1 | 15~16 | 제조업 (Y/N) | 제조업 |
| 7 | `low_current_yn` | 1 | 16~17 | 저유동성종목 | 저유동성 |
| 8 | `sprn_strr_nmix_issu_yn` | 1 | 17~18 | 지배구조지수 종목 | 지배구조지수종목 |
| 9 | `kospi200_apnt_cls_code` | 1 | 18~19 | KOSPI200 섹터 (0:해당없음 1:건설 2:중공업 3:철강 4:에너지화학 5:정보통신 6:금융 7:필수소비재 8:자유소비재 9:산업재 A:건강관리 B:커뮤니케이션) | KOSPI200섹터업종 |
| 10 | `kospi100_issu_yn` | 1 | 19~20 | KOSPI100 | KOSPI100 |
| 11 | `kospi50_issu_yn` | 1 | 20~21 | KOSPI50 | KOSPI50 |
| 12 | `krx_issu_yn` | 1 | 21~22 | KRX 종목 | KRX |
| 13 | `etp_prod_cls_code` | 1 | 22~23 | ETP 상품 (0:해당없음 1:투자회사형 2:수익증권형 3:ETN 4:손실제한ETN 5:상장형수익증권) | ETP |
| 14 | `elw_pblc_yn` | 1 | 23~24 | ELW 발행 | ELW발행 |
| 15 | `krx100_issu_yn` | 1 | 24~25 | KRX100 | KRX100 |
| 16 | `krx_car_yn` | 1 | 25~26 | KRX 자동차 | KRX자동차 |
| 17 | `krx_smcn_yn` | 1 | 26~27 | KRX 반도체 | KRX반도체 |
| 18 | `krx_bio_yn` | 1 | 27~28 | KRX 바이오 | KRX바이오 |
| 19 | `krx_bank_yn` | 1 | 28~29 | KRX 은행 | KRX은행 |
| 20 | `etpr_undt_objt_co_yn` | 1 | 29~30 | 기업인수목적회사 (SPAC) | SPAC |
| 21 | `krx_enrg_chms_yn` | 1 | 30~31 | KRX 에너지화학 | KRX에너지화학 |
| 22 | `krx_stel_yn` | 1 | 31~32 | KRX 철강 | KRX철강 |
| 23 | `short_over_cls_code` | 1 | 32~33 | 단기과열 (0:해당없음 1:지정예고 2:지정 3:지정연장) | 단기과열 |
| 24 | `krx_medi_cmnc_yn` | 1 | 33~34 | KRX 미디어통신 | KRX미디어통신 |
| 25 | `krx_cnst_yn` | 1 | 34~35 | KRX 건설 | KRX건설 |
| 26 | `krx_fnnc_svc_yn` | 1 | 35~36 | (삭제됨 20151218 — Python 파서 'Non1'으로 dummy 보존) | Non1 |
| 27 | `krx_scrt_yn` | 1 | 36~37 | KRX 증권 | KRX증권 |
| 28 | `krx_ship_yn` | 1 | 37~38 | KRX 선박 | KRX선박 |
| 29 | `krx_insu_yn` | 1 | 38~39 | KRX 보험 | KRX섹터_보험 |
| 30 | `krx_trnp_yn` | 1 | 39~40 | KRX 운송 | KRX섹터_운송 |
| 31 | `sri_nmix_yn` | 1 | 40~41 | SRI 지수 | SRI |
| 32 | `stck_sdpr` | 9 | 41~50 | 주식 기준가 (정수 9자리) | 기준가 |
| 33 | `frml_mrkt_deal_qty_unit` | 5 | 50~55 | 정규시장 매매수량단위 | 매매수량단위 |
| 34 | `ovtm_mrkt_deal_qty_unit` | 5 | 55~60 | 시간외 매매수량단위 | 시간외수량단위 |
| 35 | `trht_yn` | 1 | 60~61 | 거래정지 | 거래정지 |
| 36 | `sltr_yn` | 1 | 61~62 | 정리매매 | 정리매매 |
| 37 | `mang_issu_yn` | 1 | 62~63 | 관리종목 | 관리종목 |
| 38 | `mrkt_alrm_cls_code` | 2 | 63~65 | 시장경고 (00:해당없음 01:투자주의 02:투자경고 03:투자위험) | 시장경고 |
| 39 | `mrkt_alrm_risk_adnt_yn` | 1 | 65~66 | 시장경고위험 예고 | 경고예고 |
| 40 | `insn_pbnt_yn` | 1 | 66~67 | 불성실공시 | 불성실공시 |
| 41 | `byps_lstn_yn` | 1 | 67~68 | 우회상장 | 우회상장 |
| 42 | `flng_cls_code` | 2 | 68~70 | 락구분 (00:해당없음 01:권리락 02:배당락 03:분배락 04:권배락 05:중간배당락 06:권리중간배당락 99:기타) | 락구분 |
| 43 | `fcam_mod_cls_code` | 2 | 70~72 | 액면가변경 (00:해당없음 01:액면분할 02:액면병합 99:기타) | 액면변경 |
| 44 | `icic_cls_code` | 2 | 72~74 | 증자구분 (00:해당없음 01:유상 02:무상 03:유무상 99:기타) | 증자구분 |
| 45 | `marg_rate` | 3 | 74~77 | 증거금 비율 (3자리 정수) | 증거금비율 |
| 46 | `crdt_able` | 1 | 77~78 | 신용주문 가능 | 신용가능 |
| 47 | `crdt_days` | 3 | 78~81 | 신용기간 (일) | 신용기간 |
| 48 | `prdy_vol` | 12 | 81~93 | 전일 거래량 | 전일거래량 |
| 49 | `stck_fcam` | 12 | 93~105 | 주식 액면가 | 액면가 |
| 50 | `stck_lstn_date` | 8 | 105~113 | 상장일자 (yyyymmdd) | 상장일자 |
| 51 | `lstn_stcn` | 15 | 113~128 | 상장주수 (천 단위) | 상장주수 |
| 52 | `cpfn` | 21 | 128~149 | 자본금 | 자본금 |
| 53 | `stac_month` | 2 | 149~151 | 결산월 (mm) | 결산월 |
| 54 | `po_prc` | 7 | 151~158 | 공모가 | 공모가 |
| 55 | `prst_cls_code` | 1 | 158~159 | 우선주 (0:보통주 1:구형우선주 2:신형우선주) | 우선주 |
| 56 | `ssts_hot_yn` | 1 | 159~160 | 공매도 과열 | 공매도과열 |
| 57 | `stange_runup_yn` | 1 | 160~161 | 이상급등 | 이상급등 |
| 58 | `krx300_issu_yn` | 1 | 161~162 | KRX300 | KRX300 |
| 59 | `kospi_issu_yn` | 1 | 162~163 | KOSPI 여부 | KOSPI |
| 60 | `sale_account` | 9 | 163~172 | 매출액 | 매출액 |
| 61 | `bsop_prfi` | 9 | 172~181 | 영업이익 | 영업이익 |
| 62 | `op_prfi` | 9 | 181~190 | 경상이익 | 경상이익 |
| 63 | `thtr_ntin` | 5 | 190~195 | 당기순이익 | 당기순이익 |
| 64 | `roe` | 9 | 195~204 | ROE (자기자본이익률) | ROE |
| 65 | `base_date` | 8 | 204~212 | 기준년월 | 기준년월 |
| 66 | `prdy_avls_scal` | 9 | 212~221 | 전일기준 시가총액 (억) | 시가총액 |
| 67 | `grp_code` | 3 | 221~224 | 그룹사 코드 | 그룹사코드 |
| 68 | `co_crdt_limt_over_yn` | 1 | 224~225 | 회사신용한도초과 | 회사신용한도초과 |
| 69 | `secu_lend_able_yn` | 1 | 225~226 | 담보대출가능 | 담보대출가능 |
| 70 | `stln_able_yn` | 1 | 226~227 | 대주가능 | 대주가능 |

**검증 결과 — KOSPI**:
- `.h` 헤더 typedef 필드 수 = **70개** (Part2) + Part1(단축코드·표준코드·한글명) 3개 = **총 73개**
- Python 파서 field_specs 합계 = **227 byte** (Part2) → 헤더 line 끝 줄바꿈 1byte 포함 시 228byte 정합
- mismatch **0건** — `.h`와 Python 파서 1:1 정합 검증 완료

---

## §3. KOSDAQ 종목 마스터 헤더 — `.h` typedef 100% 추출

### §3-1. Part1 (KOSPI 동일)

```python
rf1 = row[0:len(row) - 222]   # KOSDAQ는 Part2가 222byte (KOSPI 228byte보다 6byte 짧음)
```

| 필드 | byte 길이 | 의미 |
|---|---|---|
| `mksc_shrn_iscd` | 9 | 단축코드 |
| `stnd_iscd` | 12 | 표준코드 (ISIN) |
| `hts_kor_isnm` | (가변) | 한글종목명 |

### §3-2. Part2 (고정 222byte) — Python 파서 64 필드

```python
field_specs = [2, 1,                    # 2
               4, 4, 4, 1, 1,           # 7
               1, 1, 1, 1, 1,           # 12
               1, 1, 1, 1, 1,           # 17
               1, 1, 1, 1, 1,           # 22
               1, 1, 1, 1, 9,           # 27
               5, 5, 1, 1, 1,           # 32
               2, 1, 1, 1, 2,           # 37
               2, 2, 3, 1, 3,           # 42
               12, 12, 8, 15, 21,       # 47
               2, 7, 1, 1, 1,           # 52
               1, 9, 9, 9, 5,           # 57
               9, 8, 9, 3, 1,           # 62
               1, 1                     # 64
              ]
```

| # | 필드 (`.h` 변수명) | width | 의미 | KOSPI 대비 |
|---|---|---|---|---|
| 1 | `scrt_grp_cls_code` | 2 | 증권그룹구분 | 동일 |
| 2 | `avls_scal_cls_code` | 1 | 시총규모 (KOSDAQ: 0:제외 1:KOSDAQ100 2:KOSDAQmid300 3:KOSDAQsmall) | 의미 다름 — KOSPI는 대/중/소 |
| 3 | `bstp_larg_div_code` | 4 | 지수업종 대분류 | 동일 |
| 4 | `bstp_medm_div_code` | 4 | 지수업종 중분류 | 동일 |
| 5 | `bstp_smal_div_code` | 4 | 지수업종 소분류 | 동일 |
| 6 | **`vntr_issu_yn`** | 1 | **벤처기업 여부 (KOSDAQ 단독)** | **KOSPI 부재** |
| 7 | `low_current_yn` | 1 | 저유동성 | 동일 |
| 8 | `krx_issu_yn` | 1 | KRX 종목 | 동일 (KOSPI 12위, KOSDAQ는 mnin·sprn·kospi200·100·50 부재로 8위) |
| 9 | `etp_prod_cls_code` | 1 | ETP 상품 | 동일 (단 KOSDAQ는 5:상장형수익증권 명시 없음 — 헤더 주석 차이) |
| 10 | `krx100_issu_yn` | 1 | KRX100 | 동일 |
| 11 | `krx_car_yn` | 1 | KRX 자동차 | 동일 |
| 12 | `krx_smcn_yn` | 1 | KRX 반도체 | 동일 |
| 13 | `krx_bio_yn` | 1 | KRX 바이오 | 동일 |
| 14 | `krx_bank_yn` | 1 | KRX 은행 | 동일 |
| 15 | `etpr_undt_objt_co_yn` | 1 | SPAC | 동일 |
| 16 | `krx_enrg_chms_yn` | 1 | KRX 에너지화학 | 동일 |
| 17 | `krx_stel_yn` | 1 | KRX 철강 | 동일 |
| 18 | `short_over_cls_code` | 1 | 단기과열 | 동일 |
| 19 | `krx_medi_cmnc_yn` | 1 | KRX 미디어통신 | 동일 |
| 20 | `krx_cnst_yn` | 1 | KRX 건설 | 동일 |
| 21 | **`invt_alrm_yn`** | 1 | **(코스닥)투자주의환기종목 (KOSDAQ 단독)** | **KOSPI 부재 (KOSPI는 krx_fnnc_svc_yn dummy)** |
| 22 | `krx_scrt_yn` | 1 | KRX 증권 | 동일 |
| 23 | `krx_ship_yn` | 1 | KRX 선박 | 동일 |
| 24 | `krx_insu_yn` | 1 | KRX 보험 | 동일 |
| 25 | `krx_trnp_yn` | 1 | KRX 운송 | 동일 |
| 26 | **`ksq150_nmix_yn`** | 1 | **KOSDAQ150 지수 (KOSDAQ 단독)** | **KOSPI 부재 (KOSPI는 sri_nmix_yn)** |
| 27 | `stck_sdpr` | 9 | 주식 기준가 | 동일 |
| 28 | `frml_mrkt_deal_qty_unit` | 5 | 정규시장 매매수량단위 | 동일 |
| 29 | `ovtm_mrkt_deal_qty_unit` | 5 | 시간외 매매수량단위 | 동일 |
| 30 | `trht_yn` | 1 | 거래정지 | 동일 |
| 31 | `sltr_yn` | 1 | 정리매매 | 동일 |
| 32 | `mang_issu_yn` | 1 | 관리종목 | 동일 |
| 33 | `mrkt_alrm_cls_code` | 2 | 시장경고 | 동일 |
| 34 | `mrkt_alrm_risk_adnt_yn` | 1 | 시장경고위험 예고 | 동일 |
| 35 | `insn_pbnt_yn` | 1 | 불성실공시 | 동일 |
| 36 | `byps_lstn_yn` | 1 | 우회상장 | 동일 |
| 37 | `flng_cls_code` | 2 | 락구분 | 동일 |
| 38 | `fcam_mod_cls_code` | 2 | 액면가변경 | 동일 |
| 39 | `icic_cls_code` | 2 | 증자구분 | 동일 |
| 40 | `marg_rate` | 3 | 증거금 비율 | 동일 |
| 41 | `crdt_able` | 1 | 신용주문 가능 | 동일 |
| 42 | `crdt_days` | 3 | 신용기간 | 동일 |
| 43 | `prdy_vol` | 12 | 전일 거래량 | 동일 |
| 44 | `stck_fcam` | 12 | 주식 액면가 | 동일 |
| 45 | `stck_lstn_date` | 8 | 상장일자 | 동일 |
| 46 | `lstn_stcn` | 15 | 상장주수 | 동일 |
| 47 | `cpfn` | 21 | 자본금 | 동일 |
| 48 | `stac_month` | 2 | 결산월 | 동일 |
| 49 | `po_prc` | 7 | 공모가 | 동일 |
| 50 | `prst_cls_code` | 1 | 우선주 | 동일 |
| 51 | `ssts_hot_yn` | 1 | 공매도 과열 | 동일 |
| 52 | `stange_runup_yn` | 1 | 이상급등 | 동일 |
| 53 | `krx300_issu_yn` | 1 | KRX300 | 동일 (KOSPI는 추가로 kospi_issu_yn 1byte 더 있음) |
| 54 | `sale_account` | 9 | 매출액 | 동일 |
| 55 | `bsop_prfi` | 9 | 영업이익 | 동일 |
| 56 | `op_prfi` | 9 | 경상이익 | 동일 |
| 57 | `thtr_ntin` | 5 | 당기순이익 | 동일 |
| 58 | `roe` | 9 | ROE | 동일 |
| 59 | `base_date` | 8 | 기준년월 | 동일 |
| 60 | `prdy_avls_scal` | 9 | 전일기준 시가총액 | 동일 |
| 61 | `grp_code` | 3 | 그룹사 코드 | 동일 |
| 62 | `co_crdt_limt_over_yn` | 1 | 회사신용한도초과 | 동일 |
| 63 | `secu_lend_able_yn` | 1 | 담보대출가능 | 동일 |
| 64 | `stln_able_yn` | 1 | 대주가능 | 동일 |

**검증 결과 — KOSDAQ**:
- `.h` 헤더 typedef 필드 수 = **64개** (Part2) + Part1 3개 = **총 67개**
- Python 파서 field_specs 합계 = **221 byte** + 줄바꿈 1byte = 222byte 정합 (Python L40 `row[0:len(row) - 222]` 정합)
- mismatch **0건**

---

## §4. KOSPI vs KOSDAQ 차이 매트릭스 (필수 인지)

| # | 필드 | KOSPI | KOSDAQ | 통합 entity 처리 |
|---|---|---|---|---|
| 1 | `mnin_cls_code_yn` (제조업) | ✅ | ❌ | nullable |
| 2 | `sprn_strr_nmix_issu_yn` (지배구조지수) | ✅ | ❌ | nullable |
| 3 | `kospi200_apnt_cls_code` (KOSPI200 섹터) | ✅ | ❌ | nullable |
| 4 | `kospi100_issu_yn` | ✅ | ❌ | nullable |
| 5 | `kospi50_issu_yn` | ✅ | ❌ | nullable |
| 6 | `elw_pblc_yn` | ✅ | ❌ | nullable |
| 7 | `krx_fnnc_svc_yn` (삭제됨 — KOSPI dummy 1byte) | ✅ | ❌ | 무시 (Python 파서도 'Non1' dummy) |
| 8 | `sri_nmix_yn` (SRI 지수) | ✅ | ❌ | nullable |
| 9 | `kospi_issu_yn` (KOSPI 여부) | ✅ | ❌ | nullable (KOSDAQ는 항상 false) |
| 10 | `vntr_issu_yn` (벤처기업) | ❌ | ✅ | nullable |
| 11 | `invt_alrm_yn` (투자주의환기) | ❌ | ✅ | nullable (KOSDAQ 단독) |
| 12 | `ksq150_nmix_yn` (KOSDAQ150) | ❌ | ✅ | nullable |
| 13 | `avls_scal_cls_code` 의미 | 0:제외 1:대 2:중 3:소 | 0:제외 1:KOSDAQ100 2:KOSDAQmid300 3:KOSDAQsmall | 단일 컬럼 + market에 따라 의미 분기 (해석은 frontend) |
| 14 | `etp_prod_cls_code` 5번 코드 | "5:상장형수익증권" 명시 | 명시 부재(주석에 4까지) | 동일 컬럼, 5는 KOSPI에서만 발견 가능 |

**통합 entity 전략**: 모든 KOSPI/KOSDAQ 단독 필드는 nullable. parser가 market에 따라 다른 byte offset 사용 — `KisInstrumentMasterAdapter`가 KOSPI/KOSDAQ 분기 메서드 분리.

---

## §5. 종목 type 분류 — `scrt_grp_cls_code` 권위 매핑 (자체 추론 폐기)

KOSPI/KOSDAQ 헤더 L9~L14 동일 정의:

| `scrt_grp_cls_code` | 의미 | 본 PR-21 InstrumentType 매핑 |
|---|---|---|
| `ST` | 주권 | `STOCK` |
| `MF` | 증권투자회사 | `STOCK` (또는 `INVESTMENT_COMPANY` 신설 검토 — §11 결정) |
| `RT` | 부동산투자회사 (REIT) | `REIT` |
| `SC` | 선박투자회사 | `STOCK` (또는 `SHIP_INVESTMENT` 신설 검토) |
| `IF` | 사회간접자본투융자회사 | `STOCK` (또는 `INFRA_FUND` 신설 검토) |
| `DR` | 주식예탁증서 | `DR` (신규 type — 본 PR-21에서 enum 추가) |
| `EW` | ELW | `WARRANT` (기존 InstrumentType.WARRANT 정합) |
| `EF` | ETF | `ETF` |
| `SW` | 신주인수권증권 | `WARRANT` (또는 `RIGHTS` 신설) |
| `SR` | 신주인수권증서 | `WARRANT` (또는 `RIGHTS` 신설) |
| `BC` | 수익증권 | `STOCK` (또는 `BENEFICIARY_CERTIFICATE` 신설) |
| `FE` | 해외 ETF | `ETF` (해외 ETF는 본 MVP 비범위) |
| `FS` | 외국주권 | `STOCK` (외국주권은 본 MVP 비범위) |

**현재 `KisInstrumentMasterAdapter.detectType(name, code)` 함정**: 종목명에 "ETF"·"KODEX"·"TIGER" 등 substring이 있는지 추측 — `scrt_grp_cls_code`가 권위인데 **자체 추론 lens 사용 중**. 본 PR-21에서 `scrt_grp_cls_code` 직접 사용으로 정정 의무.

**우선주 판별** — 현재 `code.getOrNull(4) == '5'` 휴리스틱은 KRX 우선주 코드 체계 정합이지만 `prst_cls_code` (1:구형우선주 2:신형우선주)가 권위. 본 PR-21에서 후자 사용.

**ETN 판별** — 현재 종목명 "ETN" substring. 본 PR-21에서 `etp_prod_cls_code == 3 또는 4`(ETN·손실제한ETN) 사용.

---

## §6. mst.zip 파일 형식 정합 (Python 파서 → Kotlin port 1:1)

### §6-1. 다운로드

- KOSPI: `https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip`
- KOSDAQ: `https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip`
- encoding: cp949 (= EUC-KR + 확장 한글 — Java/Kotlin은 `Charset.forName("EUC-KR")` 또는 `"x-windows-949"` 사용 권장)

### §6-2. 라인 구조 (KOSPI 예시)

```
[단축코드 9byte][표준코드 12byte][한글명 가변][Part2 228byte 고정][\n]
        9             21          (-228 시작점)              (line 끝)
```

**중요**: 한글명이 cp949에서 한글 1자 = 2byte. 일반적으로 한글명 영역은 40byte 할당 (ST_KSP_CODE에는 명시 없음 — 가변 인지). PR-20 hotfix가 byte 단위 substring 정정한 부분이 핵심.

### §6-3. PR-11 → PR-20 → PR-21 점진 진화

| PR | 파싱 범위 | 결함 | 정정 |
|---|---|---|---|
| PR-11 | 단축코드(6) + ISIN + 한글명 + 영문명만 (Java String char-단위) | 한글명 깨짐 | — |
| PR-20 | 동일 4 필드 (byte-단위 EUC-KR 정정) | type 4 필드만 → 시장경고·거래정지·시총·업종·매매단위 등 70+ 필드 0건 | 부분 정정 |
| **PR-21** | **모든 73 필드 (Part1 3 + Part2 70 KOSPI / 67 KOSDAQ)** | (PR-21 자체) | **본격 보강** |

---

## §7. 검증 결론

1. **KIS 헤더 raw 검증 완료** — KOSPI 73 필드 + KOSDAQ 67 필드, byte offset 100% Python 파서 정합. mismatch 0건
2. **자체 추론 0건** — 모든 필드명·byte 길이·의미는 `.h` 헤더 + Python 파서 직접 인용
3. **현재 코드 함정 식별** — `KisInstrumentMasterAdapter`가 4 필드만 파싱(70+ 무시), `detectType` 자체 추론(scrt_grp_cls_code 권위 미사용)
4. **PR-21 본격 보강 가능 입증** — 모든 필드 byte offset 확보, KOSPI/KOSDAQ 차이 14건 흡수 가능, 통합 entity nullable 전략 검증

본 보고서가 PR-21 spec(`pr-21-instrument-kis-first-design.md` §6)에서 직접 인용되며, 모든 결정은 본 보고서의 byte offset 매트릭스를 출처로 입증.

---

## §8. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | KIS 헤더 raw 검증 v1 발행. KOSPI 73 필드 + KOSDAQ 67 필드 + KOSPI/KOSDAQ 차이 14건 + scrt_grp_cls_code 권위 매핑 13건 + Python 파서 mst.zip 다운로드 URL·encoding 직접 인용. 사용자 비판(KIS-first 정책 위반 — 자체 추론 14 컬럼만) 응답 산출물. (architect, opus 4.7 1M) |
