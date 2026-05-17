---
id: T-20260513-2157-dart-ssl-handshake
agent: market-data
model: sonnet
branch: hotfix/dart-ssl-handshake
status: DONE
startedAt: 2026-05-13T21:57:11.145+09:00
completedAt: 2026-05-17T11:43:07.133+09:00
pr: null
parentPhase: ?
---

# 목표
DART OpenAPI SSL handshake_failure hotfix — WebClient(Reactor Netty) → java.net.http.HttpClient 전환

## 결과물 체크리스트
- [x] WebClientConfig.kt dartWebClient 빈 제거
- [x] DartCorpCodeRepository java.net.http 전환
- [x] DartDisclosureAdapter java.net.http 전환
- [x] gradle check GREEN
- [ ] batch 재배포 CorpCode 로드 성공 evidence

## 의존성
- (필요 시 기록)

## 마지막 활동
2026-05-13T21:57:11.153+09:00 — 작업 시작

## 비고
