# KIS-first Raw Fetch 의무 — 현행 권위

> **권위**: 본 문서.
> **결정 이력**: 운영 헌법 §6 (`_workspace/arch/agent-charter.md`), 사용자 메모리 "KIS-first 정책"
> **마지막 갱신**: 2026-05-08 (Living Policy 분리)

---

## 현행 규약

KIS API 연동 시 **자체 추론·관념적 설계 금지**. 다음 SoT를 1순위 인용:

| 출처 | URL | 용도 |
|------|-----|------|
| **공식 SDK 레포** | https://github.com/koreainvestment/open-trading-api | tr_id·endpoint·payload 정합 |
| **MCP 서버** | `mcp__kis-code-assistant__*` | 코드 생성 시 raw search |
| **공식 문서** | https://apiportal.koreainvestment.com | 응답 스펙 보강 |

### 코드 작성 시 의무

1. `tr_id` 또는 endpoint를 코드에 적기 전 — open-trading-api 레포에서 동일 케이스 grep
2. 인용 출처를 코드 주석 또는 ADR 본문에 명시:
   ```kotlin
   /**
    * raw 출처: https://github.com/koreainvestment/open-trading-api/blob/main/.../inquire_ccnl.py
    * tr_id: FHKST01010100
    */
   ```
3. 응답 schema 변경 시 — 자체 추론 X, raw fetch 후 본 문서 갱신

## 적용 영역

- `api/.../infrastructure/kis/` — REST 어댑터 (KisAuthValidator·KisRestPort 등)
- `consumer/.../infrastructure/kis/` — REST + WS 어댑터
- `batch/.../infrastructure/kis/` — 종목마스터·calendar·ksdinfo

## v3.1.0 시점 인용 인벤토리

`_workspace/baseline/v3.1.0/03-use-sequences.md` §7 부록 참조 — 17건 raw 인용.

---

## 위반 사례 (재발 방지 가시화)

PR-21 시점에 사용자 비판: *"kis 데이터 규격 확인 안하고 막 만들어서 꾸겨넣은거잖아"*. 결과:
- `_workspace/specs/pr-21-kis-header-raw-verification.md` baseline 보고서 발행
- architect SKILL에 "KIS-first raw fetch 의무 5단계" 추가
- 본 정책 명문화

---

## 변경 이력

- 2026-05-08: Living Policy로 분리 (ADR-0053, PR #248)
- 2026-05-XX: 운영 헌법 §6 명문화 (PR-26)
- 2026-05-XX: PR-21 baseline 검증 + architect SKILL 의무 추가
