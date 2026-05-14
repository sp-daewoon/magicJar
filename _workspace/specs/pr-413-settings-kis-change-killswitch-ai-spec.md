# PR-413+ spec — SettingsModal 풀세트 (KIS 키 변경 + KillSwitch + AI 키 사전 정합)

> **권위 ADR**: [ADR 0073](../arch/adr/0073-settings-kis-change-killswitch-ai-credentials.md) — 결정 본문 ·§1~§12 LOCK
> **상위 의제**: backlog §44 (KIS 키 변경 풀세트) + §45 (KillSwitch + AI 키 카테고리 일부 흡수)
> **사이클**: v3.6.5 patch
> **작성**: 2026-05-14 architect (사용자 자율 위임 — "§44~§45부터 하자")

---

## 1. 개요

ADR 0073 §1~§12 LOCK에 따른 sub-agent 디스패치 spec. **Phase 분리 LOCK 의무** — Phase 1·3 add-only 병렬 가능 + Phase 2 직렬(Phase 1 endpoint 머지 후).

### 1-1. 산출 종합 (3 sub-agent)

| Phase | sub-agent | 영역 | 신규 PR | 신규 파일 (예상) | 갱신 파일 (예상) |
|---|---|---|---|---|---|
| **Phase 1** | backend-core | api + domain | 1 PR | 6~8 | 4~6 |
| **Phase 3** | consumer | consumer | 1 PR | 1 | 1~2 |
| **Phase 2** | frontend | frontend SPA | 1 PR | 7~9 | 3~4 |
| **합계** | — | — | **3 PR** | **14~18** | **8~12** |

### 1-2. Phase 의존성

```
Phase 1 (backend-core) ───┐
                           ├──→ Phase 2 (frontend)  ──→ 통합 verify
Phase 3 (consumer) ────────┘
```

- Phase 1·3 **add-only 충돌 0** (영역 다름) → **병렬 디스패치 OK** (orchestrator 결정)
- Phase 2는 Phase 1 endpoint 머지 후만 시작 (REST URL fix 의무)

---

## 2. Phase 1 — backend-core (REST + Kafka producer + Redis invalidate)

### 2-1. 디스패치 요약

**sub-agent**: backend-core
**영역**: `api` 모듈 (controller + service + adapter + DTO)
**branch**: `agent/backend-core/pr-413-settings-kis-change-backend`
**author**: `bot+backend-core@magicjar.local`
**PR title**: `feat(backend-core): KIS 키 변경 endpoint 활성화 + KillSwitch 토글 endpoint + Kafka publish (ADR 0073 Phase 1)`

### 2-2. 신규 파일 매트릭스

| # | 경로 | 책임 |
|---|---|---|
| 1 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SystemConfigController.kt` | `/api/system-config/kill-switch` GET·PUT 2 endpoint (Swagger 4단) |
| 2 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SystemConfigDto.kt` | `KillSwitchStateResponse` + `KillSwitchToggleRequest` DTO |
| 3 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/SystemConfigService.kt` | KillSwitch toggle 흐름 + KillSwitchTogglePort 호출 + Kafka publish |
| 4 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/MaskedKisCredentialDto.kt` | `MaskedKisCredentialResponseDto` + `MaskedKisCredentialItem` DTO + `KisCredentialUpdateRequest` DTO |
| 5 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/adapter/kis/KisCredentialMasker.kt` | appKey·secretKey·accountNumber 마스킹 헬퍼 (앞 4자 + `***` + 뒤 4자 / 전부 `*` / DB GENERATED column 직접 사용) |
| 6 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/adapter/redis/KisCacheInvalidator.kt` | Redis `access_token:*` + `kis:approval:*` + `kis:key-changed-lock:*` 헬퍼 (DEL·SET) |
| 7 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/messaging/KisKeyChangedProducer.kt` | Kafka producer — `system.kis-key-changed.v1` publish |

### 2-3. 갱신 파일 매트릭스

| # | 경로 | 변경 내용 |
|---|---|---|
| 1 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/OnboardingController.kt` | `PUT /api/onboarding/kis/{accountType}` + `GET /api/onboarding/kis/credentials` + `DELETE /api/onboarding/kis/{accountType}` 3 메서드 추가 (Swagger 4단) |
| 2 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/OnboardingDto.kt` | `KisCredentialUpdateRequest`·`KisCredentialMaskedResponse`·`KisCredentialDeleteResponse` DTO 추가 |
| 3 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/OnboardingService.kt` | `updateKisCredential()` + `getKisCredentialsMasked()` + `deleteKisCredentialMock()` 메서드 추가 — KisAuthValidator 호출 + UPDATE + KisCacheInvalidator + KisKeyChangedProducer |
| 4 | `api/src/main/kotlin/kr/spdaewoon/magicjar/api/config/OnboardingGuardFilter.kt` | `/api/system-config/kill-switch` GET·PUT 패스 허용 추가 |
| 5 | `api/src/main/resources/application.yml` | Kafka topic config — `system.kis-key-changed.v1` (retention 7d) |

