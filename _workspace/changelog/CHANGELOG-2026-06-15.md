# magicJar CHANGELOG

> 2026-06-15 rollover 이후 신규 기록. 이전 누적분은 `CHANGELOG-2026-06-15-pre-rollover.md`에 보존한다.

| 날짜 | 변경 내용 | 대상 | 사유 |
|------|----------|------|------|
| 2026-06-15 | **chore(backend-core): Redis key Registry 중앙화 + TTL 없는 키 copy-only migration runner** — api/consumer/batch/pattern-engine에 흩어진 Redis key 생성식을 `RedisKeyRegistry`로 모으고, 현재 운영 wire key는 그대로 유지했다. 권장 Naming Convention target은 Registry `Standard` namespace로 노출하고, 안전한 low-risk 키에 한해 `RedisNoTtlKeyMigrationRunner`를 추가했다. Runner는 기본 OFF + dry-run, `SCAN` 기반, `TTL == -1` 키만 `COPY` without replace로 복사하며 계좌·주문·리스크·KIS token 키는 자동 copy rule에서 제외한다. 중복 prefix 상수와 source/target 동일 migration rule은 삭제했다. | domain · api · consumer · batch · pattern-engine · reports | Redis naming convention 이관 준비 + 운영 Redis 장애 없는 점진 migration |
