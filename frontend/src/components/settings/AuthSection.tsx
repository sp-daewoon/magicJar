/**
 * AuthSection — 인증 탭 컴포넌트 (3섹션 분리 버전)
 *
 * 카드 A: KIS 모의투자 (appKey·appSecret·accountNo·accountProductCode·htsId)
 * 카드 B: KIS 실전투자 (appKey·appSecret — 계좌는 카드 A 공유)
 * 카드 C: DART 재무 데이터 (dart_api_key)
 * 모드 토글: 매매 시 사용 모드 전환 (기존 유지)
 *
 * TODO: backend V21 endpoint 정합 필요.
 *   현재 카드 A/B는 POST /api/auth/login (mode: 'vps'|'prod')로 저장.
 *   카드 C는 TODO — backend가 DART key endpoint 추가 후 연동 예정.
 *   endpoint 가정: POST /api/auth/keys body: {mode:"MOCK"|"PROD"|"DART", appKey, appSecret}
 */

import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Separator } from '@/components/ui/separator'
import { useAuthStatus, useLoginMutation, useChangeMode, DEFAULT_USER_ID } from '@/hooks/useAuth'
import type { KisMode } from '@/api/auth'

// ── localStorage 유틸 ───────────────────────────────────────────────────────

const STORAGE_PREFIX = 'magicjar.kis.creds'

interface MockCreds {
  appKey: string
  appSecret: string
  accountNo: string
  accountProductCode: string
  htsId: string
}

interface ProdCreds {
  appKey: string
  appSecret: string
}

interface DartCreds {
  dartApiKey: string
}

function loadMockCreds(): MockCreds {
  try {
    const raw = localStorage.getItem(`${STORAGE_PREFIX}.vps`)
    if (!raw) return { appKey: '', appSecret: '', accountNo: '', accountProductCode: '', htsId: '' }
    const p = JSON.parse(raw)
    return {
      appKey: p.appKey ?? '',
      appSecret: p.appSecret ?? '',
      accountNo: p.accountNo ?? '',
      accountProductCode: p.accountProductCode ?? '',
      htsId: p.htsId ?? '',
    }
  } catch {
    return { appKey: '', appSecret: '', accountNo: '', accountProductCode: '', htsId: '' }
  }
}

function loadProdCreds(): ProdCreds {
  try {
    const raw = localStorage.getItem(`${STORAGE_PREFIX}.prod`)
    if (!raw) return { appKey: '', appSecret: '' }
    const p = JSON.parse(raw)
    return { appKey: p.appKey ?? '', appSecret: p.appSecret ?? '' }
  } catch {
    return { appKey: '', appSecret: '' }
  }
}

function loadDartCreds(): DartCreds {
  try {
    const raw = localStorage.getItem(`${STORAGE_PREFIX}.dart`)
    if (!raw) return { dartApiKey: '' }
    const p = JSON.parse(raw)
    return { dartApiKey: p.dartApiKey ?? '' }
  } catch {
    return { dartApiKey: '' }
  }
}

function saveCreds(key: string, value: unknown): void {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // ignore
  }
}

const LAST_MODE_KEY = 'magicjar.kis.lastMode'

function loadLastMode(): KisMode {
  const v = localStorage.getItem(LAST_MODE_KEY)
  return v === 'prod' ? 'prod' : 'vps'
}

function saveLastMode(mode: KisMode): void {
  try {
    localStorage.setItem(LAST_MODE_KEY, mode)
  } catch {
    // ignore
  }
}

// ── 등록 상태 배지 ──────────────────────────────────────────────────────────

interface StatusBadgeProps {
  registered: boolean
}

function StatusBadge({ registered }: StatusBadgeProps) {
  return registered ? (
    <Badge className="bg-green-600 text-white hover:bg-green-600 text-xs px-1.5 py-0">등록됨</Badge>
  ) : (
    <Badge variant="outline" className="text-xs px-1.5 py-0 text-muted-foreground">미등록</Badge>
  )
}

// ── 저장 결과 메시지 ────────────────────────────────────────────────────────

interface SaveMsg {
  ok: boolean
  text: string
}

function SaveFeedback({ msg }: { msg: SaveMsg | null }) {
  if (!msg) return null
  return (
    <p className={`text-xs ${msg.ok ? 'text-green-600' : 'text-destructive'}`}>
      {msg.text}
    </p>
  )
}

