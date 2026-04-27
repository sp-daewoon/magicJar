import { describe, it, expect } from 'vitest'
import { cn } from './cn'

describe('cn', () => {
  it('단일 클래스 반환', () => {
    expect(cn('foo')).toBe('foo')
  })

  it('복수 클래스 결합', () => {
    expect(cn('foo', 'bar')).toBe('foo bar')
  })

  it('tailwind 충돌 병합 (twMerge)', () => {
    // p-2와 p-4가 충돌할 때 뒤쪽이 이김
    expect(cn('p-2', 'p-4')).toBe('p-4')
  })

  it('falsy 값 무시 (clsx)', () => {
    expect(cn('foo', false && 'bar', undefined, null, 'baz')).toBe('foo baz')
  })

  it('조건부 클래스', () => {
    const active = true
    expect(cn('base', active && 'active')).toBe('base active')
  })
})
