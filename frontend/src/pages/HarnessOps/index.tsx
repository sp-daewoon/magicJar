import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

interface AgentCard {
  name: string
  role: string
  model: string
  status: 'IDLE' | 'RUNNING' | 'DONE' | 'ERROR'
  lastTask?: string
}

const AGENTS: AgentCard[] = [
  { name: 'architect', role: '설계·ADR', model: 'opus', status: 'DONE', lastTask: 'ADR 0019~0026 발행' },
  { name: 'backend-core', role: 'JVM 3앱 + Gradle', model: 'sonnet', status: 'DONE', lastTask: 'P2 완료' },
  { name: 'market-data', role: 'KIS REST/WS', model: 'sonnet', status: 'IDLE' },
  { name: 'strategy-engine', role: '도메인 전략', model: 'opus', status: 'IDLE' },
  { name: 'ai-engineer', role: 'Spring AI + pyworker', model: 'opus', status: 'RUNNING', lastTask: 'P3-pyworker 진행중' },
  { name: 'frontend', role: 'React SPA', model: 'sonnet', status: 'RUNNING', lastTask: 'P3-frontend 진행중' },
  { name: 'qa', role: 'QA On-Demand', model: 'sonnet', status: 'IDLE' },
]

const STATUS_STYLE: Record<AgentCard['status'], string> = {
  IDLE: 'bg-gray-100 text-gray-600',
  RUNNING: 'bg-blue-100 text-blue-700',
  DONE: 'bg-green-100 text-green-700',
  ERROR: 'bg-red-100 text-red-700',
}

const STATUS_LABEL: Record<AgentCard['status'], string> = {
  IDLE: '대기',
  RUNNING: '실행 중',
  DONE: '완료',
  ERROR: '오류',
}

export default function HarnessOpsPage() {
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-bold">하네스 운영 현황</h1>
        <span className="text-sm text-muted-foreground">읽기 전용 — P4 통합 후 실시간 갱신</span>
      </div>

      {/* Phase 타임라인 */}
      <Card>
        <CardHeader>
          <CardTitle>Phase 타임라인</CardTitle>
        </CardHeader>
        <CardContent>
          <ol className="relative border-l border-muted-foreground/20 space-y-4 pl-4">
            {[
              { phase: 'P1', title: 'MSA 재구성 1단계', status: 'DONE', date: '2026-04-27' },
              { phase: 'P2', title: 'JVM 3앱 + Kafka 와이어링', status: 'DONE', date: '2026-04-27' },
              {
                phase: 'P3',
                title: 'pyworker(Python) + frontend(React) 병렬',
                status: 'RUNNING',
                date: '2026-04-27',
              },
              { phase: 'P4', title: 'Docker Compose 통합 스모크', status: 'PENDING', date: '-' },
            ].map(({ phase, title, status, date }) => (
              <li key={phase} className="flex items-start gap-3">
                <span
                  className={`-ml-[1.125rem] mt-1 h-3 w-3 shrink-0 rounded-full border-2 ${
                    status === 'DONE'
                      ? 'border-green-500 bg-green-500'
                      : status === 'RUNNING'
                        ? 'border-blue-500 bg-blue-200 animate-pulse'
                        : 'border-gray-300 bg-white'
                  }`}
                />
                <div>
                  <p className="text-sm font-medium">
                    <span className="mr-1 text-muted-foreground">{phase}</span>
                    {title}
                  </p>
                  <p className="text-xs text-muted-foreground">{date}</p>
                </div>
              </li>
            ))}
          </ol>
        </CardContent>
      </Card>

      {/* 에이전트 카드 */}
      <div>
        <h2 className="mb-3 text-lg font-semibold">에이전트 현황</h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
          {AGENTS.map((agent) => (
            <Card key={agent.name}>
              <CardContent className="pt-4">
                <div className="flex items-start justify-between">
                  <div>
                    <p className="font-semibold">{agent.name}</p>
                    <p className="text-xs text-muted-foreground">{agent.role}</p>
                    <p className="mt-0.5 text-xs text-muted-foreground">model: {agent.model}</p>
                  </div>
                  <span
                    className={`rounded px-1.5 py-0.5 text-xs font-medium ${STATUS_STYLE[agent.status]}`}
                  >
                    {STATUS_LABEL[agent.status]}
                  </span>
                </div>
                {agent.lastTask && (
                  <p className="mt-2 text-xs text-muted-foreground">{agent.lastTask}</p>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      </div>

      {/* ADR 목록 */}
      <Card>
        <CardHeader>
          <CardTitle>최근 ADR</CardTitle>
        </CardHeader>
        <CardContent>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-muted-foreground">
                <th className="pb-2 text-left">번호</th>
                <th className="pb-2 text-left">제목</th>
                <th className="pb-2 text-left">상태</th>
              </tr>
            </thead>
            <tbody>
              {[
                { id: '0022', title: '프론트엔드 SPA 전환 (React+TS+Vite+LWC)', status: 'Accepted' },
                { id: '0021', title: '하이브리드 런타임 (3 JVM앱)', status: 'Accepted' },
                { id: '0020', title: 'Kafka 도입', status: 'Accepted' },
                { id: '0019', title: 'MSA 모듈 재구성', status: 'Accepted' },
              ].map((adr) => (
                <tr key={adr.id} className="border-b last:border-0">
                  <td className="py-2 font-mono text-xs">{adr.id}</td>
                  <td className="py-2">{adr.title}</td>
                  <td className="py-2">
                    <span className="rounded bg-green-100 px-1.5 py-0.5 text-xs text-green-700">
                      {adr.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </CardContent>
      </Card>
    </div>
  )
}
