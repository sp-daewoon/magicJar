# NAS Docker 배포·PostgreSQL 이관 Runbook

> 권위: policy `deployment-topology.md`, ADR-0141. 실제 매매·리스크·주문 로직은 변경하지 않는다.

## 1. 목표 토폴로지

- Mac: source checkout, JDK/Gradle, Docker Compose client, `.env`, backup dump.
- NAS: Docker daemon, 승인된 core 9개 magicJar 컨테이너, PostgreSQL·Kafka·Redis·Dozzle named volumes.
- frontend만 entry point. api/ws는 nginx 내부 proxy.
- 로컬 Docker stack은 NAS 검증 완료 후 stop하며 source volume은 rollback용으로 보존한다.

## 2. 사전 조건

1. Mac과 NAS가 같은 LAN이거나 NAS Tailscale/SSH route가 살아 있어야 한다.
2. NAS SSH 공개키 인증이 비대화식으로 동작해야 한다. Docker context over SSH에 password prompt를 사용할 수 없다.
   최초/변경 host key는 `ssh-keyscan` 결과를 즉시 신뢰하지 말고 NAS 콘솔·관리 UI가
   표시하는 fingerprint와 별도 채널로 대조한 뒤 `known_hosts`에 등록한다.
3. NAS user가 Docker daemon 접근 권한을 가져야 한다.
   일반 shell key와 별도의 Docker context 전용 key를 사용한다. Mac의 `~/.ssh/config`에는
   `magicjar-nas-docker` alias를 두고 `IdentityAgent none`, `IdentitiesOnly yes`,
   `StrictHostKeyChecking yes`로 고정한다. NAS의 해당 public key는
   `no-agent-forwarding,no-port-forwarding,no-X11-forwarding,no-pty`와
   `command="/usr/bin/sudo -n /usr/local/bin/docker system dial-stdio"`를 강제한다.
   `/etc/sudoers.d`에는 같은 `docker system dial-stdio` 명령 하나만 `NOPASSWD`로 허용하며,
   무제한 Docker sudo나 shell sudo를 허용하지 않는다.
4. Docker Compose client는 `!reset`/`!override`를 지원해야 한다(Compose 2.24+; 검증 기준 v5.3.0).
5. NAS architecture는 amd64/x86_64다.
6. `.env`는 ignored/untracked이고 `MAGICJAR_MODE=MOCK`, `MAGICJAR_ALLOW_REAL=false`여야 한다.
7. tracked worktree가 clean하고 배포 SHA가 승인 SHA와 일치해야 한다.

확인:

```bash
docker context update nas --docker host=ssh://magicjar-nas-docker
docker --context nas version --format \
  'server={{.Server.Version}} os={{.Server.Os}} arch={{.Server.Arch}}'

docker context inspect nas --format '{{.Endpoints.docker.Host}}'
MAGICJAR_EXPECTED_GIT_SHA="$(git rev-parse HEAD)" \
MAGICJAR_ENV_FILE="$PWD/.env" scripts/deploy-nas.sh --preflight-only
```

현재 NAS가 `No route to host`이면 **중단**한다. 로컬 stack을 정지하지 않는다.
`Host key verification failed`도 동일하게 fail-closed이며 자동 TOFU로 우회하지 않는다.

## 3. 최초 이관

### 3-1. dry evidence

```bash
npm run test:nas-deployment
bash -n scripts/deploy-nas.sh scripts/migrate-docker-to-nas.sh

MAGICJAR_GIT_SHA="$(git rev-parse HEAD)" \
MAGICJAR_IMAGE_TAG="$(git rev-parse --short=12 HEAD)" \
docker compose --env-file "$PWD/.env" \
  -f docker/docker-compose.yml \
  -f docker/docker-compose.dev.yml \
  -f docker/docker-compose.nas.yml config >/dev/null
```

### 3-2. cold cutover

```bash
MAGICJAR_ENV_FILE="$PWD/.env" \
MAGICJAR_EXPECTED_GIT_SHA="$(git rev-parse HEAD)" \
MAGICJAR_SOURCE_DOCKER_CONTEXT=desktop-linux \
MAGICJAR_TARGET_DOCKER_CONTEXT=nas \
NAS_BIND_ADDRESS=127.0.0.1 \
  scripts/migrate-docker-to-nas.sh --confirm-cutover
```

