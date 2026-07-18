# R-4: DB 복구·Backup

> ADR 0094 §3-7 P0-OPS-5 · 운영 헌법 §9-4 정기 backup · **ADR 0096 P0-OPS-4 DR/backup 단일 권위** (스크립트·산출물·RPO/RTO·DR drill 본문화 완료 — 2026-05-21)

## 0. 사용 시점

- `docker ps`에 `magicjar-postgres` 미존재 또는 status `unhealthy`
- `docker logs magicjar-api` startup 로그에 `FlywayException` 또는 `Connection refused`
- 사용자 화면 자본 path 차단 (account API 500 + position 표시 불가)
- table drop · query 폭주 · DB 파일 무결성 손상 의심
- Flyway migration 실패 (V44 이후 신규 migration)
- 분기 DR drill (ADR 0094 §10-4 — qa Visual E2E 자동 재현 의무)

R-1 §2-3 분기 LOCK — DB 분류 시 본 runbook 진입.

---

## 1. 상태 확인 (Diagnose)

### 1-1. 컨테이너 상태

```bash
# postgres 컨테이너 상태
docker --context nas ps -a | grep magicjar-postgres
# 정상: STATUS=Up X hours (healthy)
# 결함: Exited 또는 Up X hours (unhealthy)
```

### 1-2. DB 가용성 확인 (pg_isready)

```bash
# postgres ready 체크
docker --context nas exec magicjar-postgres pg_isready -U magicjar -d magicjar
# 정상: "/var/run/postgresql:5432 - accepting connections"
# 결함: "no response" 또는 "connection refused"
```

### 1-3. Flyway 정합 확인

```bash
# Flyway info — 적용된 migration 목록 + 실패 row 확인
docker --context nas exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "SELECT installed_rank, version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 20;"
```

`success = false` row 발견 시 Flyway 부팅 차단 — § 3 복구 진입.

### 1-4. 앱 startup 로그 grep

```bash
# api 부팅 결함 root cause
docker --context nas logs --since 5m magicjar-api 2>&1 | grep -E "FlywayException|HikariPool|Failed to start|caused by:" | head -30

# 일반 패턴:
# "FlywayException: Validate failed: Migration checksum mismatch"
# "FlywayException: Migration V44__system_event.sql failed"
# "HikariPool: Connection is not available"
```

### 1-5. 무결성 매트릭스 5건 (R-3 §6 통합)

```sql
-- 1. 핵심 테이블 row count baseline
SELECT 'orders' AS tbl, COUNT(*) FROM orders UNION ALL
SELECT 'position', COUNT(*) FROM position UNION ALL
SELECT 'kill_switch_state', COUNT(*) FROM kill_switch_state UNION ALL
SELECT 'system_event', COUNT(*) FROM system_event UNION ALL
SELECT 'stomp_event', COUNT(*) FROM stomp_event UNION ALL
SELECT 'reconcile_run', COUNT(*) FROM reconcile_run UNION ALL
SELECT 'order_event_outbox', COUNT(*) FROM order_event_outbox UNION ALL
SELECT 'flyway_schema_history', COUNT(*) FROM flyway_schema_history;

-- 2. 최신 sequence 정합 (PK 자동증가 정상)
SELECT pg_get_serial_sequence('orders', 'id') AS seq, last_value
FROM orders, LATERAL pg_sequences
WHERE schemaname = 'public' AND sequencename = 'orders_id_seq';

-- 3. orders.status pending 잔여 (KillSwitch ON 시 cancel 흐름 결함 추정)
SELECT status, COUNT(*) FROM orders
GROUP BY status ORDER BY COUNT(*) DESC;

-- 4. KillSwitch state 분실 검증 (사용자별 1 row 의무)
SELECT user_id, enabled, updated_at FROM kill_switch_state;

-- 5. Outbox drain 정합 (ADR 0088 — persistence-shared)
SELECT topic, status, COUNT(*) FROM order_event_outbox
GROUP BY topic, status;
-- → status='PUBLISHED'가 절대 다수 의무. PENDING이 100+ 누적 시 drain lag risk.
```

---

