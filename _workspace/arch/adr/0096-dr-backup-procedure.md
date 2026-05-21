# ADR 0096: P0-OPS-4 — DR/backup 절차 (PostgreSQL pg_dump + Redis snapshot + RPO/RTO 정의)

- 상태: **Accepted (2026-05-21 — architect 단독 결재, 사용자 의제는 ADR 0094 D-5 운영 단계 v3.8.0 cut 결재에 통합 완료)**
- 작성일: 2026-05-21
- 결정자: architect (ADR 0094 §3-7 P0-OPS-4 task 본문화 의무 정합)
- 영역: 운영 헌법 §9 (DR/backup) + persistence + observability
- Amends: 운영 헌법 §9-4 (backup 매트릭스 본문화 amend — cron 스크립트 산출물 위치 LOCK)
- 관련 ADR: 0094 (운영 단계 transition — 본 ADR이 §3-7 P0-OPS-4 task 본문화) · 0095 (P0-OPS-3 SLO/SLA — RTO/RPO 측정 source cross) · 0088 (persistence-shared — Outbox/notification recovery 정합) · 0006 (PostgreSQL 단일 + 스키마 분리) · 0030 (master-architecture SoT — post-MVP TimescaleDB 전환 cross) · 0044 (Notification — Slack alert reuse for backup 실패) · 0043 (KillSwitch — DR drill 시 사용자 결재 정합)

## 1. 배경

### 1-1. ADR 0094 §3-7 P0-OPS-4 task 본문화 의무

ADR 0094 §3-2 P0-OPS-4가 다음과 같이 task 명시:

> "postgres pg_dump 자동화 0 + Kafka offset 백업 0 + Redis snapshot 정책 0 → postgres pg_dump cron + Kafka topic backup + Redis snapshot 정책 ADR 별도 발행 — **architect spec + backend-core 1차 dispatch**"

본 ADR이 그 본문화. ADR 0094 §9-4 운영 헌법 §10-4(원문 §9-4 부분):

> postgres pg_dump cron 매일 03:00 KST + 7일 retention (**backend-core 책임**) · Redis snapshot 매일 03:30 KST + 3일 retention (**backend-core 책임**) · 분기별 DR drill (사용자 결재 의무) + **qa Visual E2E 시나리오 자동 재현 의무**

본 ADR이 위 본문 정합 + 산출물 위치 LOCK + RPO/RTO 본문화. R-4 runbook(P0-OPS-5)이 본 ADR §3 절차를 직접 인용 (cross-link).

### 1-2. v3.8.0 운영 baseline 직전 — RPO/RTO 정의 의무

ADR 0094 §4-3 release 정책 정합:

> v3.8.0 운영 단계 1차 baseline — 7 hierarchy 정착 + qa Visual 격상 + **P0 OPS 6건 해소** + observability stack + runbook

P0 OPS 6건 중 본 ADR(P0-OPS-4)이 4번째. P0-OPS-1·2(logback JSON + Actuator/Prometheus) 머지 완료 + P0-OPS-3(ADR 0095 SLO/SLA + alert routing) 머지 완료 + P0-OPS-5(runbook 5건 R-1~R-5) 머지 완료 → 본 ADR은 backup·DR drill의 **단일 권위 source** + R-4 runbook이 본 ADR을 직접 참조.

### 1-3. 현 시점 baseline evidence (2026-05-21 main commit 직전)

| 항목 | baseline 상태 | 비고 |
|------|--------------|------|
| `_workspace/backups/` 디렉토리 | 부재 — 본 ADR이 신설 LOCK | 실 dump 파일은 git untracked (.gitignore) |
| postgres pg_dump cron | 0 (수동만) | 본 ADR이 cron 등록 가이드 발행 |
| Redis snapshot 정책 | docker default(AOF + RDB 자동 snapshot) — 호스트 backup 0 | RDB dump 파일 호스트 export 본 ADR이 본문화 |
| pg_restore 절차 | R-4 runbook §3-2 baseline | 본 ADR cross-link |
| DR drill 분기 의무 | 운영 헌법 §10-4 본문만 — 실행 spec 0 | 본 ADR §6 본문화 |
| Kafka offset backup | backlog — 본 ADR scope 외 (자본 path 직결 0 + Outbox table이 영속 권위) | 사후 ADR 후보 |
| RPO/RTO 정의 | 0 — 본 ADR이 첫 정의 | 본 ADR §4 본문화 |