// ── KIS 모의투자 카드 (카드 A) ───────────────────────────────────────────────

function MockKisCard() {
  const loginMutation = useLoginMutation(DEFAULT_USER_ID)
  const initial = loadMockCreds()
  const [appKey, setAppKey] = useState(initial.appKey)
  const [appSecret, setAppSecret] = useState(initial.appSecret)
  const [accountNo, setAccountNo] = useState(initial.accountNo)
  const [accountProductCode, setAccountProductCode] = useState(initial.accountProductCode)
  const [htsId, setHtsId] = useState(initial.htsId)
  const [msg, setMsg] = useState<SaveMsg | null>(null)

  const isRegistered = !!initial.appKey

  async function handleSave() {
    setMsg(null)
    try {
      // TODO: backend V21 endpoint 정합 — POST /api/auth/keys {mode:"MOCK", ...}
      // 현재는 기존 POST /api/auth/login (mode='vps') 호환
      await loginMutation.mutateAsync({
        userId: DEFAULT_USER_ID,
        mode: 'vps',
        appKey,
        appSecret,
        accountNo,
      })
      saveCreds(`${STORAGE_PREFIX}.vps`, { appKey, appSecret, accountNo, accountProductCode, htsId })
      setMsg({ ok: true, text: '모의투자 키 저장 완료' })
    } catch (e: unknown) {
      const errMsg = e instanceof Error ? e.message : String(e)
      setMsg({ ok: false, text: `저장 실패: ${errMsg}` })
    }
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center justify-between text-sm">
          <span>KIS 모의투자</span>
          <StatusBadge registered={isRegistered} />
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="space-y-1.5">
          <Label htmlFor="mock-app-key" className="text-xs">App Key</Label>
          <Input
            id="mock-app-key"
            type="text"
            placeholder="모의 App Key"
            value={appKey}
            onChange={(e) => setAppKey(e.target.value)}
            autoComplete="off"
            className="h-8 text-sm"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="mock-app-secret" className="text-xs">App Secret</Label>
          <Input
            id="mock-app-secret"
            type="password"
            placeholder="모의 App Secret"
            value={appSecret}
            onChange={(e) => setAppSecret(e.target.value)}
            autoComplete="new-password"
            className="h-8 text-sm"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="mock-account-no" className="text-xs">계좌번호</Label>
          <Input
            id="mock-account-no"
            type="text"
            placeholder="계좌번호 (예: 12345678)"
            value={accountNo}
            onChange={(e) => setAccountNo(e.target.value)}
            autoComplete="off"
            className="h-8 text-sm"
          />
        </div>
        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1.5">
            <Label htmlFor="mock-product-code" className="text-xs">계좌상품코드</Label>
            <Input
              id="mock-product-code"
              type="text"
              placeholder="예: 01"
              value={accountProductCode}
              onChange={(e) => setAccountProductCode(e.target.value)}
              autoComplete="off"
              className="h-8 text-sm"
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="mock-hts-id" className="text-xs">HTS ID</Label>
            <Input
              id="mock-hts-id"
              type="text"
              placeholder="HTS ID"
              value={htsId}
              onChange={(e) => setHtsId(e.target.value)}
              autoComplete="off"
              className="h-8 text-sm"
            />
          </div>
        </div>
        <Button
          size="sm"
          onClick={() => { void handleSave() }}
          disabled={loginMutation.isPending || (!appKey && !appSecret && !accountNo)}
          className="w-full h-8"
        >
          {loginMutation.isPending ? '저장 중...' : '저장'}
        </Button>
        <SaveFeedback msg={msg} />
      </CardContent>
    </Card>
  )
}

// ── KIS 실전투자 카드 (카드 B) ───────────────────────────────────────────────