## 2. 격리 (Isolate)

### 2-1. 자본 path 보호

DB 결함 → 잔고·position·KillSwitch 모두 영향 → **즉시 KillSwitch ON 검토** (R-3 §3-1).

단, KillSwitch도 `kill_switch_state` 테이블 의존 → DB 자체 결함 시 토글 불가. 이 경우 frontend OrderRouter 정지 시간 짧으면 자연 격리 (사용자 주문 발사 불가).

### 2-2. 앱 정지 (DB 손상 시 추가 write 차단)

```bash
# api·batch·consumer 정지 — DB 추가 write 차단
docker --context nas stop magicjar-api magicjar-batch magicjar-consumer

# frontend는 정지 안 함 (사용자에게 격리 상태 표시 — "데이터 없음")
```

---

## 3. 복구 (Recover)

### 3-1. 백업 위치 (ADR 0096 §3-4 amendment)

NAS runtime의 autonomous primary는 다음 combined set이다.

- **위치**: `/volume1/docker/magicjar/backups/sets/<KST timestamp>/`
- **구성**: PostgreSQL custom dump + fresh Redis RDB + 각 SHA-256/meta, 정확히 6개 파일
- **주기/retention**: DSM Task Scheduler 매일 03:00 KST + combined set 7일
- **entrypoint**: `/volume1/docker/magicjar/ops/nas-task-scheduler-backup.sh`
- **readiness**: scheduler-triggered artifact와 PostgreSQL restore/Redis checksum 증거 전 PENDING

기존 `_workspace/backups/postgres/daily/` 및 `_workspace/backups/redis/snapshots/`는
Mac의 manual/off-host 보조 경로다. `scripts/backup/pg-dump-daily.sh`와
`scripts/backup/redis-snapshot.sh`를 계속 사용할 수 있지만 autonomous primary의 근거가 아니다.
상세 설치·검증은 `docs/operations/backup-setup.md`를 따른다.

```bash
# 사용자가 scheduler evidence에서 선택한 exact set 이름만 입력한다.
SET_NAME=20260718-030000
[[ "$SET_NAME" =~ ^[0-9]{8}-[0-9]{6}$ ]] || exit 1
APPROVED_SHA='<approved full SHA from release evidence>'
[[ "$APPROVED_SHA" =~ ^[a-f0-9]{40}$ ]] || exit 1
LOCAL_SET="_workspace/backups/postgres/nas-drill/$SET_NAME"
mkdir -p "$LOCAL_SET" && chmod 0700 "$LOCAL_SET"

# approved Compose reference와 running image ID를 대조한다.
APPROVED_PG_IMAGE="$(git show "$APPROVED_SHA:docker/docker-compose.nas.yml" |
  python3 -c 'import re,sys; m=re.search(r"(?ms)^  postgres:\n(?:(?:    .*|\s*)\n)*?    image:\s*(\S+)\s*$",sys.stdin.read()); print(m.group(1) if m and "@sha256:" in m.group(1) else "")')"
[[ -n "$APPROVED_PG_IMAGE" ]] || exit 1
[[ "$(docker --context nas inspect magicjar-postgres --format '{{.Config.Image}}')" == "$APPROVED_PG_IMAGE" ]] || exit 1
PG_IMAGE_ID="$(docker --context nas inspect magicjar-postgres --format '{{.Image}}')"
[[ "$PG_IMAGE_ID" =~ ^sha256:[a-f0-9]{64}$ &&
   "$PG_IMAGE_ID" == "$(docker --context nas image inspect "$APPROVED_PG_IMAGE" --format '{{.Id}}')" ]] || exit 1

# uncommitted marker·symlink·extra artifact가 없는 exact-six set만 허용한다.
docker --context nas run --rm --pull never --network none --read-only \
  --entrypoint sh --cap-drop ALL --security-opt no-new-privileges \
  -v /volume1/docker/magicjar/backups:/backups:ro \
  "$PG_IMAGE_ID" -ceu '
    dir="/backups/sets/$1"
    [ ! -L "$dir" ] && [ -d "$dir" ]
    [ "$(find "$dir" -mindepth 1 -maxdepth 1 -print | wc -l | tr -d " ")" = 6 ]
    for file in postgres.dump postgres.dump.sha256 postgres.dump.meta redis.rdb redis.rdb.sha256 redis.rdb.meta; do
      [ ! -L "$dir/$file" ] && [ -f "$dir/$file" ]
    done
  ' sh "$SET_NAME"

for FILE in postgres.dump postgres.dump.sha256 redis.rdb redis.rdb.sha256; do
  docker --context nas run --rm --pull never --network none --read-only \
    --entrypoint cat --cap-drop ALL --security-opt no-new-privileges \
    -v /volume1/docker/magicjar/backups:/backups:ro \
    "$PG_IMAGE_ID" "/backups/sets/$SET_NAME/$FILE" > "$LOCAL_SET/$FILE"
done
(cd "$LOCAL_SET" && \
  shasum -a 256 -c postgres.dump.sha256 && \
  shasum -a 256 -c redis.rdb.sha256)
```