→ 본 ADR은 **PostgreSQL backup + Redis snapshot + RPO/RTO + 사용자 cron 가이드 + DR drill 분기 절차** 5건을 baseline 0 → 운영 단계 LOCK 회복.

---

## 2. PostgreSQL backup 매트릭스

### 2-1. 매일 full dump (pg_dump custom format)

| 항목 | 본문 |
|------|------|
| **주기** | 매일 03:00 KST (사용자 macOS crontab 또는 launchd plist 등록) |
| **명령** | `docker exec magicjar-postgres pg_dump -U magicjar -Fc magicjar` |
| **출력 위치** | `_workspace/backups/postgres/daily/YYYY-MM-DD.dump` |
| **파일 형식** | pg_dump custom format (`-Fc`) — pg_restore --clean --create 호환 |
| **retention** | **7일** (`find ... -mtime +7 -delete` 03:30 KST 후속 cron) |
| **실행 actor** | 사용자 macOS crontab (개발 단계) → docker compose service (v3.9.0+ 검토) |
| **실패 감지** | cron 실패 시 stderr 로그 + 본 ADR §5 alert routing 검토 (Slack — backend-core 후속 wiring) |

### 2-2. weekly full + 4주 retention (선택 — backlog)

- daily 7일 retention과 별개로 **주간 보존 backup** (예: 매주 일요일 03:00 weekly slot)
- 출력 위치: `_workspace/backups/postgres/weekly/YYYY-WW.dump`
- retention: 4주 (한 달치 weekly backup 보존)
- **본 ADR baseline 외 — P1 OPS 사이클 amend 후보** (운영 dwell evidence 기반 결정)

### 2-3. WAL archiving (선택 — backlog)

- PostgreSQL Point-in-Time Recovery (PITR) 가능 — WAL segment 별도 보관
- **본 ADR baseline 외 — P2 OPS 사이클 amend 후보** (자본 path 직결 0 + daily backup으로 RPO 24h 충분 baseline)
- 도입 trigger: 사용자 결재 + storage 용량 plan + WAL retention 정책 본문화

### 2-4. 산출물 디렉토리 구조 LOCK

```
_workspace/backups/
├── postgres/
│   ├── daily/
│   │   ├── 2026-05-21.dump          # 매일 03:00 KST
│   │   ├── 2026-05-20.dump
│   │   └── ... (7일 보존)
│   └── weekly/                      # (P1 OPS amend 후보 — 현 baseline 미사용)
└── redis/
    └── snapshots/
        ├── dump-2026-05-21-0330.rdb # 매일 03:30 KST
        └── ... (3일 보존)
```

`_workspace/backups/` 전체는 `.gitignore` 추가 (실 dump 파일 git tracked 0 — 시크릿 누출 risk + 용량 누적 risk 회피).

---

## 3. Redis snapshot 매트릭스

### 3-1. baseline (현재 docker compose default)

- Redis 7-alpine 기본: AOF disabled + RDB snapshot 자동 (write 빈도 threshold 도달 시 백그라운드 `SAVE`)
- **컨테이너 내부 위치**: `/data/dump.rdb` (volume 매핑 안 됨 — 컨테이너 재생성 시 손실 risk)

### 3-2. 본 ADR 정합 매트릭스

