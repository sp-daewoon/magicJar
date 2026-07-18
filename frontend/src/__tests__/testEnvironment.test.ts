import { describe, expect, it } from 'vitest'

describe('Vitest Web Storage environment', () => {
  it('uses the JSDOM document storage as the global storage', () => {
    const jsdomWindow = document.defaultView

    expect(jsdomWindow).not.toBeNull()
    expect(jsdomWindow?.localStorage).toBeDefined()
    expect(globalThis.localStorage).toBe(jsdomWindow?.localStorage)
    expect(window.localStorage).toBe(jsdomWindow?.localStorage)
    expect(jsdomWindow?.sessionStorage).toBeDefined()
    expect(globalThis.sessionStorage).toBe(jsdomWindow?.sessionStorage)
    expect(window.sessionStorage).toBe(jsdomWindow?.sessionStorage)
  })

  it('keeps global and window localStorage round trips in the same isolated store', () => {
    const key = 'magicjar:test-environment:storage'
    globalThis.localStorage.setItem(key, 'wave0')

    expect(window.localStorage.getItem(key)).toBe('wave0')

    window.localStorage.removeItem(key)
  })
})