### 2-4. 검증 의무 (PR 본문 evidence 인용)

#### 2-4-1. 단위 테스트 매트릭스

| 테스트 | 검증 항목 | 라운드 4 일괄 (개발 룰 4) |
|---|---|---|
| `OnboardingServiceUpdateKisTest` | PUT 흐름 — KisAuthValidator 통과 시 UPDATE 성공 + Redis DEL + Kafka publish | R3 일괄 |
| `OnboardingServiceUpdateKisTest::validateFailRollback` | KisAuthValidator 실패 시 row 보존 (atomicity) | R3 일괄 |
| `OnboardingServiceMaskedTest` | GET 마스킹 응답 — appKey 앞 4자+`***`+뒤 4자 / secretKey 전부 `*` / accountNumber DB GENERATED | R3 일괄 |
| `OnboardingServiceDeleteMockTest::realRejected` | DELETE accountType=REAL 시 400 reject (자기 자신 user_id 보호) | R3 일괄 |
| `SystemConfigServiceKillSwitchTest::toggleOnOff` | toggle ON·OFF 둘 다 UPDATE + Kafka publish | R3 일괄 |
| `KisCredentialMaskerTest` | 마스킹 형식 단위 검증 | R3 일괄 |
| `KisCacheInvalidatorTest::redisLockExpiry` | `kis:key-changed-lock:*` TTL 5초 검증 (TestContainers Redis) | R3 일괄 |

**개발 룰 4 정합** (R1·R2 PR은 테스트 0건 OK, R3 일괄) — 본 Phase 1 backend-core 1 PR로 R1+R2+R3 통합 시 테스트 일괄 의무.

#### 2-4-2. 통합 verify evidence 의무

PR 본문 evidence 인용 의무 (메모리 `feedback_verification_strict.md` 정합):

1. `./gradlew :api:check` GREEN 로그 인용
2. `./gradlew :api:bootJar` GREEN 로그 인용
3. KIS tokenP 통과 mock + UPDATE evidence (단위 테스트 로그 인용)
4. Redis DEL evidence (TestContainers 시나리오)
5. Kafka publish evidence (`@SpringBootTest` + `EmbeddedKafka` 또는 TestContainers)

### 2-5. Swagger 4단 LOCK (메모리 `feedback_swagger_pattern.md` 정합)

5 endpoint 모두 4단 의무:
- `@Tag` 컨트롤러 — "온보딩" / "시스템 설정"
- `@Operation(summary, description)` 메서드 — 한국어 + 한 줄 요약 + 본문 description
- `@Parameter` 파라미터 — accountType path variable / RequestBody description
- `@Schema` DTO — example 값 포함 (예: `"PS01abcdwxyz1234567890"` for appKey)
- `@ApiResponses` — 200/400/401/404/409/503 매트릭스

### 2-6. KIS-first raw fetch 의무 (헌법 §3 정합)

본 Phase 1 backend-core 작업 영역:
- KIS OAuth tokenP 호출은 **이미 KisAuthValidator §72에 구현 완료** → KIS-first raw 신규 fetch 의무 0건
- KIS WS API spec 신규 의제 0건 (consumer Phase 3에서 graceful reconnect는 기존 KisWebSocketBridge baseline reuse)

→ Phase 1 backend-core는 KIS-first raw fetch 의무 면제 (baseline reuse).

### 2-7. PR 본문 templates