| 항목 | 본문 |
|------|------|
| **주기** | 매일 03:30 KST (pg_dump 30분 후 — 자원 경합 회피) |
| **명령** | `docker exec magicjar-redis redis-cli BGSAVE` → 약 1~5초 후 `docker cp magicjar-redis:/data/dump.rdb <host>` |
| **출력 위치** | `_workspace/backups/redis/snapshots/dump-YYYY-MM-DD-HHMM.rdb` |
| **retention** | **3일** (Redis는 cache 본질 — 영구 audit 의무 0 + 용량 ↓) |
| **실행 actor** | 사용자 macOS crontab |
| **AOF 도입 결정** | **현 baseline 거부** — Redis 사용 패턴은 fanout cache + KIS 토큰 캐시 + Bucket4j rate limit 임시 state. AOF 영속은 운영 부담 ↑ + 본질 외. RDB snapshot 단일 정합 |

### 3-3. Redis 사용 패턴 정합 검증

| 사용 영역 | Redis 본질 | 손실 시 영향 | snapshot 필요성 |
|----------|-----------|------------|---------------|
| KIS OAuth 토큰 캐시 (ADR 0038) | cache + 24h TTL | KIS 재발급 (자동 회복) | LOW — snapshot 없이도 동작 |
| Bucket4j rate limit state (ADR 0037) | 임시 token bucket | 5분 내 자연 회복 | LOW |
| WS fanout boundary (ADR 0064) | pub/sub | STOMP 재연결 시 자연 회복 | LOW |
| Screener cache 30m (ADR 0071) | cache + 30m TTL | 자연 회복 (재계산) | LOW |
| **dedup/rate limit (ADR 0075 T-NEWS)** | 임시 state | 5분 내 자연 회복 | LOW |

→ Redis snapshot은 **DR drill 시 staging 환경 복구 evidence 보존 + 컨테이너 자체 결함 시 빠른 복구**가 본질. RPO=24h 정합.

---

## 4. RPO/RTO 정의

### 4-1. RPO (Recovery Point Objective) — 데이터 손실 최대 허용 시간

| DB | RPO | 사유 |
|----|-----|------|
| **PostgreSQL** | **24h** | daily backup 03:00 KST 1회 → 최악 시점 23:59 backup 직전 결함 시 23:59h 데이터 손실. 자본 path 직결 (orders·position·kill_switch_state)이나 **WAL archiving 없으면 본 RPO가 최대**. 사용자 결재 시 WAL 도입(P2 OPS amend)으로 RPO ≤ 5분 회복 가능 |
| **Redis** | **24h** (snapshot baseline) — 또는 **0~분 (자연 회복)** | snapshot 손실 시에도 cache 본질이므로 자연 회복. backup은 staging DR drill evidence 보존 본질 |
| **Kafka offset** | **N/A** | 본 ADR scope 외. Outbox table이 영속 권위 (ADR 0088 persistence-shared) → DB backup으로 흡수됨. Kafka 자체는 commit log 본질 + 24h retention default + at-least-once delivery |

### 4-2. RTO (Recovery Time Objective) — 복구 완료 최대 허용 시간

| 시나리오 | RTO | 절차 본문 |
|---------|-----|----------|
| **DB 결함 (전체 복구)** | **30분** | R-4 runbook §3-2 pg_restore 절차 LOCK — 1) 컨테이너 정지(2분) + 2) 최신 backup 선택·docker cp(1분) + 3) pg_restore --clean --create(5~10분) + 4) 앱 재배포(5분) + 5) dogfooding 검증(5분) + buffer(5분) = 30분 baseline |
| **DB 부분 복구 (특정 테이블)** | **15분** | R-4 runbook §3-3 부분 복구 절차 — pg_restore --data-only --table=X |
| **Flyway 결함 복구** | **20분** | R-4 runbook §3-4 — flyway_schema_history clean + migration SQL fix + 재배포 |
| **Postgres 컨테이너 결함 (볼륨 보존)** | **10분** | R-4 runbook §3-5 — 컨테이너 재생성 + 볼륨 그대로 |
| **Redis 컨테이너 결함** | **5분** | docker compose restart redis (snapshot 자동 복구 또는 자연 cache 재구축) |
| **분기 DR drill (staging 복구)** | **60분** | §6 본문 + qa Visual E2E 5 시나리오 자동 재현 |

### 4-3. RPO/RTO 검증 절차 (분기 DR drill 의무)

