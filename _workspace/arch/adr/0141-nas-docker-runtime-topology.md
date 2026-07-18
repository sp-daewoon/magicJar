# ADR 0141 — Docker 실행 plane을 NAS remote context로 이전

- 상태: **Accepted (2026-07-16, 사용자(최고 관리자) 결재)**
- 작성일: 2026-07-16
- 결정자: 사용자(최고 관리자) — “docker 실행되는 영역은 nas 에서 할수 있도록” 요청 후 **전체 9개 서비스 + 현재 PostgreSQL 데이터 이관** 선택
- 관계: ADR 0008(Docker 표준) · ADR 0045(graceful shutdown) · ADR 0094(운영 단계) · ADR 0096(DR/backup) · ADR 0115(in-flight drain)
- 현행 정책: `docs/architecture/policies/deployment-topology.md`
- 실행 런북: `docs/operations/nas-deployment.md`

## §1. 컨텍스트

현재 표준은 Mac에서 JAR를 만든 뒤 Docker Desktop에 인프라 3, 앱 4, 운영 UI 2의 총 9개 컨테이너를 기동한다. NAS는 상시 가동과 Docker daemon을 제공할 수 있으나, 단순히 전역 `docker context use nas`를 적용하면 다른 프로젝트까지 원격 daemon으로 보내고 다음 결함이 생긴다.

1. 기본 Compose의 Mac bind mount가 NAS에 존재하지 않는다.
2. PostgreSQL·Kafka·Redis와 운영 UI 포트를 NAS LAN에 그대로 공개한다.
3. 로컬 PostgreSQL named volume의 기존 데이터가 NAS volume으로 자동 이관되지 않는다.
4. 로컬·NAS consumer가 동시에 실행되면 KIS WS·스케줄·주문 소비가 이중화된다.
5. 기존 backup/smoke/운영 스크립트가 암묵적으로 현재 Docker context를 사용한다.

## §2. 결정

### 2-1. 실행 토폴로지

- **Mac = build/control plane**: Git, Gradle, Compose client, 검증, dump 보관.
- **NAS = runtime/data plane**: 9개 컨테이너와 PostgreSQL·Kafka·Redis·Dozzle named volume.
- 4개 자체 image는 승인된 full Git SHA를 필수 입력으로 받고 그 12자리 immutable tag와
  full-SHA container label을 사용한다. untracked 포함 clean tree만 허용한다. stateful infra와
  Dozzle/Kafka UI도 digest로 고정한다. `p4`/`latest` 단독 rollback은 금지한다.
- 전역 Docker context는 변경하지 않는다. 모든 NAS 명령은 `MAGICJAR_DOCKER_CONTEXT=nas` 또는 `docker --context nas`를 명시한다.
- source repository와 `.env` 파일을 NAS filesystem에 복제하지 않는다. Compose client가 build context와 container environment를 remote daemon에 전달한다.

### 2-2. 네트워크 경계

- PostgreSQL, Kafka, Redis, api, batch, consumer는 host port를 공개하지 않는다.
- Dozzle·Kafka UI는 NAS loopback에만 bind한다.
- frontend만 단일 진입점이며 기본도 `127.0.0.1:3000`이다. `/api`와 `/ws`는 내부 Compose network의 api-app으로 proxy한다.
- 원격 접속은 SSH tunnel이 기본이다. LAN bind는 `NAS_BIND_ADDRESS`를 명시한 경우에만 허용하며 공용 인터넷 port-forward는 금지한다.

### 2-3. PostgreSQL cold cutover