★ **현 단계 evidence**: ADR 0096 amendment와 자동 backup 경로 구현 완료. DSM task가
생성한 exact set 및 아래 isolated restore drill evidence 전 readiness는 PENDING이다.

### 3-2. 복구 — pg_restore

```bash
# 기본 검증: 운영 DB 무손상 isolated restore 후 자동 DROP
MAGICJAR_DOCKER_CONTEXT=nas \
MAGICJAR_EXPECTED_DOCKER_ENDPOINT=ssh://magicjar-nas-docker \
  scripts/backup/pg-restore.sh \
    --file "$LOCAL_SET/postgres.dump" \
    --target-db magicjar_restore_test \
    --drop-after

# 운영 DB 전체 복원은 §2 격리 + 사용자 별도 명시 승인 후에만 실행한다.
# MAGICJAR_DOCKER_CONTEXT=nas \
# MAGICJAR_EXPECTED_DOCKER_ENDPOINT=ssh://magicjar-nas-docker \
#   scripts/backup/pg-restore.sh \
#     --file "$LOCAL_SET/postgres.dump" \
#     --target-db magicjar --confirm-prod
```

### 3-3. 부분 복구 (특정 테이블만)

```bash
# 예: orders 테이블만 복구
docker --context nas cp "$LOCAL_SET/postgres.dump" magicjar-postgres:/tmp/restore.dump
docker --context nas exec magicjar-postgres pg_restore \
  -h localhost -U magicjar -d magicjar \
  --data-only --table=orders \
  /tmp/restore.dump
docker --context nas exec magicjar-postgres rm -f /tmp/restore.dump
```

### 3-4. Flyway 결함 복구 (migration 실패)

migration 파일 자체 결함 (예: V44 SQL syntax error) → fix 후 재배포:

```bash
# 1) flyway_schema_history에서 실패 row 제거
docker --context nas exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "DELETE FROM flyway_schema_history WHERE success = false;"

# 2) 영향받은 partial migration 수동 rollback (테이블 drop 등)
# 예: V44 partial — system_event 테이블 부분 생성
docker --context nas exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "DROP TABLE IF EXISTS system_event CASCADE;"

# 3) migration SQL fix (api/src/main/resources/db/migration/V44__system_event.sql)
# 4) 앱 재기동 — Flyway 자동 재실행
docker --context nas compose -f docker/docker-compose.yml -f docker/docker-compose.nas.yml \
  --env-file "$PWD/.env" start api-app
```

### 3-5. Postgres 컨테이너 자체 결함 (이미지 손상·볼륨 결함)

```bash
# 1) 정지 + 컨테이너 삭제 (볼륨 유지)
docker --context nas compose -f docker/docker-compose.yml -f docker/docker-compose.nas.yml \
  --env-file "$PWD/.env" stop postgres
docker --context nas rm magicjar-postgres

# 2) 재생성 (볼륨 그대로 — 데이터 보존)
docker --context nas compose -f docker/docker-compose.yml -f docker/docker-compose.nas.yml \
  --env-file "$PWD/.env" up -d postgres

# 3) healthy 도달 대기 (보통 15~30초)
until docker --context nas exec magicjar-postgres pg_isready -U magicjar -d magicjar; do
  sleep 2
done
```

