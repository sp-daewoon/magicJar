/**
 * AuthSection — 인증 탭 컴포넌트
 * 인증 상태 카드 + 모드 전환 라디오 + 로그인 폼
 */

import { useEffect, useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useAuthStatus, useLoginMutation, useChangeMode, DEFAULT_USER_ID } from '@/hooks/useAuth'
import type { KisMode } from '@/api/auth'

/** 계좌번호 마스킹: 8자리 이상 입력 시 XXXX-XX-XX 형식 */
function maskAccountNo(raw: string): string {
  if (raw.length < 8) return raw
  return `${raw.slice(0, 4)}-XX-XX`
}

/** 모드 라벨 */
function modeLabel(mode: KisMode | null): string {
  if (!mode) return '미설정'
  return mode === 'vps' ? '모의투자 (VPS)' : '실전투자 (PROD)'
}

/**
 * KIS 인증 입력값 localStorage 캐시 (개발 편의 — `project_kis_key_management.md` 정책).
 * 모드(vps/prod)별로 분리 저장. 본격 운영 진입 시 secret manager로 이전.
 */
const STORAGE_KEY_PREFIX = 'magicjar.kis.creds'

interface StoredCreds {
  appKey: string
  appSecret: string
  accountNo: string
}

function loadCreds(mode: KisMode): StoredCreds {
  try {
    const raw = localStorage.getItem(`${STORAGE_KEY_PREFIX}.${mode}`)
    if (!raw) return { appKey: '', appSecret: '', accountNo: '' }
    const parsed = JSON.parse(raw)
    return {
      appKey: typeof parsed.appKey === 'string' ? parsed.appKey : '',
      appSecret: typeof parsed.appSecret === 'string' ? parsed.appSecret : '',
      accountNo: typeof parsed.accountNo === 'string' ? parsed.accountNo : '',
    }
  } catch {
    return { appKey: '', appSecret: '', accountNo: '' }
  }
}

function saveCreds(mode: KisMode, creds: StoredCreds): void {
  try {
    localStorage.setItem(`${STORAGE_KEY_PREFIX}.${mode}`, JSON.stringify(creds))
  } catch {
    // localStorage 비활성·할당 초과 시 무시
  }
}

/** 마지막 선택 모드도 별도 저장 (다음 모달 열 때 default 채움) */
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

