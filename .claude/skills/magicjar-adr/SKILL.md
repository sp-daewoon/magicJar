---
name: magicjar-adr
description: magicJar 프로젝트의 아키텍처 결정을 ADR(Architecture Decision Record)로 문서화·재검토·업데이트·수정·보완한다. 새로운 기술 스택 선택, 모듈 경계 변경, 데이터 저장소 결정, LLM 공급자 변경 등 아키텍처 레벨 의사결정이 필요할 때 반드시 이 스킬을 사용. "설계 기록하자", "ADR 추가", "아키텍처 문서 업데이트", "결정 재검토"도 포함.
---

# magicjar-adr — 아키텍처 결정 기록 작성

magicJar의 모든 비가역적 기술 결정은 `_workspace/arch/adr/NNNN-title.md` 에 ADR로 남긴다. 코드는 지워져도 ADR은 남아 "왜 이렇게 했는가"를 미래에 알려준다.

## 언제 ADR을 쓰는가

- 라이브러리/프레임워크 채택 (예: TA4J vs 자체 구현, Thymeleaf vs React)
- 데이터 저장소 선택 (PostgreSQL vs TimescaleDB vs Redis Streams)
- 모듈 경계/책임 분할 변경
- 외부 API 선택 (KIS vs 키움 vs Alpaca)
- LLM 공급자·모델 결정
- 운영 환경 결정 (단일 JAR vs 컨테이너, 온프레 vs 클라우드)

## 언제 ADR을 쓰지 않는가

- 코드 스타일·네이밍 규칙 (→ 코드리뷰 또는 `CONTRIBUTING.md`)
- 사소한 메서드 시그니처 변경
- 실험적·임시 코드

## ADR 파일 구조

파일명: `_workspace/arch/adr/{4자리 번호}-{slug}.md` (예: `0003-use-timescaledb-for-candles.md`)

```markdown
# ADR {번호}: {짧고 선언적인 제목}

- 상태: Proposed / Accepted / Deprecated / Superseded by ADR-NNNN
- 작성일: YYYY-MM-DD
- 결정자: architect (협의: backend-core, …)

## 맥락(Context)

어떤 문제/요구/제약이 이 결정을 필요하게 만들었는가. 2~6문장.

## 검토한 대안(Options)

각 대안은 **간단한 비교표**로:

| 대안 | 장점 | 단점 |
|------|------|------|
| A | ... | ... |
| B | ... | ... |

## 결정(Decision)

선택한 대안과 **선택 이유**. MVP 범위·학습곡선·운영비용을 명시적으로 고려.

## 결과(Consequences)

- 긍정: 얻게 되는 것
- 부정: 감수해야 하는 것
- 후속 과제: 이 결정으로 인해 새로 필요해진 작업
```

## 작성 원칙

- **결정 이전에 쓴다.** 사후 정당화 문서가 되지 않도록, 논의 중에 Proposed로 시작.
- **번호는 증가만.** 기존 ADR을 대체할 때도 번호 재사용 금지. 새 ADR에 `Supersedes ADR-0003` 명시 후 구 ADR을 `Superseded by ADR-0017`로 업데이트.
- **단일 결정에 집중.** 한 ADR에 여러 주제를 엮지 않는다.
- **MVP 정신.** 과한 추상화를 정당화하는 ADR이라면 되짚어본다.

## 재실행/업데이트 지침

- 기존 ADR 보존이 원칙. 생각이 바뀌었으면 **새 ADR 발행** 후 구 ADR 상태를 Deprecated 또는 Superseded로 바꾼다.
- 사실관계 오류(오타·링크 끊김)는 직접 수정 허용. 다만 "결정/맥락/결과" 본문 수정은 새 ADR로.

## 변경 이력 추적

ADR 목차 파일 `_workspace/arch/adr/README.md` 에 테이블로 유지:

| 번호 | 제목 | 상태 | 날짜 |
|------|------|------|------|
| 0001 | ... | Accepted | 2026-04-25 |
