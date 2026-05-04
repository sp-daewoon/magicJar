# R2-A1 도그푸딩 가이드 — KIS 시세 WS 체결가

작성일: 2026-05-04  
작성자: market-data  
관련 PR: agent/market-data/track2-r2-a1-quote-ws  

## 사전 조건

- `.env` 파일에 실전 KIS 키 설정 완료
  - `KIS_APP_KEY=...`
  - `KIS_APP_SECRET=...`
  - `KIS_HTS_ID=thdeodns` (체결통보용, 미설정 시 체결통보만 비활성 — 시세는 정상)
  - `SPRING_PROFILES_ACTIVE=dev,real-kis`
  - `MAGICJAR_MARKET_MOCKTICK_ENABLED=false`
- consumer 코드 변경 포함 JAR 재빌드 필요

## 절차

### 1. 빌드 및 재기동

```bash
# 1. JAR 빌드
./gradlew :consumer:bootJar

# 2. consumer 컨테이너만 재빌드 + 기동
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d --build consumer

# 전체 재기동이 필요하면:
docker compose -f docker/docker-compose.yml -f docker/docker-compose.dev.yml up -d --build
```

### 2. KIS WS 연결 로그 확인

```bash
docker logs magicjar-consumer --follow | grep -E "KIS_WS|APPROVAL|QUOTE_SUBSCRIBER"
```

기대 로그:
```
[KIS_WS_APPROVAL] fetching approval_key from https://openapi.koreainvestment.com:9443
[KIS_WS_APPROVAL] approval_key fetched, cached until ...
[KIS_WS_ROUTER] starting, wsUrl=ws://ops.koreainvestment.com:21000
[KIS_WS_ROUTER] connected to ws://ops.koreainvestment.com:21000
[KIS_QUOTE_SUBSCRIBER] subscribing 5 code(s): [005930, 000660, 035720, 005380, 051910]
[KIS_WS_ROUTER] subscribe trId=H0UNCNT0 trKey=005930
...
```

### 3. Kafka tick 수신 확인

```bash
docker exec magicjar-kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic market.tick.v1 \
  --from-beginning \
  --max-messages 5
```

장중(09:00~15:30)이라면 KIS_WS source tick이 도착해야 함:
```json
{"code":"005930","at":"...","price":75000,"volume":500,"source":"KIS_WS","stale":false,"asOf":null}
```

### 4. Frontend 차트 갱신 확인

브라우저에서 `http://localhost:3000/symbol/005930` 접속  
→ 장중이라면 1초 이내 새 캔들·현재가 갱신 확인.

### 5. Dashboard Watch List 갱신 확인

`http://localhost:3000` 접속  
→ 005930·000660·035720·005380·051910 카드 가격이 변동함.

## 문제 해결

| 증상 | 원인 | 대응 |
|---|---|---|
| `approval_key fetch failed` 로그 | `KIS_APP_KEY`/`KIS_APP_SECRET` 미설정 | `.env` 확인 후 재기동 |
| WS connect 실패 로그 | 방화벽 또는 KIS 서버 점검 | `curl -I ws://ops.koreainvestment.com:21000` 확인 |
| tick 미수신 (장중) | WS 구독 frame 응답 없음 | consumer 로그에서 SUBSCRIBE SUCCESS 확인 |
| `stale image` 증상 | JAR 재빌드 후 `--build` 미포함 | `docker compose ... up -d --build consumer` |
