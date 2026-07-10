/**
 * 전략 보관/해제 — userId 필수 파라미터 계약 테스트 (MSW)
 *
 * 2026-07-10 hotfix 회귀 가드: 백엔드 PUT /api/strategy/{id}/archive·unarchive 는
 * 소유자 검증용 `userId` @RequestParam 이 필수다(누락 시 400 MissingServletRequestParameter —
 * 라이브에서 보관 버튼 클릭이 전부 400으로 실패한 실사고). 프론트가 항상 붙여 보내는지 URL 로 고정한다.
 */
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { sigStrategyApi } from '../strategy'

let lastArchiveUrl: string | null = null
let lastUnarchiveUrl: string | null = null

const server = setupServer(
  http.put('/api/strategy/:id/archive', ({ request }) => {
    lastArchiveUrl = request.url
    return new HttpResponse(null, { status: 200 })
  }),
  http.put('/api/strategy/:id/unarchive', ({ request }) => {
    lastUnarchiveUrl = request.url
    return new HttpResponse(null, { status: 200 })
  }),
)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  lastArchiveUrl = null
  lastUnarchiveUrl = null
})
afterAll(() => server.close())

describe('전략 보관/해제 userId 계약', () => {
  it('archive 는 userId 쿼리 파라미터를 반드시 포함한다', async () => {
    await sigStrategyApi.archive('87', 74683223)
    expect(lastArchiveUrl).toContain('/strategy/87/archive')
    expect(lastArchiveUrl).toContain('userId=74683223')
  })

  it('unarchive 는 userId 쿼리 파라미터를 반드시 포함한다', async () => {
    await sigStrategyApi.unarchive('87', 74683223)
    expect(lastUnarchiveUrl).toContain('/strategy/87/unarchive')
    expect(lastUnarchiveUrl).toContain('userId=74683223')
  })
})
