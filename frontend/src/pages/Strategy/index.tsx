import { useState } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'

const DSL_PLACEHOLDER = `# 전략 DSL 예시
strategy "MA 교차 전략" {
  universe: ["005930", "000660"]

  entry {
    condition: ma(close, 5) crosses_above ma(close, 20)
    order: BUY quantity 10
  }

  exit {
    condition: ma(close, 5) crosses_below ma(close, 20)
    order: SELL all
    stop_loss: -5%
    take_profit: +10%
  }
}
`

export default function StrategyPage() {
  const [dsl, setDsl] = useState(DSL_PLACEHOLDER)
  const [saved, setSaved] = useState(false)

  function handleSave() {
    // P4 통합 후 api 호출
    setSaved(true)
    setTimeout(() => setSaved(false), 2000)
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">전략 편집</h1>
        <span className="text-sm text-muted-foreground">
          P4 통합 후 저장·컴파일·미리보기 기능 연결 예정
        </span>
      </div>

      <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
        {/* DSL 에디터 */}
        <Card>
          <CardHeader>
            <CardTitle>전략 DSL</CardTitle>
          </CardHeader>
          <CardContent>
            <textarea
              value={dsl}
              onChange={(e) => setDsl(e.target.value)}
              className="h-64 w-full resize-none rounded border bg-muted/10 p-3 font-mono text-sm focus:outline-none focus:ring-1 focus:ring-ring"
            />
            <div className="mt-3 flex gap-2">
              <Button onClick={handleSave} size="sm">
                {saved ? '저장됨' : '저장'}
              </Button>
              <Button variant="outline" size="sm">
                검증
              </Button>
            </div>
          </CardContent>
        </Card>

        {/* 전략 목록 */}
        <Card>
          <CardHeader>
            <CardTitle>저장된 전략</CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-2">
              {['MA 교차 전략', 'RSI 과매수/과매도', '볼린저 밴드 돌파'].map((name) => (
                <li
                  key={name}
                  className="flex items-center justify-between rounded border p-2 text-sm hover:bg-muted/30"
                >
                  <span>{name}</span>
                  <div className="flex gap-1">
                    <Button variant="ghost" size="sm" className="h-6 px-2 text-xs">
                      편집
                    </Button>
                    <Button variant="ghost" size="sm" className="h-6 px-2 text-xs">
                      백테스트
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