§6 본문 — 분기 1회 staging 환경 복구 + qa Visual E2E + RPO/RTO 실측 evidence → 본 ADR amend 검토.

---

## 5. 복구 절차 LOCK (R-4 runbook cross-link)

본 ADR은 **백업 정책 + RPO/RTO + 산출물 위치 LOCK**. 복구 실행 절차 본문은 **R-4 runbook(P0-OPS-5)이 단일 권위**. 본 §은 cross-link만 명시 — 본문 중복 회피.

### 5-1. 복구 절차 매트릭스 (R-4 인용)

| 시나리오 | R-4 runbook § | 본 ADR cross-link |
|---------|-------------|------------------|
| DB 가용성 진단 (pg_isready) | R-4 §1-2 | - |
| Flyway 정합 확인 | R-4 §1-3 | - |
| 앱 startup 로그 진단 | R-4 §1-4 | - |
| 무결성 SQL 5건 | R-4 §1-5 | §4-1 RPO 검증 source |
| 자본 path 보호 (KillSwitch ON 검토) | R-4 §2-1 + R-3 §3-1 | - |
| 앱 정지 (DB 추가 write 차단) | R-4 §2-2 | - |
| 백업 위치 확인 | R-4 §3-1 | **§2-4 산출물 LOCK** |
| pg_restore --clean --create | R-4 §3-2 | **§4-2 RTO 30분 절차** |
| 부분 복구 (특정 테이블) | R-4 §3-3 | **§4-2 RTO 15분** |
| Flyway 결함 복구 | R-4 §3-4 | **§4-2 RTO 20분** |
| 컨테이너 자체 결함 (볼륨 보존) | R-4 §3-5 | **§4-2 RTO 10분** |
| 볼륨 손상 (최후 수단 full restore) | R-4 §3-6 | §2-4 산출물 의존 |
| Flyway 정합 검증 (복구 후) | R-4 §4-1 | - |
| 앱 startup 정상 확인 | R-4 §4-2 | - |
| 무결성 매트릭스 재실행 | R-4 §4-3 | §4-1 RPO 손실량 측정 |
| 자본 path 정상화 확인 | R-4 §4-4 | - |
| KillSwitch state 정합 | R-4 §4-5 | - |

### 5-2. cron 셋업 절차 (사용자 단독)

본 ADR 발행 직후 사용자가 1회 등록:

1. **사용자 가이드 본문**: `docs/operations/backup-setup.md` (본 ADR 동반 발행)
2. **스크립트 경로**: `scripts/backup/pg-dump-daily.sh` · `scripts/backup/redis-snapshot.sh` (본 ADR 동반 발행)
3. **crontab -e 등록** — `docs/operations/backup-setup.md` §2 본문 참조

---

## 6. DR drill 분기 의무 (운영 헌법 §10-4 정합)

### 6-1. 분기 1회 의무 + 사용자 결재 시점 명시

| 항목 | 본문 |
|------|------|
| **주기** | 분기 1회 (3·6·9·12월 마지막 영업일 권장 — KRX 휴장 회피) |
| **사용자 결재** | 의무 — DR drill 전날 사용자 명시 결재 (계좌 격리·자본 path 영향 가시화) |
| **대상 환경** | staging (REAL 환경 격리 — staging compose 또는 별도 호스트) |
| **트리거 actor** | backend-core (DR drill 진행) + qa (Visual E2E 자동 재현) + architect (root cause·ADR amend) |
| **실패 시 escalation** | 사용자 1차 인지 + backend-core 즉시 fix + ADR amend (RPO/RTO 갱신) |

### 6-2. DR drill 시나리오 5건 (qa Visual E2E 자동 재현 의무)

1. **DB 전체 복구** — 최신 daily backup → pg_restore → Dashboard 진입 + Watchlist 갱신 + Positions 표시 + account API 200
2. **DB 부분 복구** — orders 테이블만 복구 → 주문 이력 표시 정합
3. **Flyway 결함 복구** — partial migration rollback → 앱 재기동 정상
4. **컨테이너 자체 결함** — postgres 컨테이너 재생성(볼륨 보존) → 자동 복구 5분 내
5. **Redis snapshot 복구** — RDB 복원 → KIS 토큰·Watchlist cache 자연 회복

