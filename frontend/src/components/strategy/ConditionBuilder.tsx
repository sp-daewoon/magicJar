/**
 * T8-T3: 조건 빌더 컴포넌트
 * 1줄: [indicator/price] [operator] [compareTo] 드롭다운 3개
 * AND 그룹 (OR은 R2)
 * 진입·청산 별도 인스턴스
 */

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select, SelectOption } from '@/components/ui/select'
import { Badge } from '@/components/ui/badge'
import type {
  IndicatorInstance,
  ConditionGroup,
  SingleCondition,
  ConditionOperator,
  CompareToType,
  PriceField,
} from '@/api/strategy'
import {
  OPERATOR_LABELS,
  PRICE_FIELD_LABELS,
} from '@/api/strategy'

interface ConditionBuilderProps {
  /** 진입 또는 청산 구분 표시용 */
  sectionLabel: string
  conditions: ConditionGroup
  indicators: IndicatorInstance[]
  onChange: (group: ConditionGroup) => void
}

/** left 드롭다운 옵션: 지표 alias + 가격 필드 */
function LeftOptions({ indicators }: { indicators: IndicatorInstance[] }) {
  return (
    <>
      <optgroup label="가격 필드">
        {(Object.entries(PRICE_FIELD_LABELS) as [PriceField, string][]).map(([field, label]) => (
          <SelectOption key={field} value={field}>
            {label}
          </SelectOption>
        ))}
      </optgroup>
      {indicators.length > 0 && (
        <optgroup label="지표">
          {indicators.map((ind) => (
            <SelectOption key={ind.id} value={ind.alias}>
              {ind.alias} ({ind.type.toUpperCase()})
            </SelectOption>
          ))}
          {/* MACD는 서브 필드 제공 */}
          {indicators
            .filter((ind) => ind.type === 'macd')
            .flatMap((ind) => [
              <SelectOption key={`${ind.id}-line`} value={`${ind.alias}_line`}>
                {ind.alias}_line (MACD 라인)
              </SelectOption>,
              <SelectOption key={`${ind.id}-signal`} value={`${ind.alias}_signal`}>
                {ind.alias}_signal (시그널)
              </SelectOption>,
              <SelectOption key={`${ind.id}-histogram`} value={`${ind.alias}_histogram`}>
                {ind.alias}_histogram (히스토그램)
              </SelectOption>,
            ])}
          {/* BB는 서브 필드 제공 */}
          {indicators
            .filter((ind) => ind.type === 'bb')
            .flatMap((ind) => [
              <SelectOption key={`${ind.id}-upper`} value={`${ind.alias}_upper`}>
                {ind.alias}_upper (상단)
              </SelectOption>,
              <SelectOption key={`${ind.id}-middle`} value={`${ind.alias}_middle`}>
                {ind.alias}_middle (중심)
              </SelectOption>,
              <SelectOption key={`${ind.id}-lower`} value={`${ind.alias}_lower`}>
                {ind.alias}_lower (하단)
              </SelectOption>,
            ])}
        </optgroup>
      )}
    </>
  )
}

/** compareTo 옵션: 지표 + 가격 필드 + 숫자 직접 입력 */
function CompareToSelector({
  conditionId,
  compareTo,
  indicators,
  onChange,
}: {
  conditionId: string
  compareTo: SingleCondition['compareTo']
  indicators: IndicatorInstance[]
  onChange: (compareTo: SingleCondition['compareTo']) => void
}) {
  const handleTypeChange = (type: string) => {
    if (type === 'number') {
      onChange({ type: 'number', value: '0' })
    } else if (type === 'price_field') {
      onChange({ type: 'price_field', value: 'close' })
    } else {
      // indicator
      const firstAlias = indicators[0]?.alias ?? ''
      onChange({ type: 'indicator', value: firstAlias })
    }
  }

  return (
    <div className="flex gap-1 flex-1">
      {/* 타입 선택 */}
      <Select
        value={compareTo.type}
        onChange={(e) => handleTypeChange(e.target.value)}
        className="w-24"
        aria-label={`compareTo 타입 (조건 ${conditionId})`}
      >
        <SelectOption value="indicator">지표</SelectOption>
        <SelectOption value="price_field">가격</SelectOption>
        <SelectOption value="number">숫자</SelectOption>
      </Select>

      {/* 값 입력 */}
      {compareTo.type === 'number' ? (
        <Input
          type="number"
          value={compareTo.value}
          onChange={(e) => onChange({ ...compareTo, value: e.target.value })}
          placeholder="숫자 입력"
          className="h-10 flex-1 text-sm"
          aria-label={`숫자 값 (조건 ${conditionId})`}
        />
      ) : compareTo.type === 'price_field' ? (
        <Select
          value={compareTo.value}
          onChange={(e) => onChange({ ...compareTo, value: e.target.value })}
          className="flex-1"
          aria-label={`가격 필드 선택 (조건 ${conditionId})`}
        >
          {(Object.entries(PRICE_FIELD_LABELS) as [PriceField, string][]).map(([field, label]) => (
            <SelectOption key={field} value={field}>
              {label}
            </SelectOption>
          ))}
        </Select>
      ) : (
        <Select
          value={compareTo.value}
          onChange={(e) => onChange({ ...compareTo, value: e.target.value })}
          className="flex-1"
          aria-label={`지표 선택 (조건 ${conditionId})`}
        >
          {indicators.map((ind) => (
            <SelectOption key={ind.id} value={ind.alias}>
              {ind.alias}
            </SelectOption>
          ))}
          {indicators
            .filter((ind) => ind.type === 'macd')
            .flatMap((ind) => [
              <SelectOption key={`${ind.id}-line`} value={`${ind.alias}_line`}>
                {ind.alias}_line
              </SelectOption>,
              <SelectOption key={`${ind.id}-signal`} value={`${ind.alias}_signal`}>
                {ind.alias}_signal
              </SelectOption>,
              <SelectOption key={`${ind.id}-histogram`} value={`${ind.alias}_histogram`}>
                {ind.alias}_histogram
              </SelectOption>,
            ])}
          {indicators
            .filter((ind) => ind.type === 'bb')
            .flatMap((ind) => [
              <SelectOption key={`${ind.id}-upper`} value={`${ind.alias}_upper`}>
                {ind.alias}_upper
              </SelectOption>,
              <SelectOption key={`${ind.id}-middle`} value={`${ind.alias}_middle`}>
                {ind.alias}_middle
              </SelectOption>,
              <SelectOption key={`${ind.id}-lower`} value={`${ind.alias}_lower`}>
                {ind.alias}_lower
              </SelectOption>,
            ])}
          {indicators.length === 0 && (
            <SelectOption value="" disabled>
              지표를 먼저 추가하세요
            </SelectOption>
          )}
        </Select>
      )}
    </div>
  )
}

