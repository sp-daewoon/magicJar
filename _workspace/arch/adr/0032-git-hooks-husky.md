# ADR 0032: Git 훅 관리 — Husky

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (협의: backend-core, 사용자 지시)

## 맥락

브랜치 전략(ADR 0009)을 실효화하려면 **푸시·커밋 시점에서 최소한의 품질 게이트**가 자동 실행되어야 한다. 로컬에서 잡을 수 있는 문제(린트 위반, 빌드 실패)는 원격/CI로 가기 전에 막는다.

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| **Husky v9** (Node 기반) | 표준, 문서 풍부, `.husky/` 가 git에 커밋됨 | Node.js·package.json 필요 (Kotlin 프로젝트에 약간 이질) |
| Pre-commit (Python) | 언어 중립 | Python 런타임 의존 |
| Lefthook (Go 바이너리) | 빠름, 의존 최소 | 채택률 낮음 |
| Gradle `gitHooks` 플러그인 | JVM 친화 | 초기 `./gradlew` 실행까지 hook 미설정 |

## 결정

**Husky v9 채택.** 이유:
- Node.js가 사용자 머신에 이미 설치됨
- `.husky/`가 저장소에 체크인되어 팀(혹은 에이전트)이 클론 즉시 동일 훅 공유
- Tailwind CLI 빌드 파이프라인도 Node 기반이므로 `package.json` 공존 자연스러움

### 구성

```
.
├── package.json          # devDependencies: husky, (선택) commitlint, lint-staged
├── package-lock.json     # 커밋
└── .husky/
    ├── pre-commit        # ktlint 검사 + 스테이지된 파일 기본 체크
    └── pre-push          # ./gradlew check (Phase B 이후 활성화)
```

### pre-commit 내용 (MVP)
1. Stage된 `*.kt` / `*.kts` 파일에 대해 `./gradlew ktlintCheck -q` (wrapper 미존재 시 skip + 경고)
2. Stage된 마크다운·YAML 파일에 대해 간단한 trailing whitespace 경고
3. `.env`·`application-local.yml` 같은 시크릿 후보 파일이 stage되면 즉시 실패 (오탐 방지 패턴 기반)

### pre-push 내용 (MVP)
1. `./gradlew check -q` (없으면 skip, Phase B 이후 활성)
2. 브랜치명 검증: `main` 직접 push 금지 메시지 안내(원격에서도 보호하지만 로컬에서 1차 차단)

### commit-msg (선택, 후속 ADR에서 commitlint 도입 시)
- Conventional Commits 형식 검증

### Husky 설치/설정 스크립트

`package.json`:
```json
{
  "name": "magicjar-hooks",
  "private": true,
  "scripts": {
    "prepare": "husky"
  },
  "devDependencies": {
    "husky": "^9.1.7"
  }
}
```

클론 직후 실행: `npm install` → `husky` 자동 설정(`prepare` 훅).

## 결과

- 긍정: 푸시 전 품질 게이트, 팀 일관성, 시크릿 우발 커밋 방지
- 부정: Node 의존이 추가됨, Gradle wrapper 생성 전까진 훅 일부가 "skip with warning" 상태로 동작
- 후속:
  - Phase B에서 Gradle wrapper 생성 직후 pre-commit/pre-push의 gradle 블록 활성화
  - commitlint + lint-staged 도입 여부는 사용성 본 뒤 결정