각 시나리오 evidence: `_workspace/devlog/YYYY-MM-DD-dr-drill-quarter-N.html` (사용자 메모리 `feedback_devlog_html` 정합 — `/devlog` slash로 발행).

### 6-3. 실측 evidence 의무 매트릭스

| 측정 항목 | 목표 | 측정 방법 |
|----------|------|----------|
| RPO 실측 (데이터 손실량) | < 24h | 무결성 SQL 5건 row count diff (backup 시점 vs 복구 후) |
| RTO 실측 (복구 완료 시간) | < 30분 | drill 시작·종료 timestamp diff |
| qa Visual E2E 5 시나리오 통과율 | 100% | Playwright report (qa Visual 격상 — ADR 0094 §2-2-B) |
| Slack alert 도달 (DR drill 트리거) | 100% | NotificationDispatcher metric (ADR 0044 reuse) |
| Outbox drain 정합 (복구 후 PENDING → PUBLISHED) | 100% within 5분 | persistence-shared monitoring (P0-OPS-6 cross) |

### 6-4. DR drill 결과 → ADR amend 트리거

| evidence | amend 액션 |
|---------|----------|
| RPO 실측 > 24h (예: backup 결함) | 본 ADR §4-1 RPO LOCK 위반 — backend-core 즉시 fix + ADR amend (WAL archiving 도입 검토) |
| RTO 실측 > 30분 (예: pg_restore lag) | 본 ADR §4-2 RTO LOCK 위반 — R-4 runbook §3 절차 갱신 + ADR amend |
| Visual E2E 통과율 < 100% | qa Visual 시나리오 본문 갱신 + frontend 영향 시 hotfix |
| Slack alert 도달 < 100% | ADR 0044 routing 갱신 + P0-OPS-3(ADR 0095) amend 검토 |

---

## 7. 운영 ADR 양식 3 슬롯 (운영 헌법 §9-5 정합 — 본 ADR이 두 번째 적용 사례)

### 7-1. SLO 영향

본 ADR은 운영 헌법 §9-1 SLO 5건(ADR 0095) 직접 영향 0. BUT **§9-4(원문 §10-4) backup·DR 영역 완성** → 운영 단계 데이터 무결성 단일 권위 source 회복:

| 항목 | before (현재) | after (본 ADR + backend-core 후속 wiring) |
|------|--------------|------------------------------------------|
| backup 자동화 | 0% (수동만) | **100%** (daily 03:00 cron LOCK) |
| RPO 정의 | 0 (미정) | **24h** (PostgreSQL daily backup baseline) |
| RTO 정의 | 0 (미정) | **30분** (R-4 runbook 절차 LOCK) |
| DR drill 분기 의무 | 헌법 §10-4 본문만 (실행 spec 0) | **본 ADR §6 본문화** (5 시나리오 + qa Visual 의무) |
| Redis snapshot 정책 | 0 (docker default만) | **매일 03:30 + 3일 retention LOCK** |
| 산출물 위치 LOCK | 0 | **`_workspace/backups/postgres/daily/` + `_workspace/backups/redis/snapshots/`** |

신규 SLO 후보 (v3.8.x amend 검토):
- **backup 성공률 SLO** — > 99% (daily cron 실패율 측정)
- **DR drill 성공률 SLO** — 분기 100% (qa Visual evidence)

### 7-2. rollback plan

본 ADR은 정의·문서·스크립트 권위 ADR — 운영 코드 0 touch. revert 절차:

1. **본 PR 단일 revert** (ADR + README + master §10 + 헌법 §10 cross-link + CHANGELOG + .gitignore + 스크립트 + 사용자 가이드 add-only)
2. **cron 등록 제거** — 사용자 macOS crontab `crontab -e`로 본 ADR 정합 cron 라인 삭제 (사용자 단독 작업)
3. **기존 `_workspace/backups/` 디렉토리** — git untracked → revert 영향 0 (사용자 수동 삭제 선택)
4. **R-4 runbook §3-1·§5 cross-link** — 본 ADR 인용 row 보존 (P0-OPS-4 ADR 발행 후 cron 셋업 활성 본문은 R-4 §5-1에 baseline)

