import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { findCurrentDocDrift } from '../verify-docs-current.mjs'

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'magicjar-doc-drift-'))
  const write = (relative, content) => {
    const target = join(root, relative)
    mkdirSync(join(target, '..'), { recursive: true })
    writeFileSync(target, content, 'utf8')
  }
  return { root, write, cleanup: () => rmSync(root, { recursive: true, force: true }) }
}

test('current-doc verifier reports derived version/module/model/profile drift', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write('settings.gradle.kts', 'include("domain", "api")\n')
  f.write('README.md', 'version-v1.0.0 JVM 4 Modules Sonnet ADR 63건\n')
  f.write('docs/operations/initial-setup.md', 'SPRING_PROFILES_ACTIVE=mock\n')
  f.write('docs/operations/runbook/R5-redeploy.md', '4 모듈 healthy\n')
  f.write('docs/architecture/master-architecture.md', 'Status: DRAFT\n')
  f.write('.claude/agents/architect.md', '---\nmodel: sonnet\n---\n')

  const drift = findCurrentDocDrift(f.root, { latestTag: 'v2.0.0', expectedAgents: ['architect'] })
  assert.ok(drift.some((item) => item.includes('latest tag v2.0.0')))
  assert.ok(drift.some((item) => item.includes('JVM module count 2')))
  assert.ok(drift.some((item) => item.includes('deprecated Sonnet')))
  assert.ok(drift.some((item) => item.includes('mock boot profile')))
  assert.ok(drift.some((item) => item.includes('master architecture is still DRAFT')))
  assert.ok(drift.some((item) => item.includes('architect model=sonnet')))
  assert.ok(drift.some((item) => item.includes('nine-service Compose contract')))
})

test('current-doc verifier rejects deprecated market sources', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write('settings.gradle.kts', 'include("domain")\n')
  f.write('README.md', 'version-v1.0.0 JVM 1 Modules Docker Compose 9 컨테이너 Yahoo Finance Naver RSS\n')
  f.write('docs/architecture/master-architecture.md', 'KafkaTopics.kt\n')

  const drift = findCurrentDocDrift(f.root, { latestTag: 'v1.0.0', expectedAgents: [] })
  assert.ok(drift.some((item) => item.includes('deprecated Yahoo Finance')))
  assert.ok(drift.some((item) => item.includes('deprecated Naver RSS')))
})
