# magicJar (마법의항아리)

> 돈이 알아서 차는 마법의 항아리 — 개인 맞춤형 한국 주식 자동매매 시스템

Spring Boot 3.x + Spring AI + 한국투자증권 KIS OpenAPI 기반. **백테스트 → 모의투자 → 실전** 순서로 단계적 개발을 목표로 한다.

## 상태

**초기 부트스트랩 단계.** 아직 애플리케이션 코드는 비어 있으며, Claude Code 하네스(에이전트 팀 + 스킬)만 구성되어 있다. 다음 단계는 `magicjar-orchestrator` 스킬을 트리거해 스캐폴딩·구현·검증을 진행하는 것.

## 핵심 스택 (예정)

- Java 21, Spring Boot 3.3+, Gradle Kotlin DSL (멀티모듈)
- Spring AI (ChatClient, 기본 공급자 Anthropic Claude)
- KIS OpenAPI (REST + WebSocket, 실계좌/모의계좌)
- PostgreSQL / Redis (캐시·실시간 시세)
- Thymeleaf + HTMX + Chart.js (MVP 대시보드)
- Resilience4j, Bucket4j (외부 API 안정성)

## AI 기능 (예정)

1. 매매 전략 생성·자연어 설명
2. 뉴스·공시 감성 분석 (DART + 네이버 금융)
3. 포트폴리오 리밸런싱 조언
4. 자연어 → 차트 조건식 변환 + 종목 발굴

## 실행 방법 (구축 후)

```bash
# 개발 프로파일(모의계좌)
./gradlew :app:bootRun --args='--spring.profiles.active=mock'
```

필수 환경변수(예정):

```
KIS_MOCK_APP_KEY=...
KIS_MOCK_APP_SECRET=...
KIS_ACCOUNT_CANO=...
KIS_ACCOUNT_PRDT=01
ANTHROPIC_API_KEY=...
DART_API_KEY=...
```

## Claude Code 하네스

본 저장소는 Claude Code 하네스로 자동화된다. `CLAUDE.md` 참고.