1. source와 target Docker context가 서로 다른지 확인한다.
2. `MOCK` + `ALLOW_REAL=false`, in-flight 0, 열린 strategy position 0을 강제한다.
3. target에 기존 `magicjar_pg_data`가 있으면 자동 삭제·덮어쓰기하지 않고 중단한다.
4. NAS 이미지를 downtime 전에 native amd64로 prebuild한다.
5. source api·batch·consumer를 정지한 뒤 flat·in-flight 0을 다시 확인하고 최종 `pg_dump -Fc`를 만든다.
6. local checksum·row-count manifest를 만들고 NAS 전송 후 target-side checksum까지 일치해야 복원한다.
7. 모든 public table의 정확한 row count와 Flyway version을 source/target 간 비교한다.
8. NAS 9개 서비스 health와 runtime mode를 확인한 뒤에만 로컬 stack을 정지한다.
9. 실패 시 NAS writer 정지와 비실행을 증명한 경우에만 source stack을 재기동한다. NAS가
   unreachable하여 이를 증명할 수 없으면 split-brain 방지를 위해 source 자동 재기동도
   금지하고 수동 incident 절차로 전환한다. source volume과 dump는 삭제하지 않는다.

### 2-4. 이후 운영

- 표준 NAS 배포는 `scripts/deploy-nas.sh`다. stale JAR 방지를 위해 기존 `build-and-deploy.sh`의 bootJar→build→up→health 흐름을 재사용한다.
- 표준 재배포도 source/local consumer가 정지했음을 먼저 증명해야 한다.
- backup, restore, smoke, drain, daily snapshot은 `MAGICJAR_DOCKER_CONTEXT=nas`를 지원한다.
- REAL 배포는 `MAGICJAR_NAS_ALLOW_REAL_DEPLOY=true` 별도 게이트 없이는 차단한다.
- NAS core 9개는 `restart: unless-stopped`와 ADR-0045의 역할별 graceful stop
  budget을 적용한다. PostgreSQL뿐 아니라 Kafka·Redis도 NAS named volume으로 영속한다.

### 2-5. 선택 범위 밖

- 사용자가 승인한 이번 cutover 범위는 base Compose의 **9개 core workload**다.
- 별도 `docker-compose.observability.yml`의 Prometheus·Grafana·Alertmanager 3개는
  NAS bind/config 배포 방식과 외부 alert 실패 도메인을 별도로 설계해야 하므로 이번
  9개 이관에 포함하지 않는다. 이를 포함하기 전까지 “Docker workload 12개 전부 NAS”라고
  주장하지 않으며 observability NAS 이전은 후속 승인 항목이다.

## §3. 기각한 대안

| 대안 | 기각 이유 |
|---|---|
| 전역 `docker context use nas` | 다른 프로젝트 Docker 명령까지 NAS로 향하는 blast radius |
| 앱만 NAS, DB/Kafka/Redis는 Mac | Mac 가용성에 계속 종속되고 NAS 상시 실행 목적을 달성하지 못함 |
| NAS에 새 빈 DB | 기존 전략·캔들·설정·credential 암호문·주문 이력 단절 |
| 로컬과 NAS consumer 동시 가동 | KIS WS, scheduler, Kafka 소비 및 자본 경로 중복 위험 |
| NAS에 infra port 전체 공개 | 고정 DB password·무인증 Redis/Kafka/UI가 LAN에 노출됨 |

## §4. 운영 영향과 롤백

- **SLO 영향**: cutover 중 짧은 계획 downtime. 이후 NAS 네트워크·스토리지 가용성이 runtime SLO에 포함된다.
- **자본 영향**: 매매/리스크/주문 코드 변경 0. cutover는 MOCK·flat·in-flight 0에서만 허용한다.
- **rollback**: NAS 앱 정지 → 보존한 source stack `up -d`. source PostgreSQL volume과 migration dump는 NAS 안정 dwell 및 별도 승인 전 삭제 금지.
- **관측**: 9개 service health, Flyway success, startup structured ERROR, DB parity, outbox 신규 실패, KIS WS session-window evidence, 3개 smoke 시나리오를 분리 보고한다.

## §5. 결과

- Docker 실행과 데이터 영속 위치는 NAS로 단일화하면서 build/test 제어는 기존 Mac workflow를 보존한다.
- NAS 연결 또는 SSH key가 준비되지 않으면 배포는 fail-closed하며 로컬 healthy stack을 유지한다.
- 호스트 포트와 context 오발사를 코드화된 guard 및 fixture test로 차단한다.
