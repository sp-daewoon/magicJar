# orderTrigger 키 사슬 추적 화면 구성 설명 (ADR 0115 §2-B 불변식 #4)

작성일: 2026-06-10
담당: frontend agent
브랜치: agent/frontend/order-trigger-trace-view
근거: spec `2026-06-10-execution-ledger-integrity-architecture.md` §2-B-2 불변식 4 (화면 추적성)
최고 관리자 직접 요구: "전략이 사인 내면 몇 주 주문 내고, 몇 주가 그 전략으로 사지고 팔아졌는지 키로 추적"

> qa Visual 회귀 캡처 가이드 — 실제 005930 DB(주문 185건·체결·trigger 분류 완료)가 살아있는 환경에서
> 아래 URL 진입 시 데이터가 보입니다. mock 합성 0건 — 데이터 없으면 "없음" 명시 경로를 캡처하세요.

---

## 화면 1 — 주문 내역 출처 badge (/orders 목록)

**URL**: `/orders`

**화면 구성**:
- 기존 OrderCard 레이아웃 유지 + 헤더 행에 **발사 출처 badge** 추가 (기존 자동/수동 badge 옆).
- badge 색 매트릭스 (Tailwind 팔레트):
  - 전략 사인 → 파랑 (`bg-blue-100 text-blue-800`) + `#<사인id>` 작은 mono 텍스트
  - 자동 익절 → 노랑 (`bg-yellow-100`)
  - 손절 → 빨강 (`bg-red-100`)
  - 분할 익절 → 주황 (`bg-orange-100`)
  - 수동 → 회색 (`bg-gray-100`)
  - 외부 → 보라 (`bg-purple-100`)
  - 미분류(legacy/V80 이전) → 점선 회색 (`border-dashed`)
- badge hover(title): "사인 #709" / "전략 #8" 등 유발 근거 키 노출.

**검증 포인트**:
- 005930 주문 185건 각각 출처 badge 가 분류대로 색 표시 (불명 0 — backend backfill 완료).
- legacy 미분류 row 가 있으면 점선 회색 "미분류".

---

## 화면 2 — 주문 상세 발사 출처 (/orders/:id)

**URL**: `/orders/{id}`

**화면 구성**:
- 상세 정보 그리드에 **"발사 출처"** 필드 추가 (KIS 주문번호 옆) → OrderTriggerBadge 렌더.
- 기존 체결 내역 테이블·감사 타임라인 그대로.

---

## 화면 3 — 사인 드릴다운 키 사슬 추적 (★ 핵심, /strategy/list)

**URL**: `/strategy/list` (하단 "시그널 내역" 섹션 → SignalCard 각 행)

**인터랙션**: 각 시그널 카드 하단 **"▸ 키 사슬 추적"** 버튼 클릭 → 행 확장 (lazy fetch `GET /api/signals/{id}/trace`).

**확장 패널(SignalTracePanel) 구성**:
1. **요약 행** (한 줄 흐름):
   `주문 15주 → 체결 11주 → 전략 보유 11주 vs KIS 권위 16주`
   + 우측 **정합 badge**:
   - 전략 보유 == KIS 권위 → 초록 "정합" (`bg-emerald-100`)
   - 다르면 → 주황 "보정 대기" (`bg-amber-100`, title="미귀속/다전략 혼재 보정 대기 ADR 0115 §C2")
   + 전략 보유>0이면 평단·회차·실현손익 메타 한 줄.
2. **연결 주문 목록** ("이 사인이 발사한 주문 (N건)"):
   - 각 주문: `#주문id · 매수/매도 · N주 · 상태 badge · 출처 badge · 체결 X/N주`.
   - 주문 #id 클릭 → `/orders/:id` 이동.
   - 거절 사유 있으면 빨간 텍스트.
   - **체결 타임라인** (부분체결 N건, 세로 ol):
     `1. 3주 @ 73,000원 05:29:10` / `2. 5주 @ 73,100원 05:29:12` / `3. 7주 @ 73,200원 05:29:14`.
   - 체결 0건이면 "체결 내역 없음 (미체결 또는 거절)".
3. **주문 0건**: "이 사인으로 발사된 주문이 없습니다 (HOLD 사인이거나, 리스크 게이트 차단, 또는 발사 전)".

**검증 포인트 (불변식 #4)**:
- 005930 전략8 사인(예: signal 709) 확장 시 사인→주문→체결→보유가 키 join으로 끊김 없이 추적.
- G4 부분체결 미귀속 케이스: 체결 11주 vs KIS 권위 16주 → "보정 대기" 주황 badge (실격 후보 즉시 식별).
- 정합 케이스: 전량 체결·귀속 일치 → "정합" 초록.
- userId 미등록 시: "키 사슬 추적은 KIS 계좌 등록 후 사용" 안내.

---

## ui-map 업데이트 항목

| URL | 컴포넌트 | 필요 API |
|-----|----------|----------|
| `/orders` | OrdersPage → OrderCard → OrderTriggerBadge | GET /api/orders (orderTrigger·triggerRefId 포함) |
| `/orders/:id` | OrderDetailPage → OrderTriggerBadge | GET /api/orders/{id} |
| `/strategy/list` | SignalListPanel → SignalCard → SignalTracePanel | GET /api/signals + **GET /api/signals/{id}/trace** (신설 read-only) |
