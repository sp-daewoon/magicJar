import '@testing-library/jest-dom'

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
