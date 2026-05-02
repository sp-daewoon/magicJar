# ADR 0002: Gradle 멀티모듈 구조와 모듈 경계

> **STATUS: SUPERSEDED by ADR 0019 (2026-05-03 v2 베이스라인)** — 7 수직 도메인 모듈 → 4 JVM(domain·api·batch·consumer) + frontend·pyworker로 재배치됨. 본 ADR은 참조용 보존.

- 상태: Superseded by 0019
- 작성일: 2026-04-25
- 결정자: architect (협의: backend-core)

## 맥락

자동매매 시스템은 역할이 선명한 여러 도메인(시세수집, 전략/백테스트, LLM, 주문 집행, 웹 UI)이 공존하므로, 한 모듈에 몰아넣으면 컴파일·테스트·배포 분리가 어렵고 의존 방향이 꼬이기 쉽다.

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| A. 단일 모듈 | 초기 속도 ↑ | 경계 강제 불가, 리팩터 비용 ↑ |
| B. 마이크로서비스(도메인별 서비스 분리) | 독립 배포 | 단일 개발자에 과하며, 운영 부담 폭증 |
| C. Gradle 멀티모듈 + 단일 `app` 실행 JAR (본 결정) | 경계 강제·재사용·테스트 격리, 배포는 단순 | Gradle 구성 복잡도 초기 증가 |

## 결정

다음 8개 모듈로 분리한다:

```
magic_ch1/
├── app/              # 스프링 부트 엔트리, 모든 구현체 조립
├── core/             # 도메인 모델, 포트 인터페이스, 공용 DTO (의존 없음)
├── market-data/      # KIS/DART/뉴스 어댑터, 시세·공시 수집
├── strategy/         # 지표·DSL·백테스트·주문 로직·매매일지 기록자
├── ai/               # Spring AI, 프롬프트, 구조화 출력
├── order-exec/       # 모의/실계좌 주문 어댑터
├── web/              # Thymeleaf 컨트롤러, WebSocket, 하네스 대시보드 컨트롤러
└── test-support/     # 통합 테스트 공용 픽스처 (선택 도입)
```

**의존 방향 규칙 (단방향):**
- `core` ← 모든 모듈이 의존. `core`는 타 모듈에 의존하지 않음.
- `app` ← 모든 어댑터 모듈을 의존 (조립 위치)
- `market-data`, `strategy`, `ai`, `order-exec`, `web`는 서로 직접 의존하지 않는다. 상호작용은 `core`의 포트 인터페이스와 Spring Application Event로.

**패키지 루트:** `kr.spdaewoon.magicjar.{module}`

## 결과

- 긍정: 테스트 격리, 단일 바이너리 배포 유지, 향후 서비스 분리 시 후보군 명확
- 부정: 초기 `settings.gradle.kts` / 공통 `build.gradle.kts` 작성 1회성 비용
- 후속: backend-core가 스캐폴딩 (magicjar-spring-boot-scaffold 스킬 적용)
