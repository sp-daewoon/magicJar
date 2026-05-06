# ADR 0001: 시스템 비전 v2 — 룰 기반 본체 + AI 옵션 + MCP 서버 미래

- 상태: Accepted
- 작성일: 2026-05-03
- 결정자: architect (opus 4.7 1M) — 사용자 비전 정정(2026-05-02) 직접 인용
- master §위치: `docs/architecture/master-architecture.md` v2 §1·§3·§10
- 선행 폐기: 1차안 LLM 공급자 ADR ("AI 본체 결정권" 가정 — 본 ADR로 superseded)

---

## 1. 맥락

이전 architect 1차안은 magicJar를 "AI quant trading platform"로 정의했다. 시그널 → AI Decision Maker → 주문 흐름이 정상 흐름의 노드로 박혀 있었다. 사용자(2026-05-02) 명시 정정:

> "AI가 매매를 하는건 허용하는게 아니다. 신호에 의한이고 AI는 옵션이다 명심해야한다 AI를 접목시킬 수 있는거지 이 시스템에 대한 MCP를 새로 만들어서 활용할 수 있게 하는거지 AI가 메인이 되면 안됨!!"

> "기본 오프라인으로 마스터 데이터만으로도 종목이 추출 가능한 시스템이 기본 목적이고 AI가 해당 추출 종목 및 차트의 지표 여러 분석을 옵션으로 코멘트 밑 기업분석을 목표로함 AI가 그냥 이거 산다 만다의 결정권을 갖는 시스템이 아님"

1차안 비전은 폐기. v2 본체 비전을 ADR로 박는다.

## 2. 결정

magicJar의 **시스템 본체는 룰 기반 quant trading 시스템**이다. AI 자동매매 플랫폼이 아니다. 4 행위자:

| 행위자 | 책임 |
|---|---|
| 사용자(`sp-daewoon`) | 시스템 설계자·전략 작성자·검증자·운영자. 종목 universe·전략 DSL·위험 한도 정의. |
| 시스템(magicJar) | 종목 마스터 + 지표 + DSL + 시그널 → 자동 주문. AI·외부 API·인터넷 끊겨도 동작. |
| AI(Claude·Gemini·OpenAI) | **옵션 분석 보조**. 사용자 클릭 시 호출. 종목 코멘트·기업 분석·차트 해석·지표 의미. **매매 트리거 0**. |
| AI 클라이언트(Claude·Cursor 등) | **미래** — magicJar의 MCP 서버 호출. 우리가 server, AI가 client. |

### 2-1. 핵심 원칙 5건

1. **시스템 본체 = 룰 기반 quant**. 종목 마스터 + 지표 + DSL + 시그널 → 자동 주문. AI 호출 0.
2. **AI = 옵션 분석 보조**. 사용자 트리거 시 코멘트만. 매매 트리거 X.
3. **MCP 서버 제공이 미래 목표**. magicJar가 MCP 서버를 노출 — Claude/Cursor가 client.
4. **지표는 source 추상화 plug-in**. KIS Strategy Builder 80지표는 1차 import. TA-Lib·자체·KRX 후속 (ADR 0021 참조).
5. **개발 진행 — 코드 먼저 완성 → 테스트 마지막 일괄**. QA 게이트 배제. 단일 책임. API 빌딩 한 스텝씩 순차.

### 2-2. 1차안 v1과 명확히 다른 점

| 영역 | 1차안 v1 (폐기) | v2 (정식) |
|---|---|---|
| 본체 | "AI quant trading platform" | "룰 기반 quant trading 시스템" |
| AI 위상 | 시스템 흐름의 의사결정 노드 | 사용자 트리거 시 옵션 코멘트 |
| 매매 트리거 | AI가 BUY/SELL 결정 | DSL 룰 평가 결과만 |
| 오프라인 동작 | 명시 없음 | 모든 본체 컴포넌트의 1순위 검증 기준 |
| MCP | 우리가 client (KIS MCP 호출) | 우리가 server (Claude/Cursor가 호출) |

## 3. 결과·영향

- **W1·W2·W3 트랙은 AI 호출 0** — 코드·테스트·prompt 디버깅 모두. W4 진입 전 AI provider import 0건 검증 의무.
- **모든 본체 컴포넌트는 오프라인 동작 1순위 검증** — KIS REST·WS·LLM 끊겨도 영속 candle + mock + last-day-close fallback으로 차트·지표·screener·DSL 평가가 동작해야 함.
- **W4 진입 시점부터** AI provider 코드 추가 OK, 단 사용자 트리거 한정 + 5분 prompt cache + `MAGICJAR_AI_ENABLED=false` default.

## 4. 검토 대안

- **(a) AI 직접 매매 가정 유지** (1차안) — 사용자 비전 위반, 즉시 폐기.
- **(b) AI 완전 배제** — 사용자 의도 "AI 옵션 보조는 활용"과 정합 X.
- **(c) v2 (채택)** — 룰 본체 + AI 옵션 + MCP 서버 미래의 3-tier.

## 5. References

- master-architecture.md §1 (시스템 비전), §3 (데이터 플로우), §10 (ADR 세트)
