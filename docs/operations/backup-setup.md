# magicJar backup cron 셋업 가이드

> ADR 0096 §2·§3 정합 — 사용자 단독 운영자(macOS) cron 등록 절차
> **★ 시각 가이드(HTML)**: [`backup-and-restore-guide.html`](backup-and-restore-guide.html) — 무슨 파일이 어떻게 남는지·외부 매체 복사·복원 한눈에 보기

## ★ 실태 이력 (2026-06-06)

> **2026-06-06 실태**: 본 가이드·스크립트(`pg-dump-daily.sh`·`redis-snapshot.sh`)는 2026-05-21 발행됐으나, **cron 미등록 + 백업 0회 실행** 상태였다(ADR 0096 §1-3 baseline이 "cron wiring 0건 — ADR 자체로는 backup 활성 0"이라 경고한 risk가 그대로 잔존).
>
> 2026-06-06 **첫 수동 백업 실행 + 검증 완료**: pg dump 56MB(custom format `-Fc`, TABLE DATA 56건, flyway V70 포함) + redis snapshot 71K. 추가로 `scripts/backup/pg-restore.sh`(신규, dump 짝)로 임시 DB `magicjar_restore_test`에 복원해 테이블 56건·flyway V70·candle 254만 row·user_kis_credential 2건이 운영 DB와 row-level 일치함을 실증.
>
> → **이번 강화 산출물**: ① `scripts/backup/pg-restore.sh`(복원 + dry-run + 검증) ② `docs/operations/new-pc-migration-runbook.md`(새 PC 완전 복원 3계층 런북) ③ 본 가이드 갱신(실태 이력 + 외부 매체 동기화 + 런북 cross-link).
>
> ⚠️ **아직 cron 등록은 사용자 1회 작업이 필요하다** — §2 절차로 등록해야 자동 백업이 활성화된다.

## 0. 사전 조건

- macOS 환경 (Linux 사용자는 §5 참조)
- 대상 Docker daemon 연결 + `magicjar-postgres` · `magicjar-redis` 컨테이너 running
- `scripts/backup/pg-dump-daily.sh` · `scripts/backup/redis-snapshot.sh` executable
- 호스트 디스크 여유 공간 ≥ 100MB (PostgreSQL 7일 + Redis 3일 baseline ~50MB)

NAS runtime(ADR-0141)에서는 backup script에 `MAGICJAR_DOCKER_CONTEXT=nas`를
지정한다. dump/RDB 산출물은 계속 Mac의 ignored `_workspace/backups/`에 저장된다.

Mac sleep과 무관한 PostgreSQL+Redis 자동 백업은 DSM Task Scheduler 경로를 권위로 한다.

```bash
# clean approved SHA에서 NAS stable path에 entrypoint provision
APPROVED_SHA='<merged main SHA from PR/release evidence>'
MAGICJAR_EXPECTED_GIT_SHA="$APPROVED_SHA" \
scripts/install-nas-backup-entrypoint.sh
```

DSM Control Panel → Task Scheduler에서 **root 사용자**의 사용자 정의 스크립트를 매일 03:00 KST로 등록한다.
일반 DSM 사용자는 Container Manager socket 접근 권한이 없어 fail할 수 있다.

```text
/volume1/docker/magicjar/ops/nas-task-scheduler-backup.sh >> /volume1/docker/magicjar/ops/backup.log 2>&1
```

산출물은 `/volume1/docker/magicjar/backups/sets/<KST timestamp>/` 아래의
`postgres.dump{,.sha256,.meta}`와 `redis.rdb{,.sha256,.meta}`다. 두 컨테이너의
Compose project/service label을 확인하고 fresh Redis BGSAVE와 양쪽 checksum 검증 후
timestamp directory 전체를 한 번 rename해 atomic publish한다. stale lock은 NAS boot-id와
PID/start-ticks가 실제 생존하지 않을 때만 회수한다.
installer는 DSM task를 대신 등록하거나 crontab을 우회 수정하지 않는다. Task Scheduler가
만든 artifact의 checksum·PostgreSQL restore drill을 확인하기 전에는 autonomous backup readiness를 주장하지 않는다.