스크립트는 다음 순서를 강제한다.

1. source/target context 분리 및 target remote endpoint 확인.
2. MOCK/ALLOW_REAL=false, in-flight=0, open position=0.
3. target 기존 container/`magicjar_{pg,kafka,redis}_data` 부재. 기존 데이터는 자동 삭제하지 않는다.
4. JAR fresh build + Git SHA tag NAS native image prebuild.
5. source writer(api/batch/consumer) stop 후 in-flight·open position 0 재검사.
6. final custom-format dump, SHA-256, exact row-count manifest 생성.
7. target postgres-only start, target-side checksum 일치 확인 후 restore.
8. source/target 전체 public table exact row count + Flyway version 비교.
9. NAS 9개 service start/health + runtime mode 확인.
10. 성공 후 local stack stop. source volume과 dump 보존.

## 4. 접속

기본 bind는 NAS loopback이다. Mac에서 tunnel을 연다.

```bash
ssh -N \
  -o IdentityAgent=none \
  -o IdentitiesOnly=yes \
  -o UpdateHostKeys=no \
  -i "$HOME/.ssh/id_ed25519" \
  -L 3000:127.0.0.1:3000 \
  -L 8888:127.0.0.1:8888 \
  -L 8090:127.0.0.1:8090 \
  janghoesoo@192.168.45.161
```

- 앱: http://127.0.0.1:3000
- Dozzle: http://127.0.0.1:8888
- Kafka UI: http://127.0.0.1:8090

LAN에서 직접 frontend를 열어야 할 때만 `NAS_BIND_ADDRESS=192.168.45.161`을 배포 명령에 명시한다. router 공용 인터넷 port-forward는 금지한다.

## 5. 배포 후 검증

### 5-1. runtime

```bash
MAGICJAR_GIT_SHA="$(git rev-parse HEAD)" \
MAGICJAR_IMAGE_TAG="$(git rev-parse --short=12 HEAD)" \
docker --context nas compose \
  --env-file "$PWD/.env" \
  -f docker/docker-compose.yml -f docker/docker-compose.dev.yml -f docker/docker-compose.nas.yml ps

docker --context nas exec magicjar-postgres psql -U magicjar -d magicjar -Atc \
  "SELECT max(version) FROM flyway_schema_history WHERE success=true;"
```

실제 검증은 다음을 각각 판정한다.

- 9개 service up/healthy 및 `restart: unless-stopped`/graceful-stop 적용.
- 4개 자체 image tag가 승인 Git SHA prefix이고 revision label은 full SHA.
- api·batch·consumer startup structured ERROR/FATAL/FlywayException 0.
- runtime MOCK + ALLOW_REAL=false.
- migration 시 저장한 table count parity 유지.
- 신규 failed outbox/system ERROR가 cutover 이후 생성되지 않음.
- KIS WS는 장중 수요가 있을 때만 `WS_CONNECTED`, H0UNCNT0/H0UNASP0 증거를 판정하며 수요 0은 PENDING/idle이다.

별도 observability Compose의 Prometheus·Grafana·Alertmanager 3개는 core 9개 cold
cutover와 별도 readiness다. ADR-0141 2026-07-18 amend로 NAS overlay 경로는 승인됐지만,
실제 3개 배포와 auth/target/rule 검증 전에는 observability 또는 12 workload readiness로
판정하지 않는다.

### 5-2. smoke

SSH tunnel을 연 상태에서:

```bash
MAGICJAR_DOCKER_CONTEXT=nas \
API_URL=http://127.0.0.1:3000 \
  scripts/smoke.sh
```

SKIP이 있으면 전체 PASS가 아니라 DEGRADED로 보고한다.

## 6. 이후 표준 재배포

```bash
MAGICJAR_EXPECTED_GIT_SHA="$(git rev-parse HEAD)" \
MAGICJAR_ENV_FILE="$PWD/.env" scripts/deploy-nas.sh
```

- 전역 context를 바꾸지 않는다.
- source/local `magicjar-consumer` 정지가 증명되지 않으면 fail-closed한다.
- consumer 포함 시 target DB의 in-flight drain을 확인한다.
- REAL/ALLOW_REAL=true이면 별도 `MAGICJAR_NAS_ALLOW_REAL_DEPLOY=true` 승인 게이트 없이는 중단한다.
- Claude discovery bridge는 Mac localhost sidecar라 NAS 배포에서 자동 비활성이다.

