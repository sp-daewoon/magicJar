/**
 * SettingsModal — KIS 설정 모달
 * shadcn/ui 스타일 Dialog (자체 구현) + Tabs
 * ESC 닫기 + 배경 클릭 닫기 지원
 */

import { useEffect, useRef } from 'react'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { Button } from '@/components/ui/button'
import { AuthSection } from './AuthSection'
import { MasterFileSection } from './MasterFileSection'

export interface SettingsModalProps {
  open: boolean
  onClose: () => void
}

export function SettingsModal({ open, onClose }: SettingsModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)

  // ESC 닫기
  useEffect(() => {
    if (!open) return
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    return () => document.removeEventListener('keydown', handleKeyDown)
  }, [open, onClose])

  // 바깥 클릭 닫기
  function handleBackdropClick(e: React.MouseEvent<HTMLDivElement>) {
    if (dialogRef.current && !dialogRef.current.contains(e.target as Node)) {
      onClose()
    }
  }

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50"
      onClick={handleBackdropClick}
      role="dialog"
      aria-modal="true"
      aria-label="설정"
    >
      <div
        ref={dialogRef}
        className="relative bg-background rounded-lg shadow-xl border w-full max-w-lg mx-4 max-h-[90vh] flex flex-col"
        onClick={(e) => e.stopPropagation()}
      >
        {/* 헤더 */}
        <div className="flex items-center justify-between px-6 py-4 border-b flex-shrink-0">
          <h2 className="text-lg font-semibold">설정</h2>
          <Button
            variant="ghost"
            size="icon"
            onClick={onClose}
            aria-label="닫기"
            className="h-8 w-8"
          >
            <svg
              xmlns="http://www.w3.org/2000/svg"
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M18 6 6 18" />
              <path d="m6 6 12 12" />
            </svg>
          </Button>
        </div>

        {/* 탭 컨텐츠 */}
        <div className="flex-1 overflow-y-auto">
          <Tabs defaultValue="auth" className="w-full">
            <div className="px-6 pt-4">
              <TabsList className="w-full">
                <TabsTrigger value="auth" className="flex-1">
                  인증
                </TabsTrigger>
                <TabsTrigger value="master" className="flex-1">
                  종목 마스터파일
                </TabsTrigger>
                <TabsTrigger value="other" className="flex-1">
                  기타
                </TabsTrigger>
              </TabsList>
            </div>

            <div className="px-6 py-4">
              <TabsContent value="auth">
                <AuthSection />
              </TabsContent>

              <TabsContent value="master">
                <MasterFileSection />
              </TabsContent>

              <TabsContent value="other">
                <div className="space-y-4 text-sm text-muted-foreground">
                  <p>추가 설정은 향후 업데이트에서 제공될 예정입니다.</p>
                  <ul className="list-disc list-inside space-y-1 text-xs">
                    <li>알림 설정 (주문 체결 · 목표가 도달 · 손절)</li>
                    <li>KillSwitch 임계값 조정</li>
                    <li>DailyOrderLimit 한도 설정</li>
                    <li>데이터 보존 정책</li>
                  </ul>
                </div>
              </TabsContent>
            </div>
          </Tabs>
        </div>
      </div>
    </div>
  )
}