확인:

```bash
ls -lh scripts/backup/*.sh
# -rwxr-xr-x  1 user  staff  ... scripts/backup/pg-dump-daily.sh
# -rwxr-xr-x  1 user  staff  ... scripts/backup/redis-snapshot.sh
```

executable 부재 시:

```bash
chmod +x scripts/backup/pg-dump-daily.sh scripts/backup/redis-snapshot.sh
```

---

## 1. 수동 1회 실행 — 검증 baseline (cron 등록 전 의무)

cron 등록 전에 수동 1회 실행으로 환경 정합 검증:

```bash
# PostgreSQL backup 1회
MAGICJAR_DOCKER_CONTEXT=nas bash scripts/backup/pg-dump-daily.sh
# 정상 종료 시 마지막 로그: "exit 0 — 정상 완료"

# Redis snapshot 1회
MAGICJAR_DOCKER_CONTEXT=nas bash scripts/backup/redis-snapshot.sh
# 정상 종료 시 마지막 로그: "exit 0 — 정상 완료"

# 산출물 확인
ls -lh _workspace/backups/postgres/daily/
ls -lh _workspace/backups/redis/snapshots/
```

실패 시 §4 진단 매트릭스 참조.

---

## 2. cron 등록 — macOS crontab (권장 — 단독 운영자 단순화)

### 2-0. 한 줄 등록 (비대화식 — vi 없이 즉시)

기존 crontab 을 보존하면서 2 라인을 append 하는 한 줄 명령 (2026-07-03 감사 §7 — 등록 마찰 제거):

```bash
( crontab -l 2>/dev/null; echo '0 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/pg-dump-daily.sh >> _workspace/backups/postgres/cron.log 2>&1'; echo '30 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/redis-snapshot.sh >> _workspace/backups/redis/cron.log 2>&1' ) | crontab - && crontab -l | grep magic_ch1
```

- 마지막 `crontab -l | grep magic_ch1` 출력으로 2 라인 등록 즉시 확인.
- **중복 실행 주의** — 이미 등록돼 있으면 라인이 2벌 생긴다. 재실행 전 `crontab -l` 확인 (또는 §2-1 수동 편집).
- 경로가 다르면 `/Users/ak-song/magic_ch1` 조정 의무 (§2-2 ★ 동일).
- (선택) 백업 실패 Slack 알림: cron 라인 앞에 `MAGICJAR_BACKUP_SLACK_WEBHOOK=<url>` env prefix 부여 시 pg-dump-daily.sh 가 실패에만 Slack 발송 (미설정이면 조용히 skip).

### 2-1. crontab -e 진입

```bash
crontab -e
```

vi/vim 진입 후 `i` 키로 insert mode 진입.

### 2-2. 다음 4 라인 추가 (ADR 0096 §2-1·§3-2 LOCK)

```cron
# ─────────────────────────────────────────────────────────────
# magicJar backup — ADR 0096 P0-OPS-4 정합
# ─────────────────────────────────────────────────────────────

# PostgreSQL daily full dump (매일 03:00 KST + 7일 retention)
0 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/pg-dump-daily.sh >> _workspace/backups/postgres/cron.log 2>&1

# Redis RDB snapshot (매일 03:30 KST + 3일 retention)
30 3 * * * cd /Users/ak-song/magic_ch1 && bash scripts/backup/redis-snapshot.sh >> _workspace/backups/redis/cron.log 2>&1
```

★ **REPO_ROOT 절대경로** — 사용자 환경 다르면 `/Users/ak-song/magic_ch1` 경로 조정 의무.

### 2-3. 저장 + 종료

`ESC` → `:wq` → Enter

성공 시 출력: `crontab: installing new crontab`

### 2-4. cron 등록 확인

```bash
crontab -l | grep magicJar -A 5
# 위 4 라인이 출력되면 성공
```

---

## 3. cron 동작 검증 (등록 후 1주일 dwell)