운영 단계 부적합 발견 시 (예: backup 용량 폭주 → retention 단축) — 본 ADR §2-1 retention amend로 처리. 본 ADR 자체 revert는 backup 권위 공백 발생 → **NOT recommended**.

### 7-3. 관측 매트릭스

본 ADR 적용 후 1주일 dwell + 1분기 DR drill 1회 후:

| 측정 항목 | 목표 | 측정 방법 |
|----------|------|----------|
| daily backup 성공률 | > 99% | cron 실행 로그 + `_workspace/backups/postgres/daily/` 파일 발견 빈도 |
| backup 파일 평균 용량 | < 100MB (현재 baseline ~3MB evidence — R-4 runbook §3-1) | `du -sh _workspace/backups/postgres/daily/*.dump` |
| retention rotation 정상 (7일 초과 0건) | 100% | `find _workspace/backups/postgres/daily -name "*.dump" -mtime +7 \| wc -l` = 0 |
| Redis snapshot 성공률 | > 99% | cron 로그 + `_workspace/backups/redis/snapshots/` 파일 빈도 |
| 분기 DR drill 성공률 (5 시나리오) | 100% | qa Visual Playwright report |
| RPO 실측 (drill evidence) | < 24h | 무결성 SQL 5건 row diff |
| RTO 실측 (drill evidence) | < 30분 | drill timestamp diff |
| **Visual 회귀 시나리오 (frontend 영향 시)** | qa Visual DR drill 5 시나리오 본문화 의무 (ADR 0094 §2-2-B 정합) | qa Visual dispatch (분기 DR drill 사이클) |

운영 1분기 dwell 후 발사 빈도·RPO/RTO 실측 평가 → 본 ADR amend 사이클 (architect 단독 ADR 0096 amend PR 발행 권한).

---

## 8. ADR cross-link · 후속 정합

### 8-1. ADR cross-link 매트릭스

| 관련 ADR | 본 ADR과의 관계 |
|---------|---------------|
| **ADR 0094** | 본 ADR이 §3-7 P0-OPS-4 task 본문화 + §9-4(원문 §10-4) backup 매트릭스 amend (산출물 위치 LOCK) |
| **ADR 0095** | P0-OPS-3 SLO/SLA — RTO/RPO 측정 source 가용성 cross (Prometheus query + SystemEvent metric) |
| **ADR 0088** | persistence-shared — Outbox/notification recovery 정합 (DB backup으로 Outbox table 흡수 → Kafka offset backup 본질 외) |
| **ADR 0006** | PostgreSQL 단일 + 스키마 분리 — backup 대상 DB 본질 |
| **ADR 0030** | master-architecture SoT — post-MVP TimescaleDB 전환 시 본 ADR amend 의무 (일봉 ≥1억 row 도달) |
| **ADR 0044** | Notification — Slack alert reuse for backup 실패 (backend-core P0-OPS-4 후속 wiring) |
| **ADR 0043** | KillSwitch — DR drill 시 사용자 결재 + KillSwitch ON 검토 정합 |
| **ADR 0070** | T-RECON notify bundled — DR drill 후 reconcile 강제 실행 의무 (잔고 정합 회복) |

### 8-2. 후속 ADR 권장 (v3.8.x amend 후보)

본 ADR baseline LOCK 후 운영 dwell 1분기 후 amend 권장 매트릭스:

| 후속 amend ID | trigger | 권장 시점 |
|--------------|---------|----------|
| ADR 0096 amend §2-2 — weekly backup 신규 | 운영 dwell 1분기 후 daily 7일 retention 평가 → 장기 보존 필요성 evidence | v3.8.x patch |
| ADR 0096 amend §2-3 — WAL archiving 도입 | 자본 path 결함 + RPO < 5분 필요 사용자 결재 | v3.9.0 amend |
| ADR 0096 amend §4 — RPO/RTO 임계 갱신 | 분기 DR drill 실측 evidence 기반 | v3.8.x patch |
| 신규 ADR — Kafka offset backup | Outbox 본질 외 use case 발견 (예: streaming analytics) | post-MVP |
| 신규 ADR — backup 외부 storage (S3·B2 등) | 호스트 디스크 손상 risk 사용자 결재 시 | v4.0.0 cut 검토 |