/** 마지막 로그인 시각 포맷 */
function formatLastLogin(iso: string | null): string {
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

export function AuthSection() {
  const { data: status, isError, isLoading } = useAuthStatus(DEFAULT_USER_ID)
  const loginMutation = useLoginMutation(DEFAULT_USER_ID)
  const changeMutation = useChangeMode(DEFAULT_USER_ID)

  const [selectedMode, setSelectedMode] = useState<KisMode>(() => loadLastMode())
  const initial = loadCreds(selectedMode)
  const [appKey, setAppKey] = useState(initial.appKey)
  const [appSecret, setAppSecret] = useState(initial.appSecret)
  const [accountNoRaw, setAccountNoRaw] = useState(initial.accountNo)

  const [loginMsg, setLoginMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [modeMsg, setModeMsg] = useState<{ ok: boolean; text: string } | null>(null)

  // 모드 전환 시 해당 모드의 마지막 입력값 자동 채움
  useEffect(() => {
    const cached = loadCreds(selectedMode)
    setAppKey(cached.appKey)
    setAppSecret(cached.appSecret)
    setAccountNoRaw(cached.accountNo)
    saveLastMode(selectedMode)
  }, [selectedMode])

  async function handleLogin() {
    setLoginMsg(null)
    try {
      const res = await loginMutation.mutateAsync({
        userId: DEFAULT_USER_ID,
        mode: selectedMode,
        appKey,
        appSecret,
        accountNo: accountNoRaw,
      })
      setLoginMsg({ ok: true, text: res.message || '로그인 성공' })
      // 마지막 값 기억 (모드별, 개발 편의 — project_kis_key_management.md)
      saveCreds(selectedMode, { appKey, appSecret, accountNo: accountNoRaw })
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e)
      setLoginMsg({ ok: false, text: `로그인 실패: ${msg}` })
    }
  }

  async function handleChangeMode(mode: KisMode) {
    setModeMsg(null)
    setSelectedMode(mode)
    try {
      const res = await changeMutation.mutateAsync(mode)
      setModeMsg({ ok: true, text: res.message || '모드 전환 완료' })
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e)
      setModeMsg({ ok: false, text: `모드 전환 실패: ${msg}` })
    }
  }

  return (
    <div className="space-y-6">
      {/* 인증 상태 카드 */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-base">인증 상태</CardTitle>
        </CardHeader>
        <CardContent>
          {isLoading && (
            <p className="text-sm text-muted-foreground animate-pulse">상태 조회 중...</p>
          )}
          {isError && (
            <p className="text-sm text-destructive">
              백엔드 연결 안 됨 — T18a 서버 미구동 상태입니다.
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
                <span className="ml-2 text-muted-foreground">{modeLabel(status.mode)}</span>
              </span>

              <span className="text-muted-foreground">계좌번호</span>
              <span className="font-mono">{status.accountNo ?? '-'}</span>

              <span className="text-muted-foreground">App Key</span>
              <span>{status.hasAppKey ? '등록됨' : '미등록'}</span>

              <span className="text-muted-foreground">App Secret</span>
              <span>{status.hasAppSecret ? '등록됨' : '미등록'}</span>

              <span className="text-muted-foreground">마지막 로그인</span>
              <span>{formatLastLogin(status.lastLoginAt)}</span>
            </div>
          )}
        </CardContent>
      </Card>

      {/* 모드 전환 */}
      <div className="space-y-3">
        <h3 className="text-sm font-semibold">모드 전환</h3>
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
        {modeMsg && (
          <p className={`text-xs ${modeMsg.ok ? 'text-green-600' : 'text-destructive'}`}>
            {modeMsg.text}
          </p>
        )}
      </div>

      {/* 로그인 폼 */}
      <div className="space-y-4">
        <h3 className="text-sm font-semibold">KIS 인증 정보 입력</h3>

        <div className="space-y-2">
          <Label htmlFor="app-key" className="text-sm">
            App Key
          </Label>
          <Input
            id="app-key"
            type="text"
            placeholder="App Key를 입력하세요"
            value={appKey}
            onChange={(e) => setAppKey(e.target.value)}
            autoComplete="off"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="app-secret" className="text-sm">
            App Secret
          </Label>
          <Input
            id="app-secret"
            type="password"
            placeholder="App Secret을 입력하세요"
            value={appSecret}
            onChange={(e) => setAppSecret(e.target.value)}
            autoComplete="new-password"
          />
        </div>

        <div className="space-y-2">
          <Label htmlFor="account-no" className="text-sm">
            계좌번호
          </Label>
          <Input
            id="account-no"
            type="text"
            placeholder="계좌번호 (예: 12345678)"
            value={accountNoRaw}
            onChange={(e) => setAccountNoRaw(e.target.value)}
            autoComplete="off"
          />
          {accountNoRaw.length >= 8 && (
            <p className="text-xs text-muted-foreground">
              표시 형식: {maskAccountNo(accountNoRaw)}
            </p>
          )}
        </div>

        {/* 보안 경고 */}
        <div className="rounded-md border border-yellow-300 bg-yellow-50 px-3 py-2 text-xs text-yellow-800 dark:bg-yellow-950 dark:border-yellow-700 dark:text-yellow-300">
          주의: App Secret은 평문 전송됩니다. HTTPS 운영 환경에서만 사용하세요.
        </div>

        <Button
          onClick={() => { void handleLogin() }}
          disabled={loginMutation.isPending || !appKey || !appSecret || !accountNoRaw}
          className="w-full"
        >
          {loginMutation.isPending ? '로그인 중...' : '로그인'}
        </Button>

        {loginMsg && (
          <p className={`text-xs ${loginMsg.ok ? 'text-green-600' : 'text-destructive'}`}>
            {loginMsg.text}
          </p>
        )}
      </div>
    </div>
  )
}
