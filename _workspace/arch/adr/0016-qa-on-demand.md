# ADR 0016: QA On-Demand 정책 (MVP 단계)

- 상태: Accepted (**보강 2026-05-01** — 시나리오 모드 트리거)
- 작성일: 2026-04-25 (보강: 2026-05-01)
- 결정자: architect (사용자 직접 진단)
- 관계: **ADR 0011 개정** (스크린샷 게이트 적용 범위 축소), **ADR 0030 통합** (오케스트레이터=architect)

## 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-04-25 | 최초 채택 |
| 2026-05-01 | **시나리오 모드 트리거 명시** — On-Demand 호출 시 architect가 `.spec.ts` 작성 → `scripts/qa-run.mjs scenario` 실행. 7 트리거 조건은 그대로 유지. spec 2026-05-01-qa-defect-loop-design.md 참조 |

## 맥락

ADR 0011은 "UI/사용자 흐름에 닿는 모든 PR에 스크린샷 + APPROVED 보고서 필수"를 정했다. MVP 단계에서 운영해 본 결과:

- **PR을 쪼갤 인센티브 사라짐** — 어차피 게이트 한 번이니까 큰 덩어리로 묶음
- **게이트 통과 의식 형식화** — 스크린샷 캡처 도구도 미설치된 상태로 통과 시키는 패턴
- **개발 속도 저하** — 단순 변경도 며칠씩 걸림
- **사용자 부담 증가** — 매 PR에 검수 부담

사용자 표현: "지금 막 만드는 단계인데 그렇게 하면 서비스 코드만 나뻐질 수 있으니깐 '프로덕트한 코드'로 개발하되 서비스에 더 충실해보자."

게이트는 **risk × frequency**가 의미 있을 때 가치가 있다. MVP 단계엔 frequency가 너무 높아서 게이트가 형식이 됐다.

## 결정

**QA는 On-Demand**. 기본 PR은 셀프 + 자동 검사로 진행하고, 아래 7개 트리거 조건 중 하나라도 해당할 때만 QA 에이전트 호출.

### QA 트리거 조건 (이 중 하나라도)

1. **돈 흐름 변경** — `OrderSender`, 실계좌 모드, 안전 게이트(daily-loss·position-pct 등)
2. **공용 도메인 모델 변경** — `core/domain/*`의 깨는 변경. 다수 모듈 파급
3. **새 사용자 흐름 UI** — 새 화면/워크플로우 (스크린샷 가치 있을 때)
4. **보안 경계** — 인증·키 처리·시크릿 마스킹 로직
5. **이전 버그 회귀 영역** — 한 번 깨졌던 곳 재변경
6. **Phase 통합·실배포 직전**
7. **사용자 또는 메인 세션이 명시 판단**

PR 본문 양식에 `QA: not required (사유)` 또는 `QA: required (조건 #N)` 한 줄 명시 의무.

### 기본 흐름 (90% 케이스)

```
agent/<a>/<task> → PR(base=main) → 사용자 승인 → 머지
```

자동 검사:
- pre-commit: 시크릿 차단 + ktlint
- pre-push: `./gradlew check` + 빌드 통과
- 단위 테스트 포함 의무 (에이전트 정의에 명시)

메인 세션(Claude)이 머지 전 일상적 검토 — **게이트가 아니라 자연스러운 단계**.

### "프로덕트한 코드" 기준 (게이트 대신 코드 품질로)

- 단위 테스트 커버리지 70%+ 지향 (강제 아님, 측정만)
- 공개 API·도메인 모델 KDoc 1줄 이상
- 에러 처리는 sealed class·Result 타입 (예외 throw 최소화)
- 외부 호출은 Resilience4j Circuit Breaker (KIS·LLM)
- 시크릿은 환경변수만, 코드/로그 노출 금지
- 로그 구조화(Logback JSON) + 마스킹
- 각 모듈에 `README.md` 짧게 (실행·테스트·주요 엔트리)

이는 **PR 게이트가 아니라 코드 자체의 기준**. 매 PR마다 게이트로 강제하지 않지만, 메인 세션이 셀프 리뷰 시 위 기준을 체크.

### Sunset 조건 (실서비스 진입 시 강화 게이트로 회귀)

- `magicjar.mode=REAL` + `magicjar.allow-real=true` 동시 도달 시
- 외부 사용자에게 노출 시
- 이때 새 ADR로 "전 PR QA 강제 모드" 발행 → 본 ADR sunset

### ADR 0011과의 관계

ADR 0011의 "스크린샷 양식·캡처 도구·QA 보고서 양식" 부분은 **유효 유지**. 다만 적용 범위가 "모든 UI PR"에서 "위 7개 트리거 시"로 축소.

## 결과

- 긍정: PR 사이클 단축, 사용자 승인 부담 ↓, 개발 속도 ↑, 게이트 의식 형식화 차단
- 부정: 일부 회귀 가능성 — 단위 테스트·린트·셀프 리뷰로 보완. 트리거 조건이 누락 없이 적용되는지 모니터링 필요
- 후속:
  - `magicjar-boundary-qa` 스킬에 트리거 조건 + 호출 절차 반영
  - `.github/pull_request_template.md`에 `QA:` 한 줄 의무 추가
  - `agents/qa.md`에 on-demand 모델 명시

## 시나리오 모드 트리거 (2026-05-01)

7 트리거 조건 발동 시, 또는 사용자 명시 호출 시:

1. architect(=오케스트레이터=메인 세션)가 ADR 0011·0022 베이스로 Playwright `.spec.ts` 작성 (ADR 0030 자율 규약)
2. `scripts/qa-run.mjs new` → ISSUE-NNN 채번
3. `scripts/qa-run.mjs scenario --issue ISSUE-NNN --spec <path> --attempt before`
4. 사용자 시각 검토 → 디펙 확정
5. 수정 PR 머지 후 `scripts/qa-run.mjs scenario --attempt after`
6. `scripts/qa-run.mjs close --pr <N>` → ISSUE FIXED + 인덱스 갱신

상세 spec: `docs/superpowers/specs/2026-05-01-qa-defect-loop-design.md` §4 흐름.