### 8-3. backend-core P0-OPS-4 후속 dispatch 본문 (권고)

본 ADR Accepted 후 backend-core 1차 dispatch 영역 (본 PR scope 외 — 별 사이클):

1. **backup 성공률 Micrometer counter wiring** — `magicjar.backup.success` counter (cron 호출 시점 → exit code)
2. **backup 실패 Slack alert** — ADR 0044 NotificationDispatcher reuse + backup 실패 시 즉시 P2 ERROR 발사
3. **persistence-shared Outbox monitoring** — P0-OPS-6 cross (drain lag SLO 측정 → DR drill 후 PENDING 누적 검증)
4. **docker compose service 검토** — 사용자 macOS crontab → docker compose service 전환 (v3.9.0+ 자동화 본격화)
5. **DR drill 자동화 script** — `scripts/dr-drill.sh` (staging 환경 자동 복구 + qa Visual 자동 trigger) — backlog

---

## 9. 트레이드오프

### 9-1. 채택 (선택지)

- **daily full dump (`-Fc` custom format)** — pg_restore 호환 + 압축 + 부분 복구 가능 + 24h RPO 충분
- **7일 retention baseline** — 운영 dwell 1주 충분 + 용량 부담 ↓ (3MB × 7 = 21MB baseline)
- **사용자 macOS crontab 등록** — 단독 운영자 가정 + 운영 부담 ↓ (docker compose service는 v3.9.0+ amend 후보)
- **Redis snapshot 매일 03:30 KST** — pg_dump 30분 후 자원 경합 회피 + 3일 retention (cache 본질)
- **RPO 24h + RTO 30분** — daily backup 정합 + R-4 runbook 절차 LOCK 정합
- **분기 DR drill + qa Visual 의무** — 운영 헌법 §10-4 정합 + Visual evidence 누적
- **`_workspace/backups/` .gitignore** — 시크릿 누출 risk + 용량 누적 risk 회피
- **R-4 runbook cross-link** — 본문 중복 회피 + 단일 권위

### 9-2. 거부 옵션 (왜 안 했는가)

| 옵션 | 거부 사유 |
|------|----------|
| WAL archiving baseline 포함 (PITR) | 운영 부담 ↑ + 호스트 디스크 용량 plan 의무 + 사용자 결재 필요 + daily backup으로 RPO 24h 충분. P2 OPS amend 후보 |
| weekly backup baseline 포함 | daily 7일 retention 정합 충분 + 운영 dwell 1분기 후 evidence 기반 amend가 본질. P1 OPS amend 후보 |
| Redis AOF 활성화 | Redis 사용 패턴은 cache + 임시 state 본질 (§3-3 매트릭스) → AOF는 운영 부담 ↑ + 본질 외 |
| Kafka offset backup baseline | Outbox table이 영속 권위 (ADR 0088 정합) + Kafka 자체는 commit log 본질 + 24h retention + at-least-once delivery → 본 ADR scope 외 |
| docker compose service로 cron 운영 | 사용자 단독 운영자 가정 + 사용자 macOS crontab 충분 + docker 컨테이너 always-on 부담. v3.9.0+ 자동화 본격화 시 amend |
| backup 외부 storage (S3·B2) | 외부 의존 ↑ + 비용 ↑ + 사용자 단독 운영자 가정 vs 호스트 디스크 충분. post-MVP (v4.0.0+) 검토 |
| backup 파일 git tracked | 시크릿 누출 risk + 용량 누적 risk + git history 폭주 → .gitignore LOCK |
| DR drill 주간·월간 빈도 | 운영 부담 ↑ + 분기 1회로 RPO/RTO evidence 충분. P1 OPS amend 후보 |
| RPO 5분 baseline (WAL archiving 필수화) | 자본 path가 한국 주식 daily settlement 정합 + 일일 정산 본질 vs 분초 RPO 필요성 0 + 운영 부담 ↑ |

