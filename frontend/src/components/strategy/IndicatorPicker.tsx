/**
 * T8-T2: 지표 Picker 컴포넌트 (1차 5종)
 * SMA·EMA·RSI·MACD·볼린저밴드 추가/삭제 + 파라미터 편집 + alias 자동/수동 설정
 */

import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select, SelectOption } from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import type {
  IndicatorInstance,
  IndicatorType,
  SmaParams,
  EmaParams,
  RsiParams,
  MacdParams,
  BbParams,
} from '@/api/strategy'
import {
  INDICATOR_LABELS,
  INDICATOR_DEFAULTS,
  generateAlias,
} from '@/api/strategy'

interface IndicatorPickerProps {
  indicators: IndicatorInstance[]
  onChange: (indicators: IndicatorInstance[]) => void
}

function SmaEmaForm({
  params,
  onChange,
}: {
  params: SmaParams | EmaParams
  onChange: (p: SmaParams | EmaParams) => void
}) {
  return (
    <div className="flex items-center gap-2">
      <Label className="w-16 text-xs text-muted-foreground">기간</Label>
      <Input
        type="number"
        min={1}
        max={500}
        value={params.period}
        onChange={(e) => onChange({ period: parseInt(e.target.value) || 1 })}
        className="h-8 w-24 text-sm"
      />
    </div>
  )
}

function RsiForm({
  params,
  onChange,
}: {
  params: RsiParams
  onChange: (p: RsiParams) => void
}) {
  return (
    <div className="flex items-center gap-2">
      <Label className="w-16 text-xs text-muted-foreground">기간</Label>
      <Input
        type="number"
        min={1}
        max={100}
        value={params.period}
        onChange={(e) => onChange({ period: parseInt(e.target.value) || 1 })}
        className="h-8 w-24 text-sm"
      />
    </div>
  )
}

function MacdForm({
  params,
  onChange,
}: {
  params: MacdParams
  onChange: (p: MacdParams) => void
}) {
  return (
    <div className="flex flex-wrap gap-2">
      <div className="flex items-center gap-1">
        <Label className="text-xs text-muted-foreground">단기</Label>
        <Input
          type="number"
          min={1}
          max={200}
          value={params.fastPeriod}
          onChange={(e) => onChange({ ...params, fastPeriod: parseInt(e.target.value) || 1 })}
          className="h-8 w-16 text-sm"
        />
      </div>
      <div className="flex items-center gap-1">
        <Label className="text-xs text-muted-foreground">장기</Label>
        <Input
          type="number"
          min={1}
          max={200}
          value={params.slowPeriod}
          onChange={(e) => onChange({ ...params, slowPeriod: parseInt(e.target.value) || 1 })}
          className="h-8 w-16 text-sm"
        />
      </div>
      <div className="flex items-center gap-1">
        <Label className="text-xs text-muted-foreground">시그널</Label>
        <Input
          type="number"
          min={1}
          max={100}
          value={params.signalPeriod}
          onChange={(e) => onChange({ ...params, signalPeriod: parseInt(e.target.value) || 1 })}
          className="h-8 w-16 text-sm"
        />
      </div>
    </div>
  )
}

function BbForm({
  params,
  onChange,
}: {
  params: BbParams
  onChange: (p: BbParams) => void
}) {
  return (
    <div className="flex flex-wrap gap-2">
      <div className="flex items-center gap-1">
        <Label className="text-xs text-muted-foreground">기간</Label>
        <Input
          type="number"
          min={1}
          max={200}
          value={params.period}
          onChange={(e) => onChange({ ...params, period: parseInt(e.target.value) || 1 })}
          className="h-8 w-16 text-sm"
        />
      </div>
      <div className="flex items-center gap-1">
        <Label className="text-xs text-muted-foreground">표준편차</Label>
        <Input
          type="number"
          min={0.1}
          max={5}
          step={0.1}
          value={params.multiplier}
          onChange={(e) => onChange({ ...params, multiplier: parseFloat(e.target.value) || 2 })}
          className="h-8 w-16 text-sm"
        />
      </div>
    </div>
  )
}

