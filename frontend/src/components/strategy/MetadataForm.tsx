/**
 * T8-T4: 전략 메타데이터 폼
 * 전략 이름·설명·태그·저자
 */

import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { Badge } from '@/components/ui/badge'
import type { StrategyMetadata } from '@/api/strategy'

interface MetadataFormProps {
  metadata: StrategyMetadata
  onChange: (metadata: StrategyMetadata) => void
}

export function MetadataForm({ metadata, onChange }: MetadataFormProps) {
  function handleChange(field: keyof StrategyMetadata, value: string) {
    onChange({ ...metadata, [field]: value })
  }

  const tagList = metadata.tags
    ? metadata.tags.split(',').map((t) => t.trim()).filter(Boolean)
    : []

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="text-base">전략 정보</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        {/* 전략 이름 */}
        <div className="space-y-1.5">
          <Label htmlFor="meta-name" className="text-sm">
            전략 이름 <span className="text-destructive">*</span>
          </Label>
          <Input
            id="meta-name"
            value={metadata.name}
            onChange={(e) => handleChange('name', e.target.value)}
            placeholder="예: MA 골든크로스 전략"
            required
          />
        </div>

        {/* 설명 */}
        <div className="space-y-1.5">
          <Label htmlFor="meta-description" className="text-sm">
            설명
          </Label>
          <Textarea
            id="meta-description"
            value={metadata.description}
            onChange={(e) => handleChange('description', e.target.value)}
            placeholder="전략의 원리와 사용 조건을 간략히 입력하세요"
            rows={3}
          />
        </div>

        {/* 태그 */}
        <div className="space-y-1.5">
          <Label htmlFor="meta-tags" className="text-sm">
            태그 (쉼표 구분)
          </Label>
          <Input
            id="meta-tags"
            value={metadata.tags}
            onChange={(e) => handleChange('tags', e.target.value)}
            placeholder="예: MA,모멘텀,단기"
          />
          {tagList.length > 0 && (
            <div className="flex flex-wrap gap-1 pt-1">
              {tagList.map((tag) => (
                <Badge key={tag} variant="secondary" className="text-xs">
                  {tag}
                </Badge>
              ))}
            </div>
          )}
        </div>

        {/* 저자 */}
        <div className="space-y-1.5">
          <Label htmlFor="meta-author" className="text-sm">
            저자
          </Label>
          <Input
            id="meta-author"
            value={metadata.author}
            onChange={(e) => handleChange('author', e.target.value)}
            placeholder="예: 홍길동"
          />
        </div>
      </CardContent>
    </Card>
  )
}