---

## 10. 결과

### 10-1. 긍정

- **운영 헌법 §9-4 backup·DR 영역 완성** — backup 자동화 0 → 100% + RPO/RTO 정의 0 → 본문화
- **단일 권위 source** — backend-core·qa·architect 후속 PR이 본 ADR §2·§3·§4·§6 직접 참조
- **운영 ADR 양식 두 번째 적용 사례** — 헌법 §9-5 SLO 영향·rollback plan·관측 매트릭스 3 슬롯 적용 (ADR 0095 첫 적용에 이어 본 ADR이 두 번째)
- **RPO/RTO 본문화** — RPO 24h + RTO 30분 LOCK + R-4 runbook 절차 정합
- **DR drill 분기 의무 본문화** — qa Visual E2E 5 시나리오 + 사용자 결재 + 실측 evidence 매트릭스
- **운영 코드 0 touch** — ADR + 스크립트 + 사용자 가이드 + .gitignore add-only (PR scope LOCK)
- **v3.8.0 운영 baseline P0 진척** — P0 OPS 6건 중 4번째 task 완료 (P0-OPS-1·2·3·4)
- **사용자 단독 운영자 가정 정합** — macOS crontab + 단일 호스트 + Slack 단일 channel (ADR 0094 §9-3 정합)

### 10-2. 부정 (대응 plan)

- **WAL archiving 없음 — RPO 24h 한계** — 자본 path 결함 시 최악 23:59h 데이터 손실 risk. 운영 dwell evidence 기반 P2 OPS amend (WAL 도입 결재) 대기
- **cron wiring 0건 — 본 ADR 자체로는 backup 활성 0** — 사용자 macOS crontab 등록 1회 + backend-core P0-OPS-4 후속 wiring(Micrometer counter + Slack alert) 의무 (본 ADR §8-3 권고)
- **DR drill 자동화 0 — 분기 1회 수동** — backend-core `scripts/dr-drill.sh` 발행 (backlog) 후 분기 100% 자동 회복
- **호스트 디스크 단일 의존** — 호스트 손상 시 backup 동시 손실 risk. 외부 storage(S3·B2) 후보는 post-MVP (v4.0.0+) amend

### 10-3. SLO 영향

§7-1 본문화 — 본 ADR이 backup·DR 매트릭스 단일 권위. backup 성공률 SLO·DR drill 성공률 SLO 신규 후보 (v3.8.x amend).

### 10-4. rollback plan

§7-2 본문화 — 본 PR 단일 revert 가능 (ADR + README + master §10 + 헌법 §10 cross-link + CHANGELOG + .gitignore + 스크립트 + 사용자 가이드 add-only).

### 10-5. 관측 매트릭스

§7-3 본문화 — 1주일 dwell + 1분기 DR drill 1회 후 backup 성공률·RPO/RTO 실측·qa Visual evidence 평가.

---

## 11. 변경 이력

| 날짜 | 변경 | 결정자 |
|------|------|--------|
| 2026-05-21 | **ADR 0096 Accepted — P0-OPS-4 DR/backup 절차 본문화** (ADR 0094 §3-7 P0-OPS-4 task 본문화 + 운영 헌법 §9-4 backup 매트릭스 amend + PostgreSQL daily 03:00 KST + 7일 retention + Redis snapshot 매일 03:30 + 3일 retention + RPO 24h + RTO 30분 + R-4 runbook cross-link + DR drill 분기 의무 + qa Visual E2E 5 시나리오 + 운영 ADR 양식 3 슬롯 두 번째 적용 사례 + `_workspace/backups/` .gitignore + 사용자 macOS crontab 가이드 + backend-core P0-OPS-4 후속 wiring 5건 권고). 운영 코드 0 touch. | architect (ADR 0094 §3-7 task 본문화 의무) |
