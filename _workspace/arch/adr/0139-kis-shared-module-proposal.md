# ADR 0139 — KIS rate limiter 공유 모듈 승격 (3중 복붙 단일화 계획)

- 상태: **Proposed (2026-07-03 — 구현 보류: 모듈 신설은 `./gradlew check` 전체 빌드 검증 필요, 본 감사 문서 Wave는 코드 무변경이므로 후속 사이클에서 구현)**
- 작성일: 2026-07-03
- 결정자: architect 발의 (2026-07-03 전 영역 감사 §5-⑮ 후속 — 사용자 결재 대기)
- 입력 권위: `_workspace/audit/2026-07-03-system-audit.md` §5-⑮
- 관계: ADR 0054(pattern-engine)·0083(indicator-impl)·0088(persistence-shared)·0092(strategy-engine) — **동일 라이브러리 모듈 분리 패턴 1:1** · ADR 0037/0116(KIS rate limit 정책 본문 — 알고리즘 무변경) · ADR 0004(모듈 구조 — 9 JVM, settings.gradle.kts 권위)

---

## §1. 컨텍스트

감사 실측: KIS rate limiter 구현이 api·batch·consumer 3벌 복붙 상태(consumer판 주석이 "동일 알고리즘" 자인). 공유 모듈 원칙(ADR 0083/0088/0092)이 KIS 어댑터 계층에는 미적용이며 이미 diverge 시작 — api↔consumer 동일 파일명 11종 중 `TraceIdKafkaProducerInterceptor`(64 vs 48줄)·`ChartCandleProperties`(101 vs 122줄) 실측 diverge, hotfix를 3벌에 각각 반영한 흔적 존재. 10 TPS LOCK·Redis 공유 bucket이라는 정책은 단일인데 코드가 3벌이라 침식이 조용히 진행된다.

## §2. 결정 제안 (Proposed)

1. **1단계 = rate limiter 1건만 승격** — domain port는 기존재하므로 구현 1개를 공유 라이브러리(신규 `kis-shared` 모듈 또는 기존 `persistence-shared` 패턴 검토)로 이동 + 모듈별 `@Configuration` 배선만 잔류. Boot main 0·domain port 의존만(indicator-impl 패턴 1:1).
2. **2단계(별도 후속 ADR)** — 나머지 동일 파일명 11종의 공유/의도적 분리 분류 후 단계적 통합. 본 ADR에서 일괄 결정하지 않음(빅뱅 회피).
3. 알고리즘·정책 값(10 TPS LOCK·priority bucket ADR 0116) **변경 0** — 위치 이동만.

## §3. 구현 보류 사유 (명시)

- 모듈 신설은 settings.gradle.kts·의존 그래프 변경 → 전체 `./gradlew check` + smoke 검증 필수. 2026-07-03 감사 개선 Wave의 거버넌스/문서 트랙 범위 밖.
- 착수 조건: 사용자 결재 + backend-core 단독 dispatch(라운드 R1 모듈 골격 → R2 이식 → R3 테스트, 개발 룰 3 정합).

## §4. 롤백

모듈 분리는 위치 이동 add-only — 실패 시 PR revert로 3벌 상태 복귀(동작 계약 무변경).