function ProdKisCard() {
  const loginMutation = useLoginMutation(DEFAULT_USER_ID)
  const { data: status } = useAuthStatus(DEFAULT_USER_ID)
  const initial = loadProdCreds()
  const [appKey, setAppKey] = useState(initial.appKey)
  const [appSecret, setAppSecret] = useState(initial.appSecret)
  const [msg, setMsg] = useState<SaveMsg | null>(null)

  // 실전 카드에서 계좌번호는 모의 카드의 accountNo 공유
  const sharedAccountNo = loadMockCreds().accountNo

  const isRegistered = status?.mode === 'prod' ? status.hasAppKey : !!initial.appKey

  async function handleSave() {
    setMsg(null)
    if (!sharedAccountNo) {
      setMsg({ ok: false, text: '계좌번호는 모의투자 카드에서 입력해주세요' })
      return
    }
    try {
      // TODO: backend V21 endpoint 정합 — POST /api/auth/keys {mode:"PROD", ...}
      await loginMutation.mutateAsync({
        userId: DEFAULT_USER_ID,
        mode: 'prod',
        appKey,
        appSecret,
        accountNo: sharedAccountNo,
      })
      saveCreds(`${STORAGE_PREFIX}.prod`, { appKey, appSecret })
      setMsg({ ok: true, text: '실전투자 키 저장 완료' })
    } catch (e: unknown) {
      const errMsg = e instanceof Error ? e.message : String(e)
      setMsg({ ok: false, text: `저장 실패: ${errMsg}` })
    }
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center justify-between text-sm">
          <span>KIS 실전투자</span>
          <StatusBadge registered={isRegistered} />
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <div className="rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-950 dark:border-amber-700 dark:text-amber-300">
          주의: 실전 키 등록 후 매매 모드를 실전으로 전환하면 실제 주문이 발생합니다.
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="prod-app-key" className="text-xs">App Key</Label>
          <Input
            id="prod-app-key"
            type="text"
            placeholder="실전 App Key"
            value={appKey}
            onChange={(e) => setAppKey(e.target.value)}
            autoComplete="off"
            className="h-8 text-sm"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="prod-app-secret" className="text-xs">App Secret</Label>
          <Input
            id="prod-app-secret"
            type="password"
            placeholder="실전 App Secret"
            value={appSecret}
            onChange={(e) => setAppSecret(e.target.value)}
            autoComplete="new-password"
            className="h-8 text-sm"
          />
        </div>
        {sharedAccountNo ? (
          <p className="text-xs text-muted-foreground">계좌번호: {sharedAccountNo} (모의투자 카드 공유)</p>
        ) : (
          <p className="text-xs text-destructive">계좌번호 미등록 — 모의투자 카드에서 먼저 입력하세요</p>
        )}
        <Button
          size="sm"
          onClick={() => { void handleSave() }}
          disabled={loginMutation.isPending || (!appKey && !appSecret)}
          className="w-full h-8"
        >
          {loginMutation.isPending ? '저장 중...' : '저장'}
        </Button>
        <SaveFeedback msg={msg} />
      </CardContent>
    </Card>
  )
}

// ── DART 재무 데이터 카드 (카드 C) ───────────────────────────────────────────

function DartCard() {
  const initial = loadDartCreds()
  const [dartApiKey, setDartApiKey] = useState(initial.dartApiKey)
  const [msg, setMsg] = useState<SaveMsg | null>(null)
  const [isSaving, setIsSaving] = useState(false)

  const isRegistered = !!initial.dartApiKey

  async function handleSave() {
    setMsg(null)
    setIsSaving(true)
    try {
      // TODO: backend V21 endpoint 정합 — POST /api/auth/keys {mode:"DART", dartApiKey}
      // 현재는 localStorage에만 저장 (backend DART endpoint 미구현)
      saveCreds(`${STORAGE_PREFIX}.dart`, { dartApiKey })
      setMsg({ ok: true, text: 'DART 키 저장 완료 (로컬 저장 — 백엔드 endpoint 미구현)' })
    } catch (e: unknown) {
      const errMsg = e instanceof Error ? e.message : String(e)
      setMsg({ ok: false, text: `저장 실패: ${errMsg}` })
    } finally {
      setIsSaving(false)
    }
  }

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center justify-between text-sm">
          <span>DART 재무 데이터</span>
          <StatusBadge registered={isRegistered} />
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-xs text-muted-foreground">
          금감원 DART API 키 — 종목 재무 데이터(PER·PBR·EPS 등) 수신에 필요합니다.
        </p>
        <div className="space-y-1.5">
          <Label htmlFor="dart-api-key" className="text-xs">DART API Key</Label>
          <Input
            id="dart-api-key"
            type="password"
            placeholder="DART Open API 인증키"
            value={dartApiKey}
            onChange={(e) => setDartApiKey(e.target.value)}
            autoComplete="new-password"
            className="h-8 text-sm"
          />
        </div>
        <Button
          size="sm"
          onClick={() => { void handleSave() }}
          disabled={isSaving || !dartApiKey}
          className="w-full h-8"
        >
          {isSaving ? '저장 중...' : '저장'}
        </Button>
        <SaveFeedback msg={msg} />
      </CardContent>
    </Card>
  )
}

