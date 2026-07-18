# Docker 배포 토폴로지 — 현행 권위

> **권위**: 본 문서. 변경 시 직접 갱신.
> **결정 이력**: ADR-0008 (Compose 표준), ADR-0141 (NAS runtime/data plane)
> **마지막 갱신**: 2026-07-18

## 현행 규약

### 역할 분리

| 영역 | 권위 위치 | 책임 |
|---|---|---|
| Build/control plane | 사용자 Mac | Git, Gradle bootJar, Compose client, dump 보관, 검증 |
| Runtime/data plane | NAS Docker context `nas` | 승인된 core 9개 컨테이너, PostgreSQL·Kafka·Redis·Dozzle named volume |
| 비밀 설정 | Mac의 ignored `.env` | Compose가 container environment로 전달; Git/NAS repo 파일로 복제 금지 |

- NAS 작업은 항상 `docker --context nas` 또는 `MAGICJAR_DOCKER_CONTEXT=nas`를 명시한다.
- 전역 `docker context use nas`는 금지한다.
- 로컬과 NAS의 `consumer-app`을 동시에 실행하지 않는다. 표준 재배포도 source context의
  consumer가 정지했음을 fail-closed로 증명한 뒤에만 NAS consumer를 기동한다.
- NAS 연결 실패 시 로컬 healthy stack을 먼저 유지한다.
- 승인 full SHA 필수 + untracked 포함 clean tree를 강제한다. 자체 image는 Git SHA 12자리
  tag/full SHA label, infra·운영 UI는 image digest로 고정한다.
- core 9개는 `restart: unless-stopped`와 역할별 graceful-stop budget을 적용한다.

### 네트워크

- NAS host port 비공개: PostgreSQL, Kafka, Redis, api, batch, consumer.
- NAS loopback 전용: Dozzle 8888, Kafka UI 8090, frontend 3000(default).
- frontend가 `/api`·`/ws`를 내부 `api-app`으로 proxy하는 단일 entry point다.
- 외부 접속은 SSH tunnel이 기본이다. `NAS_BIND_ADDRESS`로 LAN 공개 시 공용 인터넷 port-forward를 금지한다.

### 데이터 이전

- 현재 PostgreSQL은 `scripts/migrate-docker-to-nas.sh --confirm-cutover` cold cutover만 사용한다.
- MOCK·ALLOW_REAL=false·in-flight 0·열린 position 0·빈 target volume을 모두 만족해야 한다.
- 정확한 전체 public table row count와 Flyway version parity 후에만 NAS 앱을 가동한다.
- dump local/target checksum과 backup 시점 exact row-count manifest를 검증한다.
- rollback은 NAS writer 정지·비실행을 증명한 경우에만 source consumer를 재기동한다.
- source volume과 final dump는 NAS 안정 확인 및 별도 삭제 승인 전 보존한다.

### 별도 observability 범위

`docker-compose.observability.yml`의 Prometheus·Grafana·Alertmanager 3개는 core 9개
cutover와 별도다. ADR-0141의 2026-07-18 amendment로 NAS 배포 경로는 승인됐지만
`magicjar-observability` 별도 project, remote-safe `configs.content`, digest pin, loopback port,
desired Grafana credential 검증, core 9 identity 불변을 강제한다. 실제 배포·target/rule/auth
검증 전에는 observability readiness 또는 “12개 workload 전체 NAS readiness”로 판정하지 않는다.

NAS-native backup은 `/volume1/docker/magicjar/ops/nas-task-scheduler-backup.sh`를 DSM Task
Scheduler root 사용자가 매일 03:00 KST 실행하는 combined PG+Redis set이 autonomous primary다.
설치·task 등록·restore drill은 각각 별도 운영 승인 대상이며, scheduler-triggered artifact와
restore 증거 전 readiness는 PENDING이다.

### 배포·운영 명령

```bash
# NAS 연결·runtime 사전 점검
MAGICJAR_EXPECTED_GIT_SHA="$(git rev-parse HEAD)" MAGICJAR_ENV_FILE=/absolute/path/.env scripts/deploy-nas.sh --preflight-only

# 최초 DB 포함 cold cutover
MAGICJAR_EXPECTED_GIT_SHA="$(git rev-parse HEAD)" MAGICJAR_ENV_FILE=/absolute/path/.env scripts/migrate-docker-to-nas.sh --confirm-cutover

# 이후 표준 재배포
MAGICJAR_EXPECTED_GIT_SHA="$(git rev-parse HEAD)" MAGICJAR_ENV_FILE=/absolute/path/.env scripts/deploy-nas.sh

# NAS 대상 백업·smoke·운영 snapshot
MAGICJAR_DOCKER_CONTEXT=nas scripts/backup/pg-dump-daily.sh
MAGICJAR_DOCKER_CONTEXT=nas API_URL=http://127.0.0.1:3000 scripts/smoke.sh
MAGICJAR_DOCKER_CONTEXT=nas scripts/ops-daily-snapshot.sh
```

## 적용 위치

- `docker/docker-compose.nas.yml` — host port·Mac bind mount 차단
- `scripts/lib/docker-target.sh` — 명시적 context command wrapper
- `scripts/deploy-nas.sh` — 일반 NAS 배포 진입점
- `scripts/migrate-docker-to-nas.sh` — 1회성 DB cutover
- `scripts/build-and-deploy.sh` — remote context·extra override·prebuilt image 지원
- `scripts/backup/*.sh`, `scripts/smoke.sh`, `scripts/check-inflight-drain.sh`, `scripts/ops-daily-snapshot.sh` — context-aware 운영 도구
- `docs/operations/nas-deployment.md` — 상세 실행·검증·rollback runbook

## 변경 이력

- 2026-07-16: NAS runtime/data plane 및 current PostgreSQL cold cutover 채택 (ADR-0141).
- 2026-07-18: 별도 NAS observability 3과 NAS-native combined backup 후속 승인 (ADR-0141/0096 amend).
