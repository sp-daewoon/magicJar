import { describe, it, expect } from 'vitest'
import { queryClient } from './queryClient'
import { QueryClient } from '@tanstack/react-query'

describe('queryClient', () => {
  it('QueryClient 인스턴스', () => {
    expect(queryClient).toBeInstanceOf(QueryClient)
  })

  it('staleTime 기본값 30초', () => {
    const defaults = queryClient.getDefaultOptions()
    expect(defaults.queries?.staleTime).toBe(30_000)
  })

  it('retry 기본값 1', () => {
    const defaults = queryClient.getDefaultOptions()
    expect(defaults.queries?.retry).toBe(1)
  })
})
