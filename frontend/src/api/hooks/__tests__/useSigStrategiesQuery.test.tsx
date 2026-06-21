/**
 * useSigStrategies 검색·정렬 query 테스트 (list-ux-uplift)
 *
 * 검증:
 *  1. sigStrategyApi.list 가 q/sort 를 쿼리스트링으로 전달 (LOCKED 백엔드 계약)
 *  2. q/sort 미지정·기본값(recent)이면 파라미터 생략 (graceful)
 *  3. q/sort 변경 시 무한 쿼리가 0페이지부터 재시작 (queryKey 분리)
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import type { ReactNode } from 'react'
import { sigStrategyApi } from '../../strategy'
import { useSigStrategies } from '../useSigStrategy'

const server = setupServer()
beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }))
afterEach(() => server.resetHandlers())
afterAll(() => server.close())

function wrapper({ children }: { children: ReactNode }) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0, last: true }

describe('sigStrategyApi.list q/sort 직렬화', () => {
  it('q·sort 를 쿼리스트링으로 전달한다', async () => {
    let captured: URL | null = null
    server.use(
      http.get('*/strategies', ({ request }) => {
        captured = new URL(request.url)
        return HttpResponse.json(emptyPage)
      }),
    )
    await sigStrategyApi.list(1, 0, 20, { q: '삼성', sort: 'name' })
    expect(captured!.searchParams.get('q')).toBe('삼성')
    expect(captured!.searchParams.get('sort')).toBe('name')
  })

  it('q 미지정·sort=recent(기본)이면 파라미터를 생략한다(graceful)', async () => {
    let captured: URL | null = null
    server.use(
      http.get('*/strategies', ({ request }) => {
        captured = new URL(request.url)
        return HttpResponse.json(emptyPage)
      }),
    )
    await sigStrategyApi.list(1, 0, 20, { sort: 'recent' })
    expect(captured!.searchParams.has('q')).toBe(false)
    expect(captured!.searchParams.has('sort')).toBe(false)
    expect(captured!.searchParams.get('userId')).toBe('1')
  })

  it('공백만 입력한 q는 전송하지 않는다', async () => {
    let captured: URL | null = null
    server.use(
      http.get('*/strategies', ({ request }) => {
        captured = new URL(request.url)
        return HttpResponse.json(emptyPage)
      }),
    )
    await sigStrategyApi.list(1, 0, 20, { q: '   ' })
    expect(captured!.searchParams.has('q')).toBe(false)
  })
})

describe('useSigStrategies q/sort 변경 시 query 재시작', () => {
  it('sort 변경 시 새 요청이 발생한다', async () => {
    const sortsSeen: string[] = []
    server.use(
      http.get('*/strategies', ({ request }) => {
        const url = new URL(request.url)
        sortsSeen.push(url.searchParams.get('sort') ?? 'recent')
        return HttpResponse.json(emptyPage)
      }),
    )

    const { result, rerender } = renderHook(
      ({ sort }: { sort: 'recent' | 'name' | 'status' }) => useSigStrategies(1, 20, { sort }),
      { wrapper, initialProps: { sort: 'recent' as 'recent' | 'name' | 'status' } },
    )
    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    rerender({ sort: 'name' })
    await waitFor(() => expect(sortsSeen).toContain('name'))
    // recent(기본·미전송) + name 두 번 요청
    expect(sortsSeen).toContain('recent')
    expect(sortsSeen).toContain('name')
  })
})
