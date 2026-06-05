# REAL canary 준비 체크리스트 (진행 추적)

> **사용법**: 최고 관리자가 항목을 준비하고 알려주시면, orchestrator가 **실측 검증 후** `[x]` + 검증 evidence를 기록합니다. 전 항목 ✅ + 실행일 결정 = canary 진입 가능.
> 가이드: [`real-canary-precheck.html`](real-canary-precheck.html) · 절차 권위: [`real-canary-runbook.md`](real-canary-runbook.md)
> 상태: **준비 단계** (MOCK 관찰 중 — canary 실행일 미정)

## A. 사용자 준비 항목 (precheck §4와 1:1)

| 체크 | # | 항목 | 검증 방법 | 검증 evidence (orchestrator 기록) |
|------|---|------|-----------|-----------------------------------|
| [ ] | U-1 | canary용 전략 1개 생성 + 백테스트 통과 (분할 OFF·손절/익절·Sizing 최소) | strategy row + 백테스트 결과 | |
| [ ] | U-2 | MOCK 관찰 기간 — 시그널↔주문 정합 확인 (며칠) | 관찰 리포트 (orchestrator 발행) | |
| [ ] | U-3 | KIS 실전 주문용(vps) 키 등록 (⚙️ SettingsModal) | `user_kis_credential` REAL row + 잔고 200 | |
| [ ] | U-4 | KIS IP 화이트리스트 등록 | 실키 조회 1회 성공 | |
| [ ] | U-5 | `.env` hard cap 3종 입력 (QTY=1 · NOTIONAL=100000 · PER_DAY=5) | printenv 3종 | |
| [ ] | U-6 | RiskConfig 4종 (손실한도 50,000 · 전략당 5건 · 동시 1종목 · 종목예산 100,000) | risk_config row | |
| [ ] | U-7 | 실거래 동의 토글 | consent API `consented=true` | |
| [ ] | U-8 | 실행일 결정 + 당일 아침 통보 | — | |

## B. 시스템 준비 항목 (orchestrator 책임)

| 체크 | # | 항목 | 상태 |
|------|---|------|------|
| [x] | S-1 | canary hard cap 게이트 코드 (ADR 0109) | ✅ PR #740 배포 (2026-06-05) |
| [x] | S-2 | 주문 path rate limit 우선권 (EGW00201 재발 방지) | ✅ PR #749 배포 — USER_PATH reserve 3토큰 |
| [x] | S-3 | WS 유령 재연결 루프 fix | ✅ PR #748 배포 + 19:00 라이브 실증 |
| [x] | S-4 | 외부 HTS 주문 흡수 매핑 (canary cap 오염 차단) | ✅ PR #752 + 보정 SQL 실증 |
| [x] | S-5 | 장운영시간 게이트 | ✅ PR #717 (default ON) |
| [x] | S-6 | runbook + 사전점검서 발행 | ✅ #739 + 본 kit |
| [ ] | S-7 | F-1-OBS 패치 (PRICE_DROP 다회차) — **분할 canary(2회차) 전 필수, 1회차는 불요** | backlog |
| [ ] | S-8 | canary 전일 저녁 배포 완료 상태 동결 (당일 아침 재배포 금지 — WS 공백 회피) | 실행일 결정 후 |

## C. 실행 당일 게이트 (당일 아침 orchestrator 실측 — runbook §1)

| 체크 | # | 항목 |
|------|---|------|
| [ ] | G-1 | `MAGICJAR_MODE=REAL` |
| [ ] | G-2 | `MAGICJAR_ALLOW_REAL=true` |
| [ ] | G-3 | 실거래 동의 consented=true |
| [ ] | P-1 | KillSwitch OFF |
| [ ] | P-3 | hard cap ENV 3종 printenv 일치 |
| [ ] | S(범위) | ACTIVE 전략 정확히 1개 · 종목 1개 |
| [ ] | O | 백업 최신 · Slack 도달 · 4모듈 ERROR 0 · staging 30분 dwell |

## 변경 이력

| 일자 | 변경 |
|------|------|
| 2026-06-05 | 최초 발행 (orchestrator) — MOCK 관찰 단계 진입, B 6/8 완료 |
