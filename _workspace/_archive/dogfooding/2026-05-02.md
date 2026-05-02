# Dogfooding 1차 시도 기록 (2026-05-02 토요일)

> 1번 트랙(KIS 주문 어댑터) 머지 직후 사용자 검증 시도. **여기서 일시 중단**, 다음 세션에서 결정 후 재개.

## 현재 상태 (스냅샷)

- **9 컨테이너 healthy** (api·batch·consumer·frontend·pyworker + postgres·kafka·redis·minio·kafka-ui)
- **profile=dev** (consumer 부팅 로그 `[KIS_MODE] active=DEV (KisCredentialProvider 미등록 또는 seed 부재)` → 직후 `[SEED] default user seeded: account=50185338, mode=MOCK_KIS, hts_id=thdeodns`)
- **KillSwitch OFF** (수동 SQL `UPDATE kill_switch_state SET is_active=false WHERE scope='GLOBAL';`)
- **MockTickGenerator 활성** (1초/5종목 publish — Dashboard 실시간 갱신 정상)
- **users + user_trading_config 시드 1행 INSERT됨** (DEFAULT_USER_ID `00000000-0000-0000-0000-000000000001`, MOCK_KIS, AES 암호화 OK)

## 발견 사항

### 1. 주말이라 KIS 모의 주문 자체 거부
직접 `curl` 검증 결과:
```
{"rt_cd":"1","msg_cd":"40100000","msg1":"모의투자 영업일이 아닙니다."}
```
오늘 토요일. KIS 모의는 평일에만 주문 접수. **월요일(2026-05-04)에 진짜 검증 가능**.

### 2. RealKisOrderAdapter `EGW00202 GW 라우팅 오류` (별개 이슈)
같은 endpoint·같은 키·같은 body지만:
- **직접 curl** → `40100000 영업일 아님` (KIS 정상 응답 — gateway 통과)
- **RealKisOrderAdapter** (Spring RestClient) → `EGW00202 GW 라우팅 중 오류`

차이가 있음 — RestClient의 추가 헤더(`Accept`·`User-Agent`) 또는 hashkey 누락 의심. **평일에도 같은 에러 가능성** — 별도 hotfix 필요.

진단 후보:
- (a) hashkey 헤더 누락 (POST 주문 API는 hashkey 검증 권장 — KIS 공식 sample은 ka._url_fetch가 자동 처리)
- (b) Spring RestClient 자동 헤더 (`Accept: application/json`·`User-Agent`)와 KIS gateway 호환성
- (c) body Map 직렬화 시 빈 문자열 field 처리 (`SLL_TYPE: ""`·`CNDT_PRIC: ""`)
- (d) `Content-Length`·`Transfer-Encoding` 헤더 차이

확인 방법: 어댑터에 raw HTTP request 로깅 추가 → curl과 비교.

### 3. 운영상 발견 (작은 정리 필요)
- **`docker compose --env-file .env` 명시 필수** — 기본 working dir(`docker/`)에서 .env 자동 안 읽음. `scripts/build-and-deploy.sh` 등에 옵션 추가 필요.
- **KisModeBootLogger가 InitialUserSeeder보다 먼저 실행** — `KisCredentialProvider 미등록 또는 seed 부재` WARN 출력 후에 Seeder가 시드. misleading. `@Order` 추가 필요.
- **.env에 `MAGICJAR_SECRET_MASTER_KEY` 2건 중복 등록** — 운영 가이드 §9 master key rotation 시 정리.
- **Guard 거부 시 orders 테이블에 row 0** — spec 의도이지만 사용자가 "주문 시도했는데 안 보임"으로 혼동 가능. audit trail로 REJECTED row 남길지는 다음 세션 결정.
- **frontend 22원 입력**은 사용자 실수 (form 정상). PriceSanityGuard가 정확히 차단 (전일 종가 ±30% 범위 [43,755 ~ 81,261]).

### 4. PR #66 hotfix 완료 (회귀 차단)
1번 트랙 PR #65 머지 후 발견된 회귀 2건 hotfix:
- `ConsumerKisConfig` Bean들에 `@Profile("dev | prd")` 추가 (mock 부팅 fail 차단)
- `MAGICJAR_MARKET_MOCKTICK_ENABLED=true` default (시세 갱신 작동)

## 사용자 결정 보류 — 다음 세션 첫 액션

1번 트랙 dogfooding 진입 방향 — 3 옵션 중 사용자 검토 중:

| 옵션 | 설명 | 가시성 | 진짜 검증 |
|---|---|---|---|
| **A. mock 전환** | profile=mock으로 재기동, MockOrderSender(즉시 FILLED 시뮬). UI·STOMP·publish 흐름만 검증 | 낮음 (orders INSERT 0) | KIS 호출 0 — 도메인·인프라만 |
| **B. dev 유지 + 월요일 KIS 재시도 + 그 사이 어댑터 hotfix** | EGW00202 진단·수정 (헤더 추가·hashkey 등) | 평일까지 대기 | 월요일 KIS 모의 실 호출 |
| **C. REJECTED·실패도 orders INSERT (audit trail)** + dev 유지 | KisOrderSender 정정 — Guard reject·KisApiException 시도 orders/order_events INSERT | 모든 시도 가시화 | 월요일 KIS + 즉시 audit trail |

**architect 추천**: **C** — dogfooding 가시성·운영 trail·디버깅 모두 향상. KIS 호출 영업일 제약·미지 어댑터 버그 둘 다 운영에서 발생할 시나리오인데 사용자가 시도 자체를 못 보면 디버깅 불가.

**다음 세션 진입 시 사용자 결정 후 즉시 진행**.

## 다음 세션 첫 명령 후보

### 옵션 A 선택 시
```bash
SPRING_PROFILES_ACTIVE=mock docker compose --env-file .env -f docker/docker-compose.yml \
  up -d --force-recreate --no-deps consumer-app
```
→ Dashboard 수동 주문 → publish + STOMP 흐름 확인 (`/orders` 페이지 row 없음 — mock은 DB INSERT 안 함)

### 옵션 B 선택 시
1. EGW00202 진단 → RealKisOrderAdapter에 raw HTTP request 로깅 추가
2. curl과 비교해 차이 식별 (헤더/body)
3. hotfix → 평일까지 대기 → 월요일 dogfooding

### 옵션 C 선택 시 (추천)
1. KisOrderSender 정정 — Guard reject 분기에 `orders.save(REJECTED) + order_events.insertIdempotent(REJECTED)` 추가
2. KisApiException 분기에 동일 추가
3. spec D8 정정 (REJECTED도 audit trail에 남김) + commit
4. consumer 재기동 → 사용자 다시 매수 시도 → `/orders`에 REJECTED row 보임
5. EGW00202 별도 hotfix는 후속

## 메모리 — 사용자 의사결정 패턴

- "안전성 우선" — Guard 정확하게 작동한 것은 좋은 신호 (KillSwitch + PriceSanity 둘 다 차단 정확)
- "데이터 가시성 중요" — UI에서 보이지 않으면 사용자 입장에선 "안 됨". audit trail 가치 큼
- 빠른 진행 톤 — 결정 즉시 액션