// ── 인증 상태 요약 ───────────────────────────────────────────────────────────

function formatLastLogin(iso: string | null | undefined): string {
  if (!iso) return '-'
  try {
    return new Date(iso).toLocaleString('ko-KR', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return iso
  }
}

function AuthStatusCard() {
  const { data: status, isError, isLoading } = useAuthStatus(DEFAULT_USER_ID)

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="text-sm">인증 상태</CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading && (
          <p className="text-sm text-muted-foreground animate-pulse">상태 조회 중...</p>
        )}
        {isError && (
          <p className="text-sm text-destructive">
            백엔드 연결 안 됨 — 서버 미구동 상태입니다.
          </p>
        )}
        {status && (
          <div className="grid grid-cols-2 gap-2 text-sm">
            <span className="text-muted-foreground">인증 여부</span>
            <span>
              {status.authenticated ? (
                <Badge className="bg-green-600 text-white hover:bg-green-600">인증됨</Badge>
              ) : (
                <Badge variant="destructive">미인증</Badge>
              )}
            </span>
            <span className="text-muted-foreground">모드</span>
            <span>
              {status.mode === 'prod' ? (
                <Badge variant="destructive">실전</Badge>
              ) : status.mode === 'vps' ? (
                <Badge className="bg-yellow-500 text-white hover:bg-yellow-500">모의</Badge>
              ) : (
                <Badge variant="outline">미설정</Badge>
              )}
            </span>
            <span className="text-muted-foreground">마지막 갱신</span>
            <span>{formatLastLogin(status.lastUpdatedAt ?? status.lastLoginAt)}</span>
          </div>
        )}
      </CardContent>
    </Card>
  )
}

// ── 모드 전환 ────────────────────────────────────────────────────────────────

function ModeSwitcher() {
  const changeMutation = useChangeMode(DEFAULT_USER_ID)
  const [selectedMode, setSelectedMode] = useState<KisMode>(() => loadLastMode())
  const [modeMsg, setModeMsg] = useState<SaveMsg | null>(null)

  async function handleChangeMode(mode: KisMode) {
    setModeMsg(null)
    setSelectedMode(mode)
    saveLastMode(mode)
    try {
      const res = await changeMutation.mutateAsync(mode)
      setModeMsg({ ok: true, text: res.message || '모드 전환 완료' })
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e)
      setModeMsg({ ok: false, text: `모드 전환 실패: ${msg}` })
    }
  }

  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold">매매 시 사용 모드</h3>
      <div className="flex gap-4">
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="radio"
            name="kis-mode"
            value="vps"
            checked={selectedMode === 'vps'}
            onChange={() => { void handleChangeMode('vps') }}
            className="accent-yellow-500"
          />
          <span className="text-sm">
            <span className="font-medium">모의투자</span>
            <span className="text-muted-foreground ml-1">(VPS)</span>
          </span>
        </label>
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="radio"
            name="kis-mode"
            value="prod"
            checked={selectedMode === 'prod'}
            onChange={() => { void handleChangeMode('prod') }}
            className="accent-red-500"
          />
          <span className="text-sm">
            <span className="font-medium">실전투자</span>
            <span className="text-muted-foreground ml-1">(PROD)</span>
          </span>
        </label>
      </div>
      <SaveFeedback msg={modeMsg} />
    </div>
  )
}

// ── 메인 컴포넌트 ────────────────────────────────────────────────────────────

export function AuthSection() {
  return (
    <div className="space-y-4">
      {/* 인증 상태 요약 */}
      <AuthStatusCard />

      {/* 모드 전환 */}
      <ModeSwitcher />

      <Separator />

      {/* KIS 모의투자 카드 */}
      <MockKisCard />

      {/* KIS 실전투자 카드 */}
      <ProdKisCard />

      {/* DART 재무 데이터 카드 */}
      <DartCard />
    </div>
  )
}