### 3-1. 다음 03:00 KST 이후 산출물 확인

```bash
# PostgreSQL backup 산출물
ls -lh _workspace/backups/postgres/daily/
# 예: 2026-05-22.dump (~3MB)

# cron 실행 로그
tail -20 _workspace/backups/postgres/cron.log
# 마지막 라인: "exit 0 — 정상 완료"
```

### 3-2. Redis snapshot 산출물 확인

```bash
ls -lh _workspace/backups/redis/snapshots/
# 예: dump-2026-05-22-0330.rdb (~5MB)

tail -20 _workspace/backups/redis/cron.log
# 마지막 라인: "exit 0 — 정상 완료"
```

### 3-3. 1주일 dwell 후 retention 검증

```bash
# PostgreSQL — 7일 초과 파일 0건 의무
find _workspace/backups/postgres/daily -name "*.dump" -mtime +7
# 결과 0 row 기대

# Redis — 3일 초과 파일 0건 의무
find _workspace/backups/redis/snapshots -name "*.rdb" -mtime +3
# 결과 0 row 기대
```

---

## 3-4. 외부 매체 동기화 (권고 — 호스트 손상 대비)

`_workspace/backups/`는 `.gitignore`(59행)라 repo에 **절대 안 올라간다**. 즉 호스트 디스크가 손상되면 backup도 함께 소실된다(ADR 0096 §10-2 "호스트 디스크 단일 의존" risk). **외부 매체(클라우드 폴더·USB·외장 SSD)로 1줄 rsync 동기화 권고**:

```bash
# 예: 클라우드 동기화 폴더로 backup 복사 (cron 03:40 KST — pg/redis dump 후)
40 3 * * * rsync -a --delete /Users/ak-song/magic_ch1/_workspace/backups/ ~/Library/Mobile\ Documents/com~apple~CloudDocs/magicjar-backups/ >> /Users/ak-song/magic_ch1/_workspace/backups/rsync.log 2>&1
```

> ★ **`.env`도 같은 매체에 함께 보관** — `.env`(특히 AES 마스터 키 `MAGICJAR_SECRET_MASTER_KEY`)와 pg dump는 "한 세트"다. AES 키가 없으면 dump를 복원해도 `user_kis_credential`(KIS 키)이 복호화되지 않는다. 자세한 경고는 [`new-pc-migration-runbook.md`](new-pc-migration-runbook.md) §B-1.

---

## 4. 진단 매트릭스 (cron 실패 시)

| 증상 | 진단 명령 | root cause |
|------|----------|-----------|
| `_workspace/backups/postgres/daily/` 비어 있음 | `tail -50 _workspace/backups/postgres/cron.log` | ★ cron.log 마지막 ERROR 라인 |
| `ERROR: docker 명령 부재` | `which docker` | Docker Desktop 미실행 — `open -a Docker` |
| `ERROR: magicjar-postgres 컨테이너 running 0` | `docker ps` | infra 컨테이너 정지 — `docker compose -f docker/docker-compose.infra.yml up -d` |
| `ERROR: pg_isready FAIL` | `docker logs magicjar-postgres` | DB 결함 — R-4 runbook 진입 |
| `ERROR: BGSAVE 완료 타임아웃` | `docker exec magicjar-redis redis-cli INFO` | Redis 부하 — 부하 평가 후 BGSAVE timeout 환경변수 amend 검토 |
| cron 자체 미실행 | `crontab -l` 확인 + 시스템 cron daemon 동작 확인 | macOS 권한 — "전체 디스크 접근 권한" `cron`에 부여 (System Settings → Privacy & Security → Full Disk Access → `+` → `/usr/sbin/cron` 추가) |

★ **macOS Full Disk Access 권한** — Catalina(10.15) 이후 cron이 `_workspace/backups/` 디렉토리 접근 시 권한 issue 발생 가능. 위 절차로 권한 부여.

---

## 5. Linux 환경 (참고 — 사용자 환경 외)

