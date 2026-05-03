/**
 * T8-T1: Strategy Builder 페이지
 * 라우트: /strategy/builder (기존 /strategy placeholder 대체)
 * 3 컬럼 레이아웃:
 *   좌측: 프리셋 목록 (10개) + 내 전략 (LocalStorage)
 *   중앙: 5단계 빌더 (탭)
 *   우측: yaml 미리보기 (실시간)
 */

import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs'
import { IndicatorPicker } from '@/components/strategy/IndicatorPicker'
import { ConditionBuilder } from '@/components/strategy/ConditionBuilder'
import { RiskForm } from '@/components/strategy/RiskForm'
import { MetadataForm } from '@/components/strategy/MetadataForm'
import { YamlPreview } from '@/components/strategy/YamlPreview'
import { useStrategyStorage } from '@/hooks/useStrategyStorage'
import type { StrategyBuilderState, SavedStrategy } from '@/api/strategy'
import { createInitialState, PRESET_STRATEGIES } from '@/api/strategy'

// 탭 단계 정의
const STEPS = [
  { id: 'preset', label: '프리셋' },
  { id: 'indicators', label: '지표' },
  { id: 'entry', label: '진입' },
  { id: 'exit', label: '청산' },
  { id: 'risk', label: '리스크' },
  { id: 'save', label: '저장' },
] as const

type StepId = (typeof STEPS)[number]['id']