### 3-6. 볼륨 자체 손상 (최후 수단 — full restore)

```bash
# ★ 데이터 전체 손실 risk — 백업 존재 확인 후만
PG_VOLUME="$(docker --context nas inspect magicjar-postgres --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Name}}{{end}}{{end}}')"
[[ -n "$PG_VOLUME" ]] || exit 1
[[ "${CONFIRM_DELETE_PG_VOLUME:-}" == "$PG_VOLUME" ]] || { printf 'set CONFIRM_DELETE_PG_VOLUME=%s after separate approval\n' "$PG_VOLUME"; exit 1; }
docker --context nas stop magicjar-postgres
docker --context nas rm magicjar-postgres
docker --context nas volume rm "$PG_VOLUME"
docker --context nas compose -f docker/docker-compose.yml -f docker/docker-compose.nas.yml \
  --env-file "$PWD/.env" up -d postgres

# pg_restore (§3-2 절차)
```

---

## 4. 검증 (Verify)

### 4-1. Flyway 정합 확인

```bash
docker --context nas exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "SELECT version, description, success FROM flyway_schema_history WHERE success = false;"
# 결과 0 row 기대
```

### 4-2. 앱 startup 정상 (Flyway 통과)

```bash
# 앱 재기동
docker --context nas compose -f docker/docker-compose.yml -f docker/docker-compose.nas.yml \
  --env-file "$PWD/.env" up -d api-app batch-app consumer-app

# 5분 startup 로그 — Flyway 통과 + Spring Context 정상 evidence
docker --context nas logs --since 2m magicjar-api 2>&1 | grep -E "Flyway|Started|HikariPool" | head -10
# 정상:
# "Flyway Community Edition X.Y.Z by Redgate"
# "Successfully validated NN migrations"
# "Started MagicjarApiApplication in NN.NNN seconds"
# "HikariPool-1 - Start completed"
```

### 4-3. 무결성 매트릭스 5건 재실행

§1-5 SQL 5건 재실행 → 백업 시점 row count와 비교. count 차이 발생 시 audit row 의무.

### 4-4. 자본 path 정상화 확인

```bash
# docs/operations/nas-deployment.md §5의 approved SSH tunnel
# (-L 3000:127.0.0.1:3000)이 별도 terminal에서 active인 상태에서
# frontend proxy를 통한 account API 200 응답을 확인한다.
curl -s -o /dev/null -w "%{http_code}\n" \
  http://127.0.0.1:3000/api/account/inquire-balance \
  -H "X-User-Id: <userId>"
# → 200

# Dashboard 진입 → 잔고 표시 + Watchlist 갱신 + Positions 정상
```

### 4-5. KillSwitch state 정합

```bash
# kill_switch_state 백업 시점 enabled 값과 비교
docker --context nas exec magicjar-postgres psql -U magicjar -d magicjar \
  -c "SELECT user_id, enabled, reason, updated_at FROM kill_switch_state;"
```

복구 후 enabled=true 잔존 시 R-3 §3-2 수동 OFF 결재 (사용자 단독).

---

## 5. backup 정책 (운영 헌법 §9-4 — backend-core 책임 + ADR 0096 단일 권위)

### 5-1. NAS DSM Task Scheduler (ADR 0096 2026-07-18 amend)

autonomous primary는 NAS의 단일 combined PostgreSQL+Redis task다. installer와 DSM 등록은 각각 명시적 운영 승인 후 수행한다. 세부 절차와 exact-six artifact 계약은 `docs/operations/backup-setup.md`가 권위다.

```text
schedule : daily 03:00 KST
command  : /volume1/docker/magicjar/ops/nas-task-scheduler-backup.sh >> /volume1/docker/magicjar/ops/backup.log 2>&1
output   : /volume1/docker/magicjar/backups/sets/<YYYYMMDD-HHMMSS>/
```