Linux는 systemd timer 또는 cron 둘 다 가능. 본 magicJar 운영 단계 기본은 macOS 단독 운영자 가정 (ADR 0094 §9-3). Linux 운영 시:

```bash
# /etc/cron.d/magicjar-backup 신규 파일
0 3 * * * <user> cd /path/to/magic_ch1 && bash scripts/backup/pg-dump-daily.sh >> _workspace/backups/postgres/cron.log 2>&1
30 3 * * * <user> cd /path/to/magic_ch1 && bash scripts/backup/redis-snapshot.sh >> _workspace/backups/redis/cron.log 2>&1
```

또는 systemd timer (운영 부담 ↑ — 사용자 자율).

---

## 6. cron 등록 해제 (rollback)

ADR 0096 §7-2 rollback plan 정합:

```bash
crontab -e
# §2-2 4 라인 삭제 (#magicJar backup 주석 포함)
# :wq 저장
crontab -l | grep magicJar -A 5
# 결과 0 row 기대
```

기존 `_workspace/backups/` 산출물은 git untracked → 수동 삭제 (선택):

```bash
rm -rf _workspace/backups/postgres/daily/*.dump
rm -rf _workspace/backups/redis/snapshots/*.rdb
```

---

## 7. 분기 DR drill (운영 헌법 §10-4 의무)

본 cron 가이드는 backup 활성 부분. **복구 검증은 분기 1회 DR drill 의무**.

### 7-1. 사용자 결재 절차

- DR drill 전날 사용자 명시 결재 (계좌 격리·자본 path 영향 가시화)
- staging 환경 또는 별도 호스트에서 실행 (REAL 환경 격리)

### 7-2. drill 5 시나리오 (ADR 0096 §6-2)

1. DB 전체 복구 (R-4 §3-2 pg_restore)
2. DB 부분 복구 (R-4 §3-3 특정 테이블)
3. Flyway 결함 복구 (R-4 §3-4)
4. 컨테이너 자체 결함 (R-4 §3-5)
5. Redis snapshot 복구

### 7-3. evidence 의무

- 결과 evidence: `_workspace/devlog/YYYY-MM-DD-dr-drill-quarter-N.html`
- RPO 실측 < 24h + RTO 실측 < 30분 (ADR 0096 §6-3 매트릭스)
- qa Visual Playwright report (ADR 0094 §2-2-B 정합)

---

## 8. 관련 문서

| 문서 | 위치 |
|------|------|
| **새 PC 마이그레이션 런북** (완전 복원 3계층) | `docs/operations/new-pc-migration-runbook.md` |
| **restore 스크립트** (dump 짝 — 복원·dry-run·검증) | `scripts/backup/pg-restore.sh` |
| ADR 0096 (DR/backup 단일 권위) | `_workspace/arch/adr/0096-dr-backup-procedure.md` |
| ADR 0105 (장기 1m 보관/복구 — dump 유일 복원원) | `_workspace/arch/adr/0105-candle-reconciliation-and-long-horizon-1m-retention.md` |
| R-4 runbook (복구 절차 LOCK) | `docs/operations/runbook/R4-db-recovery.md` |
| backup 스크립트 README | `scripts/backup/README.md` |
| 운영 헌법 §9-4 (backup·DR 본문) | `_workspace/arch/agent-charter.md` |
| ADR 0094 §3-7 P0-OPS-4 | `_workspace/arch/adr/0094-ops-stage-transition.md` |

---

## 9. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-21 | 신규 발행 — ADR 0096 §2·§3 정합 + macOS crontab 등록 + 진단 매트릭스 + DR drill 분기 의무 cross-link (architect) |
| 2026-06-06 | 실태 이력(cron 미등록·백업 0회 → 첫 백업 + 강화) + §3-4 외부 매체 동기화 권고 + new-pc-migration-runbook·pg-restore.sh cross-link 추가 (backend-core) |
| 2026-07-03 | §2-0 한 줄 crontab 등록 명령 추가 + pg-dump-daily.sh 실패 Slack 알림(env 게이트) 반영 — 시스템 감사 §7 대응 |
