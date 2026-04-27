import type { IChartApi } from 'lightweight-charts'

/**
 * LWC 공식 API: chart.takeScreenshot() → PNG Blob
 * spec §11-4, ADR 0022: html-to-image·html2canvas 등 외부 캡처 라이브러리 사용 금지.
 * 1차: LWC 공식. 실패 시 2차: pyworker /screenshot (P4 통합).
 */
export async function captureChart(chart: IChartApi): Promise<Blob> {
  const canvas = chart.takeScreenshot()
  return await canvasToBlob(canvas)
}

function canvasToBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) {
        resolve(blob)
      } else {
        reject(new Error('captureChart: canvas.toBlob returned null'))
      }
    }, 'image/png')
  })
}
