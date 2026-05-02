# ADR 0028: v1.0.1 베이스라인 태그 + release/v1 immutable 보존 브랜치

> **STATUS: DEPRECATED — 사유**: 사용자 명시(2026-05-02) "release말곤 싹 지울 예정" 정합. 단 사용자 결정(2026-05-03 default 12 §3) — `release/v0`·`release/v1`·`release/v1.1.0`·`release/v1.2.0_notwork` 4개 모두 보존. v2 베이스라인 컷은 별도 ADR(차후 발행)에서 결정. 본 ADR의 보존 정책 자체는 유효, 단 v2 흐름의 baseline 컷 표준은 새 ADR로 재기술 예정. (2026-05-03 archive 이동)

- 상태: Deprecated (보존 룰만 유효, v2 baseline 표준은 후속 ADR)
- 작성일: 2026-05-01
- 결정자: architect (사용자 직접 명시 — "v1.0.1 된거 같다")
- 관계: ADR 0027(v0 베이스라인), ADR 0009(브랜치 전략), ADR 0015(머지 흐름)

## 맥락

v0.1.0(`1aa3949`, 2026-04-30) 컷 후 짧은 기간에 다음이 main에 연속 머지됨:

1. **트레이딩뷰 풀세트** (PR #43): 단일 IChartApi candle+volume overlay, crosshair OHLC tooltip, 일/주/월/년/분/틱 봉단위, 한국 표준 시간
2. **MockTickGenerator** (PR #44): 1초 5종목 mock 시세 publish — KIS 연동 전 dev 시세 흐름 확보
3. **보조지표 토글** (PR #45): SMA·EMA·BB·RSI 클라 계산 + 토글 패널 + RSI sub-pane
4. **백테스트 실 연결** (PR #46): mock 폐기 → POST /api/backtest + 1.5s 폴링
5. **운영 가드** (PR #47, #41): kafka retention, jar staleness, build-and-deploy.sh 헬퍼, QA 리포트
6. **STOMP raw WebSocket 정합** (PR #49): SockJS mismatch 해소
7. **분봉 V8 시드** (PR #51): 005930·000660 H1·M30·M5·M1 ~1500봉
8. **분봉 time JSON number** (PR #53): LWC UTCTimestamp 호환
9. **틱 차트** (PR #54): RealtimeTickChart — STOMP 라이브 스트림 LineSeries
10. **Kafka UI** (PR #55): provectuslabs/kafka-ui:8090 디버깅 GUI
11. **JsonDeserializer trusted packages** (PR #56): com.mj→* — deserialize 정합 회복
12. **AI 뉴스 카드** (PR #57): 종목상세 NewsAnalysisCard + STOMP /topic/news 구독 + Mock 뉴스 발행기

v0의 "껍데기"에서 **데모 가시성 단계**로 진전. 실 매매는 아직 mock 모드이지만, frontend·백엔드 wiring·데이터 흐름·차트 표현 등 사용자가 눈으로 확인 가능한 수준 도달. 사용자가 직접 "v1.0.1 된거 같다"고 판단.

## 검토한 대안

| 대안 | 동작 | 적합성 |
|------|------|------|
| A. v1.0.0 첫 베이스라인 (정석) | 메이저 점프 = "실 매매 가능"으로 통상 해석 | 현재 mock 단계라 의미 과대 |
| **B. v1.0.1 (사용자 명시)** | v1로의 점프 + patch 의미 결합 | 사용자 의도 존중 + 실제 진전 반영 |
| C. v0.2.0 patch | v0 라인 연장 | 사용자 의도 거스름 |

## 결정

**옵션 B 채택 — v1.0.1 + release/v1.**

### 1. 태그
- **`v1.0.1`** annotated tag, 본 PR 머지 시점의 main HEAD 위에 생성
- 메시지에 12개 PR 시리즈 + "데모 가시성 단계 도달 — 실 매매는 아직 mock" 명시

### 2. `release/v1` 브랜치
- 동일 commit에서 분기, **immutable**
- 용도: 체크아웃 전용. 향후 v1 라인 비교·돌아가기 백업
- v1 hotfix는 main 트렁크에서 전진. release/v1에 직접 머지 안 함 (ADR 0027 정신 유지)

### 3. 보호 메커니즘 보강
- **로컬 가드 정교화**: `.husky/pre-push`의 `release/*` 차단 룰을
  `remote_sha = 0000…`(신규 ref 생성) 케이스만 허용으로 수정
  - 새 베이스라인 컷(`release/v2`, `release/v3` …)은 정상 push
  - 기존 `release/v0`/`release/v1`의 update·delete는 차단
- **원격 가드**: ADR 0027과 동일 — private repo + Free 플랜 미지원 보류

### 4. 다음 베이스라인 컷 기준
실제 매매 진입 직전(KIS 모의 키 적용 + e2e 1종목 1전략 24h 가동 검증)에 **`v2.0.0`** + `release/v2` 컷. v1.x.x patch는 그 사이에 누적.

## 비-결정

- v1.0.0 vs v1.0.1: 사용자 명시로 종결
- release/v1에 hotfix 머지: 절대 금지(ADR 0027 정신 유지)
- 태그 재할당·force-push: 금지

## 결과

- v0.1.0 / release/v0: "껍데기" 시점 보존 (변경 없음)
- v1.0.1 / release/v1: "데모 가시성 시점" 보존
- pre-push hook: 신규 release ref 허용으로 정교화 → 향후 베이스라인 컷 자동화 가능

## 참고

- 본 ADR 머지 후 main에서:
  ```bash
  git tag -a v1.0.1 -m "..."
  git push origin v1.0.1
  git switch -c release/v1
  git push -u origin release/v1
  git switch main
  ```
- CI/CD release.yml이 `v*.*.*` 태그에 반응 → ghcr.io 이미지 + Release draft 자동 생성
