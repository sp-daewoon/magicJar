# ADR 0005: 하이브리드 런타임 — api / batch / consumer 3 JVM 앱

- 상태: Accepted
- 작성일: 2026-04-27
- 결정자: architect (협의: backend-core)
- 관계: ADR 0004(MSA 재구성)와 함께 효과 발생

## 맥락

ADR 0004로 모듈을 런타임 축으로 재배치했지만, 실제 실행 단위(JVM 프로세스)를 몇 개로 둘지는 별도 결정이다.

자동매매에서 동시에 굴러가야 할 책임:
1. REST 엔드포인트 응답 (사용자·프런트엔드 호출)
2. 스케줄 작업 (시가/종가 정리, 일일 백필)
3. WebSocket fan-out (실시간 시세·신호 push)
4. 무거운 배치 (백테스트, EOD 리포트, 일일 캔들 백필)
5. Kafka 컨슈머 (시세 집계, 전략 실행, 주문 디스패치, 매매일지)

이를 한 JVM에 다 묶으면 백테스트가 REST 지연을 유발하고, WS 끊김·Kafka rebalance 폭풍이 사용자 화면을 깨뜨린다.

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| A. 단일 JVM (Phase B 방식) | 운영 단순 | 부하 격리 불가, 배포/스케일 단위 1개 |
| B. 5+ JVM (책임 1:1) | 완전 격리 | 운영 복잡, 단일 운영자에 과도 |
| C. **3 JVM (api+scheduler+WS / batch / consumer)** (본 결정) | 부하 격리 + 운영 단순 사이 균형 | api에 스케줄·WS 동거 — Phase E+에서 분리 후보 |

## 결정

**3 JVM 앱 + 1 Python 워커 + 1 React SPA로 하이브리드 런타임을 구성한다.**

| 앱 | 책임 | 주요 의존 |
|----|------|---------|
| **api-app** | REST 엔드포인트, `@Scheduled` 정기 작업, STOMP/WebSocket 실시간 push, 백테스트 트리거(batch-app HTTP/Job 호출), 외부 어댑터(KIS·Yahoo·DART·Naver) | spring-boot-starter-web, -websocket, spring-kafka(일부) |
| **batch-app** | Spring Batch 잡 — DailyCandleBackfill, EodReport, BacktestRun | spring-boot-starter-batch |
| **consumer-app** | Kafka 컨슈머/프로듀서 — KisWsBridge, TickAggregator, StrategyRunner, OrderDispatcher, JournalRecorder | spring-kafka |
| **frontend** | React SPA, nginx 정적 서빙 — ADR 0009 | Vite build → nginx |

### 왜 api에 스케줄·WS를 같이 두는가

- **`@Scheduled`**: 가벼운 1분 단위 핑·정리 작업은 별도 프로세스 띄울 가치가 낮음. 무거운 일배치는 batch-app이 책임.
- **STOMP/WebSocket**: 사용자 세션 종속 → REST와 같은 프로세스에 두는 편이 세션·인증 일관성 유리.
- 둘 다 **Phase E+에 부하 폭증하면 분리 후보**(별도 ADR로).

### 왜 consumer를 분리하는가

- Kafka 리스너는 백프레셔·rebalance·long-running 처리(Spring AI 호출 포함)가 잦아, REST 스레드 풀과 격리하지 않으면 사용자 응답이 흔들림.
- StrategyRunner·OrderDispatcher는 **순서·재시도·DLT 정책**이 REST 핸들러와 전혀 다른 운영 모델.

### 왜 batch를 분리하는가

- 백테스트 1회는 수십초~수분 CPU 소모. 같은 프로세스에 두면 GC/STW가 사용자 응답을 깎음.
- 운영 시 batch만 큰 인스턴스/주기적으로만 띄워 비용 최적화 가능.

### 통합 게이트웨이는 두지 않는다 (MVP)

- 프런트 → api-app 직접 호출. nginx(frontend 컨테이너) → api-app 프록시.
- API Gateway / BFF 도입은 외부 사용자 트래픽 발생 시 ADR로 정식화.

## 결과

- 긍정: 부하 격리 + 운영 복잡도 적정선. 각 앱이 독립 Dockerfile·릴리스(ADR 0008).
- 부정: 로컬 개발 시 동시에 띄울 프로세스 5개(JVM 3 + py 1 + nginx 1). compose가 이를 흡수.
- 후속:
  - 각 앱 `application-{api,batch,consumer}.yml` 분리
  - 헬스체크 엔드포인트: JVM = `/actuator/health`, frontend = nginx default
  - api와 batch 사이 백테스트 트리거는 MVP에선 HTTP, Phase E+ 에서 Kafka `backtest.request.v1`로 옮길 후보

## Links

- spec: `docs/superpowers/specs/2026-04-27-msa-restructure-design.md` §2
- 관련 ADR: 0019, 0020, 0023, 0024
