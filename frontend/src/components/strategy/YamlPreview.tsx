/**
 * T8-T5: yaml 미리보기·export·import
 * - 빌더 상태 → yaml string 실시간 생성
 * - 복사·다운로드(.kis.yaml) 버튼
 * - import: drag-drop 또는 파일 선택
 */

import { useRef, useState, useCallback } from 'react'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import type { StrategyBuilderState } from '@/api/strategy'
import { serializeToYaml, deserializeFromYaml } from '@/lib/strategyYamlSerializer'

interface YamlPreviewProps {
  state: StrategyBuilderState
  onImport: (imported: StrategyBuilderState) => void
}

export function YamlPreview({ state, onImport }: YamlPreviewProps) {
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [copied, setCopied] = useState(false)
  const [isDragOver, setIsDragOver] = useState(false)
  const [importError, setImportError] = useState<string | null>(null)

  const yamlContent = serializeToYaml(state)

  function handleCopy() {
    navigator.clipboard.writeText(yamlContent).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  function handleDownload() {
    const strategyName = state.metadata.name || '전략'
    const safeName = strategyName.replace(/[^a-zA-Z0-9가-힣_-]/g, '_')
    const blob = new Blob([yamlContent], { type: 'text/yaml;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${safeName}.kis.yaml`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const processYamlFile = useCallback(
    (file: File) => {
      setImportError(null)
      if (!file.name.endsWith('.yaml') && !file.name.endsWith('.yml')) {
        setImportError('.yaml 또는 .yml 파일만 가져올 수 있습니다.')
        return
      }
      const reader = new FileReader()
      reader.onload = (e) => {
        const text = e.target?.result as string
        try {
          const imported = deserializeFromYaml(text)
          onImport(imported)
        } catch (err) {
          setImportError(`YAML 파싱 오류: ${err instanceof Error ? err.message : String(err)}`)
        }
      }
      reader.readAsText(file)
    },
    [onImport],
  )

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (file) processYamlFile(file)
    // 같은 파일 재선택 허용
    e.target.value = ''
  }

  function handleDrop(e: React.DragEvent) {
    e.preventDefault()
    setIsDragOver(false)
    const file = e.dataTransfer.files?.[0]
    if (file) processYamlFile(file)
  }

  function handleDragOver(e: React.DragEvent) {
    e.preventDefault()
    setIsDragOver(true)
  }

  function handleDragLeave() {
    setIsDragOver(false)
  }

  return (
    <Card className="flex flex-col h-full">
      <CardHeader className="pb-3">
        <div className="flex items-center justify-between">
          <CardTitle className="text-base">YAML 미리보기</CardTitle>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" onClick={handleCopy}>
              {copied ? '복사됨!' : '복사'}
            </Button>
            <Button variant="outline" size="sm" onClick={handleDownload}>
              다운로드
            </Button>
          </div>
        </div>
      </CardHeader>
      <CardContent className="flex flex-col gap-3 flex-1">
        {/* yaml 내용 */}
        <pre
          className="flex-1 min-h-[300px] overflow-auto rounded-md bg-muted/30 border p-3 text-xs font-mono leading-relaxed whitespace-pre"
          aria-label="YAML 미리보기"
        >
          {yamlContent}
        </pre>

        {/* 가져오기 영역 */}
        <div
          className={`rounded-md border-2 border-dashed p-4 text-center transition-colors cursor-pointer ${
            isDragOver
              ? 'border-primary bg-primary/5'
              : 'border-muted-foreground/30 hover:border-primary/50'
          }`}
          onDrop={handleDrop}
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onClick={() => fileInputRef.current?.click()}
          role="button"
          aria-label="YAML 파일 가져오기 영역"
          tabIndex={0}
          onKeyDown={(e) => {
            if (e.key === 'Enter' || e.key === ' ') {
              fileInputRef.current?.click()
            }
          }}
        >
          <input
            ref={fileInputRef}
            type="file"
            accept=".yaml,.yml"
            className="hidden"
            onChange={handleFileChange}
            aria-hidden="true"
          />
          <p className="text-sm text-muted-foreground">
            .kis.yaml 파일을 드래그하거나 클릭해서 가져오기
          </p>
          <p className="text-xs text-muted-foreground mt-1">
            현재 빌더 상태를 덮어씁니다
          </p>
        </div>

        {importError && (
          <p className="text-xs text-destructive bg-destructive/10 rounded p-2">
            {importError}
          </p>
        )}

        <p className="text-xs text-muted-foreground">
          T7 머지 후 /api/strategies/validate 서버 검증 연동 예정
        </p>
      </CardContent>
    </Card>
  )
}