function SingleConditionRow({
  condition,
  indicators,
  onUpdate,
  onRemove,
}: {
  condition: SingleCondition
  indicators: IndicatorInstance[]
  onUpdate: (updated: SingleCondition) => void
  onRemove: () => void
}) {
  return (
    <div className="flex flex-wrap items-center gap-2 rounded border bg-background p-2">
      {/* Left */}
      <Select
        value={condition.left}
        onChange={(e) => onUpdate({ ...condition, left: e.target.value })}
        className="min-w-[110px]"
        aria-label="조건 좌변"
      >
        <LeftOptions indicators={indicators} />
      </Select>

      {/* Operator */}
      <Select
        value={condition.operator}
        onChange={(e) => onUpdate({ ...condition, operator: e.target.value as ConditionOperator })}
        className="min-w-[150px]"
        aria-label="조건 연산자"
      >
        {(Object.entries(OPERATOR_LABELS) as [ConditionOperator, string][]).map(([op, label]) => (
          <SelectOption key={op} value={op}>
            {label}
          </SelectOption>
        ))}
      </Select>

      {/* CompareTo */}
      <CompareToSelector
        conditionId={condition.id}
        compareTo={condition.compareTo}
        indicators={indicators}
        onChange={(compareTo) => onUpdate({ ...condition, compareTo })}
      />

      {/* 삭제 버튼 */}
      <Button
        variant="ghost"
        size="sm"
        onClick={onRemove}
        className="h-8 w-8 p-0 text-muted-foreground hover:text-destructive"
        aria-label="조건 삭제"
      >
        x
      </Button>
    </div>
  )
}

export function ConditionBuilder({
  sectionLabel,
  conditions,
  indicators,
  onChange,
}: ConditionBuilderProps) {
  function handleAddCondition() {
    const defaultLeft =
      indicators.length > 0 ? indicators[0].alias : 'close'
    const defaultCompareTo: SingleCondition['compareTo'] =
      indicators.length > 1
        ? { type: 'indicator' as CompareToType, value: indicators[1].alias }
        : { type: 'number' as CompareToType, value: '0' }

    const newCondition: SingleCondition = {
      id: `cond-${Date.now()}`,
      left: defaultLeft,
      operator: 'greater_than',
      compareTo: defaultCompareTo,
    }
    onChange({
      ...conditions,
      conditions: [...conditions.conditions, newCondition],
    })
  }

  function handleUpdateCondition(id: string, updated: SingleCondition) {
    onChange({
      ...conditions,
      conditions: conditions.conditions.map((c) => (c.id === id ? updated : c)),
    })
  }

  function handleRemoveCondition(id: string) {
    onChange({
      ...conditions,
      conditions: conditions.conditions.filter((c) => c.id !== id),
    })
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base">{sectionLabel}</CardTitle>
          <div className="flex items-center gap-2">
            {conditions.conditions.length > 1 && (
              <Badge variant="outline" className="text-xs">
                AND
              </Badge>
            )}
            <span className="text-xs text-muted-foreground">
              {conditions.conditions.length}개 조건
            </span>
          </div>
        </div>
      </CardHeader>
      <CardContent className="space-y-2">
        {conditions.conditions.length === 0 ? (
          <p className="text-sm text-muted-foreground text-center py-3">
            조건을 추가하세요. (여러 조건은 AND로 결합됩니다)
          </p>
        ) : (
          <div className="space-y-2">
            {conditions.conditions.map((cond, idx) => (
              <div key={cond.id}>
                {idx > 0 && (
                  <div className="flex justify-center py-1">
                    <span className="text-xs font-medium text-muted-foreground bg-muted px-2 py-0.5 rounded">
                      AND
                    </span>
                  </div>
                )}
                <SingleConditionRow
                  condition={cond}
                  indicators={indicators}
                  onUpdate={(updated) => handleUpdateCondition(cond.id, updated)}
                  onRemove={() => handleRemoveCondition(cond.id)}
                />
              </div>
            ))}
          </div>
        )}

        <Button
          variant="outline"
          size="sm"
          onClick={handleAddCondition}
          className="w-full mt-2"
        >
          + 조건 추가
        </Button>

        {conditions.conditions.length > 1 && (
          <p className="text-xs text-muted-foreground text-center">
            OR 그룹은 R2에서 지원 예정
          </p>
        )}
      </CardContent>
    </Card>
  )
}
