import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

interface JournalEntry {
  id: string
  date: string
  symbol: string
  symbolName: string
  side: 'BUY' | 'SELL'
  price: number
  quantity: number
  pnl?: number
  memo?: string
}

const MOCK_ENTRIES: JournalEntry[] = [
  {
    id: '1',
    date: '2024-12-20',
    symbol: '005930',
    symbolName: '삼성전자',
    side: 'BUY',
    price: 78_500,
    quantity: 10,
    memo: 'MA 골든크로스 매수 진입',
  },
  {
    id: '2',
    date: '2024-12-27',
    symbol: '005930',
    symbolName: '삼성전자',
    side: 'SELL',
    price: 81_200,
    quantity: 10,
    pnl: 27_000,
    memo: 'RSI 과매수 청산',
  },
]

function formatKrw(value: number) {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency',
    currency: 'KRW',
    minimumFractionDigits: 0,
  }).format(value)
}

export default function JournalPage() {
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">매매일지</h1>
        <span className="text-sm text-muted-foreground">
          P4 통합 후 실제 체결 데이터 + AI 복기 연결 예정
        </span>
      </div>

      <div className="space-y-3">
        {MOCK_ENTRIES.map((entry) => (
          <Card key={entry.id}>
            <CardHeader className="pb-2">
              <div className="flex items-center justify-between">
                <CardTitle className="text-base">
                  <span
                    className={`mr-2 rounded px-1.5 py-0.5 text-xs font-bold ${
                      entry.side === 'BUY'
                        ? 'bg-red-100 text-red-700'
                        : 'bg-blue-100 text-blue-700'
                    }`}
                  >
                    {entry.side === 'BUY' ? '매수' : '매도'}
                  </span>
                  {entry.symbolName}
                  <span className="ml-1 text-sm font-normal text-muted-foreground">
                    {entry.symbol}
                  </span>
                </CardTitle>
                <span className="text-sm text-muted-foreground">{entry.date}</span>
              </div>
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="flex gap-6 text-sm">
                <span>
                  <span className="text-muted-foreground">단가:</span>{' '}
                  <span className="font-medium">{formatKrw(entry.price)}</span>
                </span>
                <span>
                  <span className="text-muted-foreground">수량:</span>{' '}
                  <span className="font-medium">{entry.quantity.toLocaleString('ko-KR')}주</span>
                </span>
                {entry.pnl !== undefined && (
                  <span>
                    <span className="text-muted-foreground">손익:</span>{' '}
                    <span
                      className={`font-medium ${entry.pnl >= 0 ? 'text-red-500' : 'text-blue-500'}`}
                    >
                      {formatKrw(entry.pnl)}
                    </span>
                  </span>
                )}
              </div>
              {entry.memo && (
                <p className="text-sm text-muted-foreground">{entry.memo}</p>
              )}
              {/* AI 복기 패널 — P4 통합 후 채움 */}
              <div className="rounded border bg-muted/30 p-2 text-xs text-muted-foreground">
                AI 복기 분석 — P4 ai-engineer 연결 후 표시
              </div>
            </CardContent>
          </Card>
        ))}
      </div>
    </div>
  )
}