```markdown
## 1. Summary

ADR 0073 §2 KIS 키 변경 풀세트 + §3 KillSwitch 수동 토글 backend 실구현 (Phase 1).

- REST endpoint 신규 5건 — PUT/GET/DELETE `/api/onboarding/kis/*` + GET/PUT `/api/system-config/kill-switch`
- Kafka producer 신규 1 — `system.kis-key-changed.v1` (WS 재연결 신호)
- Redis 헬퍼 신규 — access_token + kis:approval DEL + kis:key-changed-lock SET 5s
- KisAuthValidator + KillSwitchToggleAdapter baseline reuse
- Flyway 0건 LOCK (ADR 0073 §5)

## 2. Verification evidence

- `./gradlew :api:check` GREEN ...
- ...
```

---

## 3. Phase 3 — consumer (Kafka @KafkaListener + WS graceful reconnect)

### 3-1. 디스패치 요약

**sub-agent**: backend-core (consumer 영역 산출 가능 — sub-agent 등록 패턴 정합 ADR 0070 정합)
**영역**: `consumer` 모듈
**branch**: `agent/backend-core/pr-413-settings-kis-change-consumer`
**author**: `bot+backend-core@magicjar.local`
**PR title**: `feat(consumer): system.kis-key-changed.v1 listener + WS graceful reconnect (ADR 0073 Phase 3)`

### 3-2. 신규 파일 매트릭스

| # | 경로 | 책임 |
|---|---|---|
| 1 | `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/messaging/KisKeyChangedListener.kt` | `@KafkaListener("system.kis-key-changed.v1")` — KisWebSocketBridge.reconnectGracefully() 호출 |

### 3-3. 갱신 파일 매트릭스

| # | 경로 | 변경 내용 |
|---|---|---|
| 1 | `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/ws/KisWebSocketBridge.kt` | `reconnectGracefully()` public 메서드 추가 — 기존 connection close + 0.5s 대기 + 신규 연결 open + 종목 재구독 (ADR 0048 §3 정합) |
| 2 | `consumer/src/main/resources/application.yml` | Kafka consumer config — `system.kis-key-changed.v1` group-id 추가 |

### 3-4. 검증 의무

#### 3-4-1. 단위 테스트

| 테스트 | 검증 항목 |
|---|---|
| `KisKeyChangedListenerTest` | Kafka 이벤트 수신 → KisWebSocketBridge.reconnectGracefully() 호출 검증 (mock) |
| `KisWebSocketBridgeReconnectTest` | reconnectGracefully — 기존 session close + delay + 신규 connection open + 활성 구독 재구독 (sessionRegistry 기반) |

#### 3-4-2. evidence 의무

- `./gradlew :consumer:check` GREEN
- `./gradlew :consumer:bootJar` GREEN
- 통합 시나리오 (orchestrator 통합 verify 사이클에서) — KIS 키 변경 PUT → Kafka publish → consumer listen → WS reconnect → 활성 구독 재구독 로그 인용

### 3-5. 충돌 행렬 (Phase 1과 add-only)

| 파일 | Phase 1 변경? | Phase 3 변경? | 충돌? |
|---|---|---|---|
| `consumer/src/main/kotlin/.../KisWebSocketBridge.kt` | ❌ | ✅ (메서드 추가) | 0 |
| `consumer/src/main/resources/application.yml` | ❌ | ✅ (Kafka consumer config 추가) | 0 |
| `api/src/main/resources/application.yml` | ✅ (Kafka producer config) | ❌ | 0 |
| `_workspace/CHANGELOG.md` | ✅ (slot anchor `<!-- pr-413-slot-backend-core -->`) | ✅ (slot anchor `<!-- pr-413-slot-consumer -->`) | **HTML anchor 분리 LOCK** (ADR 0050 정합) |

→ Phase 1·Phase 3 병렬 디스패치 가능 (`feedback_orchestrator_active_parallel.md` 정합).

---

## 4. Phase 2 — frontend (Section 2 submit 활성화 + Section 4 신설 + ConfirmDangerToggle 실구현)

### 4-1. 디스패치 요약

**sub-agent**: frontend
**영역**: `frontend` SPA
**branch**: `agent/frontend/pr-413-settings-kis-change-ui`
**author**: `bot+frontend@magicjar.local`
**PR title**: `feat(frontend): SettingsModal KIS 키 변경 활성화 + KillSwitch 토글 section + ConfirmDangerToggle 실구현 (ADR 0073 Phase 2)`

**시작 시점**: Phase 1 (backend-core) PR 머지 완료 후

### 4-2. 신규 파일 매트릭스

| # | 경로 | 책임 |
|---|---|---|
| 1 | `frontend/src/components/settings/sections/KillSwitchSection.tsx` | Section 4 — 현재 상태 표시 + 토글 버튼 (ConfirmDangerToggle wrap) |
| 2 | `frontend/src/api/queries/useKillSwitchState.ts` | `GET /api/system-config/kill-switch` query hook |
| 3 | `frontend/src/api/queries/useToggleKillSwitch.ts` | `PUT /api/system-config/kill-switch` mutation hook |
| 4 | `frontend/src/api/queries/useKisCredentials.ts` | `GET /api/onboarding/kis/credentials` query hook |
| 5 | `frontend/src/api/queries/useUpdateKisCredential.ts` | `PUT /api/onboarding/kis/{accountType}` mutation hook |
| 6 | `frontend/src/api/queries/useDeleteKisCredential.ts` | `DELETE /api/onboarding/kis/{accountType}` mutation hook |
| 7 | `frontend/src/api/types/settings.ts` | response/request TypeScript 타입 정의 (`MaskedKisCredentialResponse`·`KillSwitchStateResponse`·`KisCredentialUpdateRequest` 등) |

### 4-3. 갱신 파일 매트릭스

| # | 경로 | 변경 내용 |
|---|---|---|
| 1 | `frontend/src/components/settings/SettingsModal.tsx` | Section 4 mount (`<KillSwitchSection />`) — Section 3·Section 5 슬롯 사이 |
| 2 | `frontend/src/components/settings/sections/KisCredentialSection.tsx` | submit 활성화 + useKisCredentials hook 호출 + useUpdateKisCredential mutation + useDeleteKisCredential MOCK 삭제 버튼 (ConfirmDangerToggle wrap) |
| 3 | `frontend/src/components/settings/forms/ConfirmDangerToggle.tsx` | 1차 stub 있을 시 실구현 — 이중 confirm + 사유 입력 (textarea minLength=5 maxLength=500) + 60초 cooldown 안내 |
| 4 | `frontend/src/components/settings/forms/SecretKeyInput.tsx` | (선택) autocomplete=off + unmount 시 zero-fill 재검증 (ADR 0046 §2-5 정합) |

### 4-4. UX 검증 의무 (사용자 dogfooding 직접 의제)

#### 4-4-1. 시나리오 검증 (ADR 0073 §10 정합)

| 시나리오 | 검증 항목 |
|---|---|
| KIS REAL 키 변경 | mask 표시 → 변경 클릭 → input 빔 → 새 키 submit → toast → mask 갱신 |
| KIS MOCK 키 추가 | hasKey=false 상태 → 신규 입력 → submit → toast → row 등장 |
| KIS MOCK 키 DELETE | DELETE 버튼 → ConfirmDangerToggle → 사유 입력 → DELETE → row 사라짐 |
| KillSwitch ON (수동) | OFF 상태 → ON 버튼 → ConfirmDangerToggle → 사유 입력 → 60초 cooldown → ON 확정 → 빨간 점 |
| KillSwitch OFF (drift auto-ON 후) | ON 상태 (사유 표시) → OFF 버튼 → ConfirmDangerToggle → 사유 입력 → 60초 cooldown → OFF 확정 → 회색 점 |
| Section 5 자리 미노출 | `AiCredentialSection` 미존재 — Section 5 위치 비어 있음 OK (1차 scope 외) |

#### 4-4-2. evidence 의무

- `pnpm typecheck` GREEN
- `pnpm build` GREEN
- 통합 시나리오 — backend ↔ frontend e2e (orchestrator 통합 verify 사이클에서)
- 사용자 dogfooding 후 회신 — 이건 사용자 자율 의제 (orchestrator 후속 사이클)

### 4-5. 안전 룰 (메모리 `feedback_secret_handling.md` 정합)

- 응답 mask만 hold — 평문 키 frontend state·console·localStorage 절대 X
- submit input mount 시 빈 input + 평문 hold는 SecretKeyInput unmount 시 zero-fill
- HTTP 호출 시 axios interceptor에 `Authorization` 외 추가 헤더 X (KIS 키는 backend가 DB AES에서 복호화하여 사용 — frontend → backend payload는 평문 1회만 흘러감 + Phase 1 backend가 즉시 AES 암호화 + 메모리 zero-fill, ADR 0046 §2-5)

---

## 5. CHANGELOG 슬롯 prepend 매트릭스 (헌법 §4 architect 영역)

ADR 0050 정합 — slot row + HTML anchor 사전 prepend 의무:

```markdown
<!-- pr-413-slot-architect -->
| 2026-05-14 | architect | docs/adr | ADR 0073 신규 발행 (settings KIS 키 변경 + KillSwitch + AI 키 사전 정합) + spec markdown + backlog §44·§45 흡수 마킹 + README 인덱스 갱신 |
<!-- pr-413-slot-backend-core -->
| TBD | backend-core | api | Phase 1 — REST 5 endpoint + Kafka producer + Redis invalidate + Swagger 4단 (ADR 0073 §6) |
<!-- pr-413-slot-consumer -->
| TBD | backend-core | consumer | Phase 3 — system.kis-key-changed.v1 @KafkaListener + WS graceful reconnect (ADR 0073 §2-2-3) |
<!-- pr-413-slot-frontend -->
| TBD | frontend | frontend | Phase 2 — KisCredentialSection submit 활성화 + KillSwitchSection 신설 + ConfirmDangerToggle 실구현 + 5 hook (ADR 0073 §8) |
```

→ 4 slot anchor 사전 prepend (architect PR에서 발행) + 각 sub-agent PR이 in-place edit (TBD → 실제 PR 번호·날짜).

---

## 6. 정합 갱신 매트릭스 (architect 영역)

본 spec/plan PR(architect 단독)에서 동시 갱신 의무:

| # | 파일 | 변경 |
|---|---|---|
| 1 | `_workspace/arch/adr/0073-settings-kis-change-killswitch-ai-credentials.md` | 신규 발행 (12 §) |
| 2 | `_workspace/specs/pr-413-settings-kis-change-killswitch-ai-spec.md` | 본 문서 신규 |
| 3 | `_workspace/arch/adr/README.md` | ADR 0073 row 추가 + count 갱신 (68→69) + intro paragraph 갱신 |
| 4 | `_workspace/backlog.md` | §44 본문에 "본 ADR 0073 1차 흡수 완료 — 풀세트 3건" 마킹 + §45 본문에 "본 ADR 0073 §G·§A·§D 흡수 — §E 사전 정합 + §F·§H·§I·§J 잔류" 마킹 |
| 5 | `docs/architecture/master-architecture.md` | §10 ADR 인덱스 갱신 (ADR 0073 추가) + T-ONBOARD section SettingsModal 풀세트 outline 갱신 |
| 6 | `docs/architecture/tracks-roadmap.md` | T-ONBOARD section outline 갱신 (KIS 키 변경 + KillSwitch + AI 키 사전 정합 명세) |
| 7 | `CHANGELOG.md` | 4 slot anchor + architect slot row (§5 정합) |

---

## 7. 작업 순서 (sub-agent 디스패치 흐름)

```
[T0] architect (본 PR)
   ↓ ADR 0073 + spec + 정합 갱신 + slot anchor prepend
   ↓ → main 머지 (--rebase)
   ↓
[T1] orchestrator
   ↓ Phase 1 + Phase 3 병렬 디스패치 (add-only 충돌 0)
   ↓
[T2a] backend-core (Phase 1) ──┐
   ↓ → main 머지              │
                               ├──→ [T3] orchestrator: Phase 2 디스패치
[T2b] backend-core (Phase 3) ──┘ → main 머지
   ↓
[T4] frontend (Phase 2) → main 머지
   ↓
[T5] 통합 verify (orchestrator + 사용자 dogfooding) → v3.6.5 patch cut
```

---

## 8. AI 호출 0건 grep 검증 의무 (ADR 0070·0071·0072·0073 정합)

각 sub-agent PR 머지 직전 grep evidence 인용 의무 (메모리 `feedback_no_mock_data.md`·헌법 §3 정합):

```bash
# Phase 1 backend-core
grep -rn "anthropic\|openai\|claude" \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/OnboardingController.kt \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/SystemConfigController.kt \
  api/src/main/kotlin/kr/spdaewoon/magicjar/api/service/SystemConfigService.kt
# 기대: 0건

# Phase 3 consumer
grep -rn "anthropic\|openai\|claude" \
  consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/messaging/KisKeyChangedListener.kt
# 기대: 0건

# Phase 2 frontend
grep -rn "anthropic\|openai\|claude\|gpt" \
  frontend/src/components/settings/ \
  frontend/src/api/queries/useKillSwitchState.ts \
  frontend/src/api/queries/useToggleKillSwitch.ts
# 기대: 0건 (Section 5 AiCredentialSection 미존재이므로 자연 0)
```

---

## 9. mock 합성 0건 검증 (메모리 `feedback_no_mock_data.md` 정합)

| 영역 | 검증 |
|---|---|
| backend KillSwitch 초기 row 없을 때 | `GET /api/system-config/kill-switch` → `enabled=false, reason=null, updatedAt=null, updatedBy="(없음)"` — 합성 데이터 X |
| backend KIS 키 등록 0건일 때 | `GET /api/onboarding/kis/credentials` → `credentials=[]` 빈 배열 — 합성 placeholder mask X |
| frontend KisCredentialSection 0 row | "등록된 KIS 키 없음" 표시 — placeholder `PS01***xxxx` mask 금지 |
| frontend KillSwitchSection 초기 | "최초 토글 없음" 표시 + ON 버튼만 노출 — 가상 상태 X |

---

## 10. Rollback 검증 (ADR 0073 §12 정합)

각 Phase PR revert 가능 매트릭스 ADR 0073 §12 정합 — 본 spec에서는 revert 시나리오 명시:

- Phase 1 revert → backend endpoint 사라짐 → frontend Phase 2가 머지 X 상태일 때만 안전
- Phase 3 revert → WS 재연결 path 0 → 사용자가 backend 직접 재시작으로 우회 가능 (1인 baseline)
- Phase 2 revert → frontend mask 표시 X + KillSwitch UI X → backend는 정상 (curl 우회 가능)

---

## 11. 종합 정합 매트릭스

| 의제 | 본 spec 정합 |
|---|---|
| 헌법 §3 KIS-first raw fetch | Phase 1 backend-core baseline reuse (KisAuthValidator §72) — 신규 fetch 0건 |
| 헌법 §4 CHANGELOG slot anchor | 4 slot anchor prepend (architect §5) |
| ADR 0030 SoT 정합 | master §10 ADR 인덱스 갱신 (§6 #5) |
| ADR 0050 hunk 분리 | 4 anchor HTML 주석 분리 |
| ADR 0046 §2-5 평문 보호 | mask 응답 + log MaskingPatternLayout + unmount zero-fill |
| ADR 0043 §2-3 자동 OFF 0 | KillSwitch OFF = 사용자 수동만 (UI ConfirmDangerToggle + 사유 입력) |
| ADR 0069 §4 5 step 표준 | AI 키 카테고리 사전 정합 (§4 §2-4-2) |
| 메모리 `feedback_3tier_hierarchy.md` | architect spec → orchestrator 디스패치 → sub-agent 실행 |
| 메모리 `feedback_orchestrator_active_parallel.md` | Phase 1·3 병렬 가능 명시 (§2-9·§3-5) |
| 메모리 `feedback_spec_plan_scope.md` | 본 spec/plan 발행 정합 — 신규 ADR + 다중 sub-agent |
| 메모리 `feedback_no_mock_data.md` | §9 mock 합성 0건 검증 |
| 메모리 `feedback_verification_strict.md` | §2-4-2·§3-4-2·§4-4-2 evidence 인용 의무 |

---

본 spec/plan은 ADR 0073 본문 권위 LOCK 하에 sub-agent 디스패치 매트릭스 + 검증 의무 + 정합 갱신만 다룬다. 결정 변동 시 ADR 0073을 갱신하고 본 spec은 sub-agent 매트릭스만 정합 유지.