★ 현 단계: installer 실행·DSM task 등록·scheduler-triggered restore drill evidence 전까지 `PENDING`. Mac의 `pg-dump-daily.sh`와 `redis-snapshot.sh`는 manual/off-host fallback이며 autonomous primary가 아니다.

### 5-2. 즉시 backup (긴급 시)

DSM Task Scheduler에서 승인된 같은 task를 **Run**한다. 별도 `docker exec pg_dump`로 combined-set 계약과 Redis lease를 우회하지 않는다. 완료 후 `BACKUP_PASS` 로그, exact-six regular files, 두 checksum을 확인한다.

### 5-3. 분기 DR drill (ADR 0094 §10-4 의무)

분기 1회:
1. 최근 backup으로 staging 환경 복구
2. qa Visual E2E 시나리오 자동 재현 (Playwright 5 시나리오)
3. account API + Dashboard 정상 표시 확인
4. 결과 evidence: `_workspace/devlog/YYYY-MM-DD-dr-drill.html`

---

## 6. post-mortem

R-1 §6 통합. 추가 evidence:
- DB 결함 빈도 ≥ 분기 1회 → P0-OPS-4 DR ADR 갱신 + retention 정책 LOCK
- Flyway migration 실패 ≥ 분기 1회 → migration 검증 절차 갱신 (CI 도입 검토 — 사용자 메모리 `feedback_no_github_actions` 정합 시 husky pre-push gradle check로 흡수)
- Outbox drain lag P95 > 30초 → 운영 헌법 §10-1 SLO 위반 — backend-core capacity plan
- 무결성 SQL 5건 결과 baseline 누적 → 분기 1회 SystemEvent metric 추가 검토

### 6-1. ADR cross-link

- **ADR 0096 P0-OPS-4** ★ — DR/backup 단일 권위 (PostgreSQL 매트릭스 + Redis 매트릭스 + RPO 24h + RTO 30분 + DR drill 분기 + 스크립트 + 사용자 가이드)
- **ADR 0094 §3-7 P0-OPS-4** — task 본문화 (ADR 0096이 본문화 완료)
- **ADR 0094 §9-4** — postgres pg_dump 매일 03:00 + 7일 retention + 분기 DR drill (ADR 0096 본문화)
- **ADR 0095 P0-OPS-3** — SLO/SLA + alert routing (RTO/RPO 측정 source cross)
- **ADR 0088** — persistence-shared Outbox (drain lag 모니터링 — P0-OPS-6 cross)
- **ADR 0044** — Notification (backup 실패 Slack alert reuse)
- **ADR 0043** — KillSwitch (DR drill 시 사용자 결재 정합)
- **ADR 0070** — T-RECON notify bundled (DR drill 후 reconcile 강제)
- **ADR 0006** — PostgreSQL 단일 + 스키마 분리 (기술 스택 베이스)
- **ADR 0030** — master-architecture (post-MVP TimescaleDB 전환 — 일봉 ≥1억 row 도달 시 ADR 0096 amend 트리거)

---

## 7. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-07-18 | **ADR 0096 amend 반영** — NAS DSM combined PostgreSQL+Redis task를 autonomous primary로 전환. Mac split cron은 manual/off-host fallback으로 강등하고, NAS exact-six set 선택·검증·isolated restore 경로 및 PENDING runtime evidence gate를 추가. |
| 2026-05-21 | **ADR 0096 cross-link 보강** — §3-1 백업 위치 + §5-1 cron 셋업 + §6-1 ADR cross-link 매트릭스 갱신. ADR 0096 P0-OPS-4 DR/backup 단일 권위 본문화 완료(스크립트·산출물·RPO 24h·RTO 30분·DR drill 분기). `scripts/backup/pg-dump-daily.sh` + `scripts/backup/redis-snapshot.sh` + `docs/operations/backup-setup.md` 신규 발행. (architect) |
| 2026-05-21 | 신규 발행 — ADR 0094 §3-7 P0-OPS-5 · backend-core 1차 dispatch. pg_isready·Flyway info·무결성 SQL 5건·pg_restore·부분 복구·볼륨 손상 절차 매트릭스. P0-OPS-4 DR ADR 발행 후 cron 셋업 활성. |