export default function StrategyBuilderPage() {
  const [activeStep, setActiveStep] = useState<StepId>('indicators')
  const [builderState, setBuilderState] = useState<StrategyBuilderState>(createInitialState)
  const [saveMessage, setSaveMessage] = useState<string | null>(null)

  const { savedStrategies, saveStrategy, deleteStrategy } = useStrategyStorage()

  function handleLoadPreset(state: StrategyBuilderState) {
    setBuilderState(state)
    setActiveStep('indicators')
    setSaveMessage(null)
  }

  function handleLoadSaved(saved: SavedStrategy) {
    setBuilderState(saved.state)
    setActiveStep('indicators')
    setSaveMessage(null)
  }

  function handleSave() {
    if (!builderState.metadata.name.trim()) {
      setSaveMessage('전략 이름을 먼저 입력하세요 (저장 탭)')
      setActiveStep('save')
      return
    }
    saveStrategy(builderState)
    setSaveMessage(`"${builderState.metadata.name}" 저장 완료`)
    setTimeout(() => setSaveMessage(null), 3000)
  }

  function handleNew() {
    setBuilderState(createInitialState())
    setActiveStep('indicators')
    setSaveMessage(null)
  }

  function handleImportYaml(imported: StrategyBuilderState) {
    setBuilderState(imported)
    setSaveMessage('YAML에서 전략을 가져왔습니다.')
    setTimeout(() => setSaveMessage(null), 3000)
  }

  // 빌더 상태 업데이트 헬퍼
  function update<K extends keyof StrategyBuilderState>(
    key: K,
    value: StrategyBuilderState[K],
  ) {
    setBuilderState((prev) => ({ ...prev, [key]: value }))
  }

  return (
    <div className="space-y-3">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold">전략 빌더</h1>
          <p className="text-sm text-muted-foreground mt-0.5">
            GUI 블록 빌더 — 지표 5종 + 조건 설정 + YAML 양방향
          </p>
        </div>
        <div className="flex gap-2">
          {saveMessage && (
            <span className="text-sm text-green-600 bg-green-50 border border-green-200 rounded px-3 py-1.5">
              {saveMessage}
            </span>
          )}
          <Button variant="outline" size="sm" onClick={handleNew}>
            새 전략
          </Button>
          <Button size="sm" onClick={handleSave}>
            저장
          </Button>
        </div>
      </div>

      {/* 3 컬럼 레이아웃 */}
      <div className="grid grid-cols-1 gap-4 xl:grid-cols-[280px_1fr_320px]">

        {/* ── 좌측: 전략 목록 ── */}
        <aside className="space-y-4">
          {/* 프리셋 */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">프리셋 전략 (10)</CardTitle>
            </CardHeader>
            <CardContent className="p-3 space-y-1">
              {PRESET_STRATEGIES.map((preset) => (
                <button
                  key={preset.id}
                  onClick={() => handleLoadPreset(preset.state)}
                  className="w-full text-left rounded px-2 py-1.5 text-sm hover:bg-muted/50 transition-colors group"
                  title={preset.description}
                >
                  <span className="block font-medium text-foreground group-hover:text-primary">
                    {preset.name}
                  </span>
                  <span className="block text-xs text-muted-foreground truncate">
                    {preset.description}
                  </span>
                </button>
              ))}
            </CardContent>
          </Card>

          {/* 내 전략 */}
          <Card>
            <CardHeader className="pb-2">
              <CardTitle className="text-sm">
                내 전략 ({savedStrategies.length})
              </CardTitle>
            </CardHeader>
            <CardContent className="p-3">
              {savedStrategies.length === 0 ? (
                <p className="text-xs text-muted-foreground text-center py-2">
                  저장된 전략이 없습니다
                </p>
              ) : (
                <div className="space-y-1">
                  {savedStrategies.map((saved) => (
                    <div
                      key={saved.id}
                      className="flex items-center justify-between rounded px-2 py-1.5 hover:bg-muted/30 group"
                    >
                      <button
                        onClick={() => handleLoadSaved(saved)}
                        className="flex-1 text-left"
                      >
                        <span className="block text-sm font-medium group-hover:text-primary">
                          {saved.state.metadata.name}
                        </span>
                        <span className="block text-xs text-muted-foreground">
                          {new Date(saved.updatedAt).toLocaleDateString('ko-KR')}
                        </span>
                      </button>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => deleteStrategy(saved.id)}
                        className="h-6 w-6 p-0 opacity-0 group-hover:opacity-100 text-muted-foreground hover:text-destructive"
                        aria-label={`${saved.state.metadata.name} 삭제`}
                      >
                        x
                      </Button>
                    </div>
                  ))}
                </div>
              )}
            </CardContent>
          </Card>
        </aside>

        {/* ── 중앙: 5단계 빌더 ── */}
        <main className="min-w-0">
          {/* 현재 전략명 표시 */}
          {builderState.metadata.name && (
            <div className="mb-3 flex items-center gap-2">
              <span className="text-sm font-medium">{builderState.metadata.name}</span>
              {builderState.metadata.tags && (
                <div className="flex gap-1">
                  {builderState.metadata.tags
                    .split(',')
                    .map((t) => t.trim())
                    .filter(Boolean)
                    .map((tag) => (
                      <Badge key={tag} variant="secondary" className="text-xs">
                        {tag}
                      </Badge>
                    ))}
                </div>
              )}
            </div>
          )}

          <Tabs value={activeStep} onValueChange={(v) => setActiveStep(v as StepId)}>
            <TabsList className="mb-4 flex-wrap h-auto gap-0.5">
              {STEPS.map((step, idx) => (
                <TabsTrigger key={step.id} value={step.id} className="text-xs">
                  {idx + 1}. {step.label}
                </TabsTrigger>
              ))}
            </TabsList>

            {/* Step 1: 프리셋 안내 */}
            <TabsContent value="preset">
              <Card>
                <CardHeader>
                  <CardTitle className="text-base">프리셋 전략 불러오기</CardTitle>
                </CardHeader>
                <CardContent>
                  <p className="text-sm text-muted-foreground mb-4">
                    좌측 목록에서 프리셋 전략을 클릭하면 빌더에 자동으로 채워집니다.
                    프리셋을 수정하거나 저장해 자신만의 전략으로 발전시키세요.
                  </p>
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
                    {PRESET_STRATEGIES.map((preset) => (
                      <button
                        key={preset.id}
                        onClick={() => {
                          handleLoadPreset(preset.state)
                          setActiveStep('indicators')
                        }}
                        className="text-left rounded-md border p-3 hover:border-primary hover:bg-muted/30 transition-colors"
                      >
                        <span className="block font-medium text-sm">{preset.name}</span>
                        <span className="block text-xs text-muted-foreground mt-0.5">
                          {preset.description}
                        </span>
                        <div className="flex gap-1 mt-1 flex-wrap">
                          {preset.state.metadata.tags
                            .split(',')
                            .map((t) => t.trim())
                            .filter(Boolean)
                            .map((tag) => (
                              <Badge key={tag} variant="outline" className="text-xs px-1 py-0">
                                {tag}
                              </Badge>
                            ))}
                        </div>
                      </button>
                    ))}
                  </div>
                </CardContent>
              </Card>
            </TabsContent>

            {/* Step 2: 지표 설정 */}
            <TabsContent value="indicators">
              <IndicatorPicker
                indicators={builderState.indicators}
                onChange={(indicators) => update('indicators', indicators)}
              />
            </TabsContent>

            {/* Step 3: 진입 조건 */}
            <TabsContent value="entry">
              <ConditionBuilder
                sectionLabel="진입 조건 (Entry)"
                conditions={builderState.entryConditions}
                indicators={builderState.indicators}
                onChange={(group) => update('entryConditions', group)}
              />
            </TabsContent>

            {/* Step 4: 청산 조건 */}
            <TabsContent value="exit">
              <ConditionBuilder
                sectionLabel="청산 조건 (Exit)"
                conditions={builderState.exitConditions}
                indicators={builderState.indicators}
                onChange={(group) => update('exitConditions', group)}
              />
            </TabsContent>

            {/* Step 5: 리스크 */}
            <TabsContent value="risk">
              <RiskForm
                risk={builderState.risk}
                onChange={(risk) => update('risk', risk)}
              />
            </TabsContent>

            {/* Step 6: 저장 */}
            <TabsContent value="save">
              <div className="space-y-4">
                <MetadataForm
                  metadata={builderState.metadata}
                  onChange={(metadata) => update('metadata', metadata)}
                />
                <div className="flex gap-2">
                  <Button onClick={handleSave} className="flex-1">
                    LocalStorage에 저장
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => setActiveStep('indicators')}
                  >
                    계속 편집
                  </Button>
                </div>
                {saveMessage && (
                  <p className="text-sm text-green-600 text-center">{saveMessage}</p>
                )}
                <p className="text-xs text-muted-foreground text-center">
                  T7 머지 후 /api/strategies/validate 서버 검증 + 저장 연동 예정
                </p>
              </div>
            </TabsContent>
          </Tabs>
        </main>

        {/* ── 우측: YAML 미리보기 ── */}
        <aside>
          <YamlPreview state={builderState} onImport={handleImportYaml} />
        </aside>
      </div>
    </div>
  )
}
