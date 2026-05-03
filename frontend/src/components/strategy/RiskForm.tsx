/**
 * T8-T4: 리스크 관리 설정 폼
 * stopLoss·takeProfit·trailingStop (퍼센트)
 */

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import type { RiskConfig } from '@/api/strategy'

interface RiskFormProps {
  risk: RiskConfig
  onChange: (risk: RiskConfig) => void
}

export function RiskForm({ risk, onChange }: RiskFormProps) {
  function handleChange(field: keyof RiskConfig, value: string) {
    onChange({ ...risk, [field]: value })
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">리스크 관리</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          {/* 손절 */}
          <div className="space-y-1.5">
            <Label htmlFor="risk-stop-loss" className="text-sm">
              손절 (%)
            </Label>
            <div className="relative">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">
                -
              </span>
              <Input
                id="risk-stop-loss"
                type="number"
                min={0}
                max={100}
                step={0.1}
                value={risk.stopLoss}
                onChange={(e) => handleChange('stopLoss', e.target.value)}
                placeholder="예: 5"
                className="pl-7"
              />
            </div>
            <p className="text-xs text-muted-foreground">
              진입 대비 하락 시 자동 청산
            </p>
          </div>

          {/* 익절 */}
          <div className="space-y-1.5">
            <Label htmlFor="risk-take-profit" className="text-sm">
              익절 (%)
            </Label>
            <div className="relative">
              <span className="absolute left-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">
                +
              </span>
              <Input
                id="risk-take-profit"
                type="number"
                min={0}
                max={1000}
                step={0.1}
                value={risk.takeProfit}
                onChange={(e) => handleChange('takeProfit', e.target.value)}
                placeholder="예: 10"
                className="pl-7"
              />
            </div>
            <p className="text-xs text-muted-foreground">
              목표 수익률 달성 시 청산
            </p>
          </div>

          {/* 트레일링 스탑 */}
          <div className="space-y-1.5">
            <Label htmlFor="risk-trailing-stop" className="text-sm">
              트레일링 스탑 (%)
            </Label>
            <Input
              id="risk-trailing-stop"
              type="number"
              min={0}
              max={100}
              step={0.1}
              value={risk.trailingStop}
              onChange={(e) => handleChange('trailingStop', e.target.value)}
              placeholder="예: 3 (비워두면 비활성)"
            />
            <p className="text-xs text-muted-foreground">
              고점 대비 하락 시 청산 (0 = 비활성)
            </p>
          </div>
        </div>

        {/* 설정 요약 */}
        {(risk.stopLoss || risk.takeProfit || risk.trailingStop) && (
          <div className="rounded-md bg-muted/30 p-3">
            <p className="text-xs font-medium text-muted-foreground mb-1">리스크 요약</p>
            <div className="flex flex-wrap gap-3 text-sm">
              {risk.stopLoss && (
                <span className="text-red-500">
                  손절 -{risk.stopLoss}%
                </span>
              )}
              {risk.takeProfit && (
                <span className="text-green-600">
                  익절 +{risk.takeProfit}%
                </span>
              )}
              {risk.trailingStop && (
                <span className="text-blue-500">
                  트레일 {risk.trailingStop}%
                </span>
              )}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
