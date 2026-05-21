# magicJar backup cron 셋업 가이드

> ADR 0096 §2·§3 정합 — 사용자 단독 운영자(macOS) cron 등록 절차

## 0. 사전 조건

- macOS 환경 (Linux 사용자는 §5 참조)
- Docker Desktop 실행 + `magicjar-postgres` · `magicjar-redis` 컨테이너 running
- `scripts/backup/pg-dump-daily.sh` · `scripts/backup/redis-snapshot.sh` executable
- 호스트 디스크 여유 공간 ≥ 100MB (PostgreSQL 7일 + Redis 3일 baseline ~50MB)

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
bash scripts/backup/pg-dump-daily.sh
# 정상 종료 시 마지막 로그: "exit 0 — 정상 완료"

# Redis snapshot 1회
bash scripts/backup/redis-snapshot.sh
# 정상 종료 시 마지막 로그: "exit 0 — 정상 완료"

# 산출물 확인
ls -lh _workspace/backups/postgres/daily/
ls -lh _workspace/backups/redis/snapshots/
```

실패 시 §4 진단 매트릭스 참조.

---

## 2. cron 등록 — macOS crontab (권장 — 단독 운영자 단순화)

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
| ADR 0096 (DR/backup 단일 권위) | `_workspace/arch/adr/0096-dr-backup-procedure.md` |
| R-4 runbook (복구 절차 LOCK) | `docs/operations/runbook/R4-db-recovery.md` |
| backup 스크립트 README | `scripts/backup/README.md` |
| 운영 헌법 §9-4 (backup·DR 본문) | `_workspace/arch/agent-charter.md` |
| ADR 0094 §3-7 P0-OPS-4 | `_workspace/arch/adr/0094-ops-stage-transition.md` |

---

## 9. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-05-21 | 신규 발행 — ADR 0096 §2·§3 정합 + macOS crontab 등록 + 진단 매트릭스 + DR drill 분기 의무 cross-link (architect) |
