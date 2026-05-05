---
name: 시크릿 채팅 평문 전송 시 처리 정책
description: 사용자가 채팅에 API 키·secret·계좌번호·토큰 등을 평문으로 보내면, 즉시 재발급 권고 + .env 직접 편집 가이드. 응답에서 그 키를 다시 인용하거나 echo로 .env에 쓰지 말 것.
type: feedback
originSessionId: dd617581-992a-4d2d-a1f6-2e1d32d164e9
---
사용자가 채팅에 시크릿(KIS app key·secret, ANTHROPIC_API_KEY, GEMINI_API_KEY, 모의/실 계좌번호, OAuth 토큰 등)을 평문으로 보내면:

1. **응답에서 그 키 자체를 다시 인용하지 않는다** — 추가로 transcript에 남기지 않기 위해
2. **즉시 재발급 강력 권고** — 발급처 페이지·재발급 경로 안내
3. **새 키는 사용자가 직접 `.env` 편집**하도록 가이드 (`$EDITOR .env` / `open -e .env`). Claude가 echo·cat·heredoc로 .env에 쓰는 건 그 명령어가 transcript에 또 남으므로 금지
4. **IP 화이트리스트 설정 가이드** 같이 안내 (KIS·Gemini 등 가능한 공급자 한정)
5. 키 노출 사실을 사용자에게 명확히 전달 — "괜찮다"고 둘러말하지 말 것

**Why:** 2026-04-30 세션에서 사용자가 KIS 모의계좌 app key·secret을 채팅에 평문으로 전송. 채팅 transcript·로그·캐시에 영구 기록 위험. KIS API는 IP 화이트리스트 옵션이 있어 IP 제한 없이 등록된 키는 잠재적 자산 탈취·매매 도용 위험.

**How to apply:**
- 사용자가 키를 보냈을 때 첫 응답 처음에 🚨 보안 경고 + 재발급 권고로 시작
- 그 키를 코드·설정·예제 어디에도 다시 출력하지 않기. 변수명만 사용 (`KIS_MOCK_APP_KEY`).
- `.env` 자체는 직접 읽지 않는다(별도 메모리). 키 set 여부 확인이 필요하면 사용자에게 명시 질문
- 사용자가 "그냥 진행해"라고 하더라도 최소 한 번은 보안 경고. 그 다음 사용자 결정 따름
