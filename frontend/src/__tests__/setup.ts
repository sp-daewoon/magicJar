import '@testing-library/jest-dom'

function createWorkerLocalStorage(): Storage {
  const values = new Map<string, string>()

  return {
    get length() {
      return values.size
    },
    clear() {
      values.clear()
    },
    getItem(key: string) {
      return values.get(String(key)) ?? null
    },
    key(index: number) {
      return [...values.keys()][index] ?? null
    },
    removeItem(key: string) {
      values.delete(String(key))
    },
    setItem(key: string, value: string) {
      values.set(String(key), String(value))
    },
  }
}

// Node 26 exposes an experimental process-global localStorage. Without
// --localstorage-file it is undefined; with a shared file Vitest workers race
// through clear()/setItem(). Test storage must therefore be worker-local and
// independent from the host Node process.
const localStorage = createWorkerLocalStorage()
const sessionStorage = createWorkerLocalStorage()
for (const target of new Set<object>([globalThis, window, document.defaultView].filter(Boolean) as object[])) {
  Object.defineProperty(target, 'localStorage', { configurable: true, value: localStorage })
  Object.defineProperty(target, 'sessionStorage', { configurable: true, value: sessionStorage })
}

// jsdom은 ResizeObserver 미지원 — LWC v4 차트 컴포넌트 테스트 시 polyfill
if (typeof ResizeObserver === 'undefined') {
  global.ResizeObserver = class ResizeObserver {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}

// 주의: IntersectionObserver는 전역 polyfill하지 않는다.
// useInViewport(NewsPanel·DisclosurePanel 등)는 IO 미존재 시 hasEntered=true로
// graceful fallback하는데, 전역 polyfill을 두면 이 fallback이 깨진다.
// IO가 필요한 sentinel 컴포넌트 테스트는 각 파일에서 국소 polyfill한다(StrategyList 등).
