#!/usr/bin/env node

import { execFileSync } from 'node:child_process'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { readIncludedModules } from './verify-architecture-fitness.mjs'

export const ACTIVE_AGENTS = Object.freeze([
  'architect',
  'backend-core',
  'market-data',
  'strategy-engine',
  'ai-engineer',
  'frontend',
  'qa',
])

function read(root, path) {
  const absolute = resolve(root, path)
  return existsSync(absolute) ? readFileSync(absolute, 'utf8') : ''
}

function latestTag(root) {
  try {
    return execFileSync('git', ['describe', '--tags', '--abbrev=0'], {
      cwd: root,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim()
  } catch {
    return ''
  }
}

export function findCurrentDocDrift(root, options = {}) {
  const tag = options.latestTag ?? latestTag(root)
  const expectedAgents = options.expectedAgents ?? ACTIVE_AGENTS
  const moduleCount = readIncludedModules(root).length
  const readme = read(root, 'README.md')
  const setup = read(root, 'docs/operations/initial-setup.md')
  const redeploy = read(root, 'docs/operations/runbook/R5-redeploy.md')
  const master = read(root, 'docs/architecture/master-architecture.md')
  const branching = read(root, '_workspace/arch/branching-rules.md')
  const drift = []

  if (tag && !readme.includes(`version-${tag}`)) drift.push(`README badge does not match latest tag ${tag}`)
  if (moduleCount > 0 && !readme.includes(`JVM ${moduleCount} Modules`)) {
    drift.push(`README does not declare derived JVM module count ${moduleCount}`)
  }
  if (/JVM 4 Modules|JVM 4 모듈|전체 검증 \(4 JVM 모듈\)/.test(readme)) {
    drift.push('README contains deprecated four-module architecture')
  }
  if (/\bSonnet\b/.test(readme) || /\bSonnet\b/.test(branching)) {
    drift.push('current workflow docs contain deprecated Sonnet assignment')
  }
  if (/ADR 63건|\(63건 인덱스\)/.test(readme)) drift.push('README duplicates stale ADR count')
  if (/7\s*컨테이너|Docker Compose\s+7\s*컨테이너/.test(readme) || !/Docker Compose\s+9\s*컨테이너/.test(readme)) {
    drift.push('README does not match the nine-service Compose contract')
  }
  if (/Yahoo Finance/.test(readme)) drift.push('README contains deprecated Yahoo Finance source')
  if (/Naver RSS/.test(readme)) drift.push('README contains deprecated Naver RSS source')
  if (/SPRING_PROFILES_ACTIVE=mock/.test(setup)) drift.push('initial setup still recommends mock boot profile')
  if (/\b4\s*모듈\b/.test(redeploy)) drift.push('redeploy runbook still describes four modules/apps')
  if (/Status\*\*:\s*DRAFT|Status:\s*DRAFT/.test(master)) drift.push('master architecture is still DRAFT')
  if (!master.includes('KafkaTopics.kt')) drift.push('master architecture does not delegate Kafka wire authority')

  for (const agent of expectedAgents) {
    const path = `.claude/agents/${agent}.md`
    const content = read(root, path)
    if (!content) {
      drift.push(`missing active agent definition: ${agent}`)
      continue
    }
    const model = content.match(/^model:\s*([^\s]+)\s*$/m)?.[1]
    if (model !== 'opus') drift.push(`${agent} model=${model ?? 'missing'}, expected opus`)
  }
  return drift.sort()
}

function main() {
  const root = resolve(process.argv[2] ?? process.cwd())
  const drift = findCurrentDocDrift(root)
  if (drift.length === 0) {
    console.log('PASS current documentation drift guard')
    return
  }
  console.error(`FAIL current documentation drift guard (${drift.length})`)
  drift.forEach((item) => console.error(`  - ${item}`))
  process.exitCode = 1
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : null
if (invokedPath === fileURLToPath(import.meta.url)) main()
