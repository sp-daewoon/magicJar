# ADR 0005: LLM 공급자와 Spring AI 추상화

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (협의: ai-engineer)

## 맥락

magicJar의 4대 AI 기능(감성 분석·전략 설명·리밸런싱 조언·자연어→DSL)은 모두 LLM 호출에 의존한다. 비용·품질·네트워크 정책·오프라인 개발 요구가 엇갈린다.

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| A. OpenAI 전용 | 레퍼런스 많음 | 한국어 품질은 최신 Claude보다 낮음 보고 |
| B. **Anthropic(Claude) 기본 + Ollama 로컬 폴백** | 한국어 품질·안전성 양호, 로컬 모델 폴백 | Spring AI Anthropic starter 최신 추적 필요 |
| C. 로컬 전용(Ollama) | 비용 0, 프라이버시 | 소비자 하드웨어 품질·속도 제한 |
| D. 다중 공급자 라우터 | 비용 최적화 | 초기 오버엔지니어링 |

## 결정

**B안: Spring AI `ChatClient`/`ChatModel` 추상화 위에서 기본 공급자는 Anthropic Claude.**

- 기본 모델: `claude-sonnet-4-6` (비용/품질 균형). 중요도·길이에 따라 `claude-opus-4-7` 선택 가능.
- 폴백/로컬 개발: Ollama의 한국어 지원 모델(qwen2.5, llama3.1 계열) — 인터페이스만 동일, 품질은 평가용.
- 모든 LLM 호출은 **구조화 출력(JSON Schema)** 기반. 문자열 파싱 금지.
- 프롬프트는 `resources/prompts/{feature}/{version}.st` 파일로 관리.
- API 키는 `ANTHROPIC_API_KEY` 환경변수. 미설정 시 AI 기능은 `disabled=true` 상태로 부팅 (ai-engineer가 `ChatModel` 빈이 없을 때의 경로를 지원).

## 결과

- 긍정: 공급자 교체가 설정으로 가능, AI 비활성 상태로도 시스템 가동(매매일지·백테스트는 영향 없음)
- 부정: 두 공급자를 꾸준히 테스트해야 함 (A/B 벤치를 `_workspace/ai/model-bench.md`에 누적)
- 후속: 
  - MVP 직후 A/B 벤치 1회 수행하여 기본 모델 확정 변경 검토
  - 프롬프트 캐시(Anthropic prompt caching) 도입 → 반복 감성 분석 비용 절감
