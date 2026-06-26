---
name: magicjar-ops-monitor
description: magicJar 실운영(REAL) 상시 모니터링 + 일일 운영 보고 발행. "모니터링 시작/가동", "/magicjar-ops-monitor", "일일 보고 만들어줘", 운영 세션 시작 시 트리거. REAL canary 1~2일차(2026-06-11/12) orchestrator 세션의 검증된 감시·대응·기록 방식을 표준화한 커맨드.
---

# magicjar-ops-monitor — 실운영 감시·대응·일일 보고

> **목적**: "1개 전략을 완벽하게" (최고 관리자, 2026-06-12) — 어느 세션이 맡아도 같은 품질의 감시·대응·기록이 되도록 표준화한다. 다중 전략 확장의 토대.
> **권위 ref**: 헌법 §12(주문 액션 승인 매트릭스)·§13(롱 온리) · `docs/architecture/policies/long-only-trading.md` · runbook §5(중단 절차)

## A. 세션 시작 시 — 감시 3종 무장 (Monitor 도구)

장중(평일 09:00~15:30)이면 아래 3종을 persistent Monitor로 무장한다. 코드는 검증본 그대로 사용:

1. **주문 이벤트 스트림** (실시간): `docker logs -f magicjar-consumer`를 `OrderRouterKafkaListener (OrderIntent|ACK|REJECTED)|ODNO|order\.filled|체결통보|CANARY|KILLSWITCH|stop-loss MARKET|RealRiskContextUnavailable| ERROR ` 패턴으로 follow. 제외: `today-candle-seed|inquire-daily-itemchartprice|SELL_WITHOUT_POSITION|MAX_CONCURRENT_POSITIONS|잔고조회 실패`. **컨테이너 교체 생존형** — while true 루프로 재attach + 10초+ 부재 시 ALERT.
2. **이상 패턴 폴링** (10분): ① outbox 위반/시스템 알람 10분 8건+ ② stuck 주문(REQUESTED 5분+ 또는 MARKET ACK 5분+ — **휴면 LIMIT ACK는 정상이므로 제외**) ③ 신규 ERROR 3건+ (기지 계열 `EGW00201|EGW00215|OPSP8997` 제외) ④ 드리프트 재발 2건+.
3. **시세·평가 생존 맥박** (2.5분, 장중만): ACTIVE 전략 종목의 최신 1m 캔들 경과 **180초+ → ALERT[시세정지]** (F3 유령 세션 — 가장 위험한 무소음 장애) + 최근 5분 해당 종목 처리 로그 0건 → ALERT[평가정지].

## B. 이상 발생 시 — 대응 분기 (검증된 순서)

1. **분류 먼저** — 조치 전에 정체를 실측으로 확정한다 (로그 원문 + DB 대조). 오탐 사전(§D) 우선 대조.
2. **자본 보호 방향**(청산 가능 복구·위험 차단)은 즉시 집행 + 사후 보고. **KillSwitch ON은 무조건 즉시 가능** (헌법 §12 예외).
3. **결재값·정책 변경**(cap 숫자·게이트 의미·전략 활성화)은 사용자 결재 후 집행. 단 사용자가 의미를 정정해 주면 "숫자 임의 환산"이 아니라 **그 의미대로 코드를 고치는 hotfix**가 정도 (2026-06-12 교훈).
4. **운영 DB 직접 쓰기**(UPDATE/DELETE)는 KIS 실측과 일치시키는 동기화 + 사유 기록일 때만, 최소 행으로.
5. hotfix는 backend-core 디스패치(워트리 격리·테스트·PR) → 머지 → **배포 직전 `git log origin/main..main` diff 0 검증**(타 세션 공유 레포 — 빠진 채 배포 사고 방지) → flat 또는 마감 후 배포 원칙 (긴급 자본 보호는 장중 가능, in-flight 0 확인).

## C. 일일 보고 (장 마감 15:30 후 — 의무)

1. `scripts/ops-daily-snapshot.sh` 실행 — 9개 섹션 raw 데이터 수집 (게이트·주문 전수·손익·시그널 통계·알람 분류·ERROR 분류·미종결·원장 정합·WS 생존).
2. `_workspace/ops/daily/YYYY-MM-DD.html` 발행 (덮어쓰기 금지 — 추가 발행은 `-2` suffix). **표준 7섹션**:
   ① 요약 배너 (거래 횟수·실현손익·결함 발견/해소 수) ② 거래 전수 + 체인 무결성 대조 (시그널→주문→체결→원장→알림 각 1:1) ③ 알람 전수 분류표 (미지의 알람 0이어야 GREEN) ④ ERROR 분류 (신규/기지) ⑤ 발견 결함·조치·PR 매트릭스 ⑥ 인프라 상태 (WS 재연결·rate limit 빈도·배포 이력) ⑦ 익일 인계 (잔여 의제·관찰 포인트).
3. `_workspace/ops/daily/index.html`에 한 줄 prepend (날짜·요약·링크).
4. 메모리(`project_session_*` 또는 ops 상태 메모리) 갱신 — 다음 세션 인계 앵커.

## D. 오탐 사전 (실증 축적 — 계속 보강할 것)

| 현상 | 정체 | 대응 |
|------|------|------|
| `today-candle-seed` EGW00201 다발 | BG 시세수집 rate limit (큐 재설계 spec 대상) | 무해 — 주문 경로는 reserve 토큰 분리 |
| 잔고조회 EGW00215 산발 | 원장 per-TR 한도 (시그널 평가마다 호출) | 무해 — 다층 룰이 선차단. 연속 2주기 reconcile 실패 시에만 에스컬레이션 |
| 18:00 OPSP8997 burst | KIS 야간 전환 일괄 구독 정리 | 무해 — 익일 아침 자동 재구독 확인만 |
| 휴면 LIMIT 주문 ACK 수시간 | 익절 예약 정상 대기 | stuck 아님 |
| `SELL_WITHOUT_POSITION` 차단 | flat 상태 정상 가드 (롱 온리 — 주문 미발행) | 무해 — Slack mute됨 (PR #926) |
| 재배포 직후 STREAM-RESET/구독 로그 | 컨테이너 교체 | 정상 — healthy + 재구독 + 캔들 재개 3종만 확인 |
| WS 단발 단절 후 grace 내 회복 | KIS 측 세션 정리 | 무해 — **회복 없이 침묵이 3분+면 F3 유령 → 재시작** |
| 신규 편입 보유종목 1m 캔들 수천초 경과 ALERT | 매수 직후 1m 캔들 on-demand 백필 일시 지연(ADR 0119) | 무해 오탐 — 종목별 실측 시 즉시 회복. 시세맥박 모니터에 **편입 5분 grace**(opened_at) 적용 (2026-06-25) |

## E. 금지·주의

- 시세 정지 의심 시 "장이 한산해서"로 넘기지 말 것 — **캔들 timestamp 실측**이 판정 기준 (2026-06-11 2시간 공백 교훈).
- 모니터 알림은 사용자 응답이 아님 — 보고와 질문을 혼동하지 말 것.
- 코워크 세션과 레포 공유 중 — 브랜치 전환·배포 전 `git branch --show-current` + origin diff 확인 의무.
