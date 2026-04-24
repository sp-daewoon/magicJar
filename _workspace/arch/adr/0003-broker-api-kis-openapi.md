# ADR 0003: 증권사 API로 한국투자증권 KIS OpenAPI 선택

- 상태: Accepted
- 작성일: 2026-04-25
- 결정자: architect (협의: market-data)

## 맥락

KOSPI/KOSDAQ 자동매매를 하려면 공식 증권사 API가 필요하다. 개인 개발자가 실질적으로 사용 가능한 주요 선택지: KIS OpenAPI, 키움 OpenAPI+, eBest xingAPI, 토스증권(2024년 현재 개인 API 공개 제한적).

## 검토한 대안

| 대안 | 장점 | 단점 |
|------|------|------|
| A. **KIS OpenAPI** | REST + WebSocket, JVM 친화적 HTTP, 모의계좌 무료, 공식 문서·예제 풍부, macOS/Linux OK | WS 동시 구독 약 40종목 상한, 일부 엔드포인트 TR ID 복잡 |
| B. 키움 OpenAPI+ | 오래된 레퍼런스 많음 | COM 기반·Windows 전용, JVM 연동 까다로움 (JACOB·Win32), macOS 개발 배제 |
| C. eBest xingAPI | 수수료 경쟁력 | Windows DLL 기반, JVM 친화적이지 않음 |

## 결정

**한국투자증권 KIS OpenAPI**를 기본 증권사 API로 선택. 모의계좌 서버(`openapivts.koreainvestment.com:29443`)를 기본 실행 환경으로 하고, 실계좌(`openapi.koreainvestment.com:9443`)는 명시적 프로파일로만 활성화.

## 결과

- 긍정: macOS에서 개발 가능, REST/WS로 클린한 구조, 실계좌/모의 분리 쉽고 무료
- 부정: WS 구독 상한 40 → 전략이 많은 종목을 동시에 추적하려면 우선순위 큐/회전 구독 필요
- 후속:
  - market-data가 `magicjar-kis-openapi` 스킬로 구현
  - WebSocket 구독 상한 대응 설계는 별도 ADR 후속(0009 예정)
  - 실계좌 활성화는 "안전 게이트 5종 이상 통과 + 사용자 명시 승인"이 조건 (ADR 0006 참조)