function IndicatorParamsForm({
  indicator,
  onParamsChange,
}: {
  indicator: IndicatorInstance
  onParamsChange: (id: string, params: IndicatorInstance['params']) => void
}) {
  switch (indicator.type) {
    case 'sma':
    case 'ema':
      return (
        <SmaEmaForm
          params={indicator.params as SmaParams}
          onChange={(p) => onParamsChange(indicator.id, p)}
        />
      )
    case 'rsi':
      return (
        <RsiForm
          params={indicator.params as RsiParams}
          onChange={(p) => onParamsChange(indicator.id, p)}
        />
      )
    case 'macd':
      return (
        <MacdForm
          params={indicator.params as MacdParams}
          onChange={(p) => onParamsChange(indicator.id, p)}
        />
      )
    case 'bb':
      return (
        <BbForm
          params={indicator.params as BbParams}
          onChange={(p) => onParamsChange(indicator.id, p)}
        />
      )
    default:
      return null
  }
}

export function IndicatorPicker({ indicators, onChange }: IndicatorPickerProps) {
  const [selectedType, setSelectedType] = useState<IndicatorType>('sma')

  function handleAdd() {
    const existingAliases = indicators.map((i) => i.alias)
    const alias = generateAlias(selectedType, existingAliases)
    const newIndicator: IndicatorInstance = {
      id: `ind-${Date.now()}`,
      type: selectedType,
      alias,
      params: { ...INDICATOR_DEFAULTS[selectedType] },
    }
    onChange([...indicators, newIndicator])
  }

  function handleRemove(id: string) {
    onChange(indicators.filter((i) => i.id !== id))
  }

  function handleAliasChange(id: string, alias: string) {
    onChange(indicators.map((i) => (i.id === id ? { ...i, alias } : i)))
  }

  function handleParamsChange(id: string, params: IndicatorInstance['params']) {
    onChange(indicators.map((i) => (i.id === id ? { ...i, params } : i)))
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">지표 설정</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* 지표 추가 행 */}
        <div className="flex items-center gap-2">
          <Select
            value={selectedType}
            onChange={(e) => setSelectedType(e.target.value as IndicatorType)}
            className="flex-1"
            aria-label="지표 종류 선택"
          >
            {(Object.entries(INDICATOR_LABELS) as [IndicatorType, string][]).map(
              ([type, label]) => (
                <SelectOption key={type} value={type}>
                  {label}
                </SelectOption>
              ),
            )}
          </Select>
          <Button onClick={handleAdd} size="sm" className="shrink-0">
            + 추가
          </Button>
        </div>

        {/* 추가된 지표 목록 */}
        {indicators.length === 0 ? (
          <p className="text-sm text-muted-foreground text-center py-4">
            지표를 추가하면 조건 설정에서 사용할 수 있습니다.
          </p>
        ) : (
          <div className="space-y-3">
            {indicators.map((ind) => (
              <div
                key={ind.id}
                className="rounded-md border bg-muted/20 p-3 space-y-2"
              >
                {/* 지표 타입 배지 + 삭제 버튼 */}
                <div className="flex items-center justify-between">
                  <Badge variant="secondary" className="text-xs">
                    {INDICATOR_LABELS[ind.type]}
                  </Badge>
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => handleRemove(ind.id)}
                    className="h-6 w-6 p-0 text-muted-foreground hover:text-destructive"
                    aria-label={`${ind.alias} 삭제`}
                  >
                    x
                  </Button>
                </div>

                {/* alias 편집 */}
                <div className="flex items-center gap-2">
                  <Label htmlFor={`alias-${ind.id}`} className="w-16 text-xs text-muted-foreground">
                    별칭
                  </Label>
                  <Input
                    id={`alias-${ind.id}`}
                    value={ind.alias}
                    onChange={(e) => handleAliasChange(ind.id, e.target.value)}
                    placeholder="예: sma_fast"
                    className="h-8 flex-1 text-sm font-mono"
                  />
                </div>

                {/* 파라미터 폼 */}
                <IndicatorParamsForm
                  indicator={ind}
                  onParamsChange={handleParamsChange}
                />
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