## 7. Backup

Mac이 켜져 있는 동안 NAS context에서 dump를 Mac의 ignored backup 경로로 가져온다.

```bash
MAGICJAR_DOCKER_CONTEXT=nas scripts/backup/pg-dump-daily.sh
MAGICJAR_DOCKER_CONTEXT=nas scripts/backup/redis-snapshot.sh
```

초기 전환 완료 조건에는 migration dump에 생성된 checksum·row-count sidecar와 해당
dump를 명시한 `pg-restore.sh --file <pre-nas-cutover.dump> --target-db magicjar_restore_test --drop-after`
exact-parity drill을 포함한다. 일반 daily dump는 live
writer snapshot이므로 checksum + `pg_restore --exit-on-error` + Flyway failed=0을 검증한다.
Mac sleep과 무관한 NAS-native entrypoint는 다음 경로로 checksum 검증해 provision한다.

```bash
APPROVED_SHA='<merged main SHA from PR/release evidence>'
MAGICJAR_EXPECTED_GIT_SHA="$APPROVED_SHA" \
scripts/install-nas-backup-entrypoint.sh
```

고정 경로 `/volume1/docker/magicjar/ops/nas-task-scheduler-backup.sh`는 repository checkout
없이 local Docker CLI로 `magicjar-postgres`와 `magicjar-redis`를 백업하며 Compose label·Redis
token lease·free-space·fresh BGSAVE·SHA-256·`pg_restore --list`·exact-six 검증·atomic
set-directory rename·7일 retention을 강제한다.
DSM Task Scheduler에서 root 사용자·매일 03:00 KST로
등록하고 scheduler-triggered artifact + restore drill을 확인하기 전에는 자동 backup을
주장하지 않는다. installer는 Synology crontab을 직접 수정하지 않는다.

## 7.1 Observability overlay

```bash
APPROVED_SHA='<merged main SHA from PR/release evidence>'
MAGICJAR_EXPECTED_GIT_SHA="$APPROVED_SHA" \
MAGICJAR_ENV_FILE="$PWD/.env" \
scripts/deploy-nas-observability.sh preflight

MAGICJAR_EXPECTED_GIT_SHA="$APPROVED_SHA" \
MAGICJAR_ENV_FILE="$PWD/.env" \
scripts/deploy-nas-observability.sh up
```

NAS overlay는 remote-safe `configs.content`, NAS-only scrape config, pinned digest, loopback port, Grafana auth,
external `magicjar_default` network를 사용한다. core 9개를 recreate하지 않는다.
Alertmanager webhook 미설정은 blackhole/DEGRADED이며 수신 실증 전 notification readiness는 PENDING이다.

## 8. Rollback

NAS health, DB parity, runtime mode 또는 smoke에 실패하면:

```bash
# NAS writer 정지
MAGICJAR_GIT_SHA="$(git rev-parse HEAD)" \
MAGICJAR_IMAGE_TAG="$(git rev-parse --short=12 HEAD)" \
docker --context nas compose --env-file "$PWD/.env" \
  -f docker/docker-compose.yml -f docker/docker-compose.dev.yml -f docker/docker-compose.nas.yml \
  stop api-app batch-app consumer-app

# 다음 조회 자체가 실패하거나 writer가 남아 있으면 여기서 중단 — source 기동 금지
target_names="$(docker --context nas ps --format '{{.Names}}')" || exit 1
if grep -Eq '^magicjar-(api|batch|consumer)$' <<<"$target_names"; then
  echo 'target writer still running' >&2
  exit 1
fi

# 로컬 source stack 복귀
docker --context desktop-linux compose --env-file "$PWD/.env" \
  -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d
```

- target writer 정지와 비실행을 확인한 뒤에만 local source consumer를 재기동한다.
- NAS가 unreachable하여 target 정지를 증명할 수 없으면 source도 자동 재기동하지 않고 incident로 전환한다.
- target volume을 즉시 삭제하지 않는다.
- source `magicjar_pg_data`와 `pre-nas-cutover-*.dump`를 보존한다.
- 양쪽 consumer 동시 실행이 없는지 context별 `docker ps`로 확인한다.
- DB 역이관이 필요하면 새로운 dump/restore 작업이므로 별도 사용자 승인 후 수행한다.
