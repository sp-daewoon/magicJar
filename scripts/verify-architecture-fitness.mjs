#!/usr/bin/env node

import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const EXPECTED_MODULES = Object.freeze([
  'domain',
  'pattern-engine',
  'indicator-impl',
  'strategy-engine',
  'persistence-shared',
  'market-trend-engine',
  'api',
  'batch',
  'consumer',
])

const EXECUTABLE_MODULES = new Set(['api', 'batch', 'consumer'])
const LIBRARY_MODULES = new Set(EXPECTED_MODULES.filter((module) => !EXECUTABLE_MODULES.has(module)))
const FORBIDDEN_DOMAIN_IMPORT = /^import (?:org\.springframework|jakarta\.persistence|javax\.persistence)(?:\.|$)/
const KAFKA_LITERAL = /["'](kafka\.[a-z0-9.-]+\.v\d+(?:\.DLT)?)["']/g
const KAFKA_TOKEN = /\b(kafka\.[a-z0-9.-]+\.v\d+(?:\.DLT)?)\b/g
const PROJECT_DEPENDENCY = /project\(\s*(?:path\s*=\s*)?["']:(?<target>[a-z0-9-]+)["']\s*\)/g

function normalize(root, path) {
  return relative(root, path).split('\\').join('/')
}

function walk(directory, predicate = () => true) {
  if (!existsSync(directory)) return []
  const files = []
  for (const entry of readdirSync(directory)) {
    const path = resolve(directory, entry)
    const stats = statSync(path)
    if (stats.isDirectory()) {
      if (!['.git', 'build', 'dist', 'node_modules'].includes(entry)) files.push(...walk(path, predicate))
    } else if (predicate(path)) {
      files.push(path)
    }
  }
  return files.sort()
}

function stripCodeComments(content) {
  let result = ''
  let quote = null
  let lineComment = false
  let blockCommentDepth = 0
  for (let index = 0; index < content.length; index += 1) {
    const character = content[index]
    const next = content[index + 1]
    const triple = content.slice(index, index + 3)
    if (lineComment) {
      if (character === '\n') {
        lineComment = false
        result += '\n'
      } else result += ' '
      continue
    }
    if (blockCommentDepth > 0) {
      if (character === '/' && next === '*') {
        result += '  '
        index += 1
        blockCommentDepth += 1
      } else if (character === '*' && next === '/') {
        result += '  '
        index += 1
        blockCommentDepth -= 1
      } else result += character === '\n' ? '\n' : ' '
      continue
    }
    if (quote) {
      result += character
      if (quote === '"""' && triple === '"""') {
        result += '""'
        index += 2
        quote = null
      } else if (quote !== '"""' && character === quote && content[index - 1] !== '\\') quote = null
      continue
    }
    if (character === '/' && next === '/') {
      result += '  '
      index += 1
      lineComment = true
    } else if (character === '/' && next === '*') {
      result += '  '
      index += 1
      blockCommentDepth = 1
    } else if (triple === '"""') {
      result += '"""'
      index += 2
      quote = '"""'
    } else {
      result += character
      if (character === '"' || character === "'") quote = character
    }
  }
  return result
}

function matchingParenthesis(content, openIndex) {
  let depth = 0
  let quote = null
  for (let index = openIndex; index < content.length; index += 1) {
    const character = content[index]
    const previous = content[index - 1]
    if (quote) {
      if (character === quote && previous !== '\\') quote = null
      continue
    }
    if (character === '"' || character === "'") {
      quote = character
      continue
    }
    if (character === '(') depth += 1
    if (character === ')') {
      depth -= 1
      if (depth === 0) return index
    }
  }
  return -1
}

export function readIncludedModules(root) {
  const settings = stripCodeComments(readFileSync(resolve(root, 'settings.gradle.kts'), 'utf8'))
  const includeStart = settings.indexOf('include(')
  const openParenthesis = includeStart + 'include'.length
  const includeEnd = includeStart >= 0 ? matchingParenthesis(settings, openParenthesis) : -1
  const include = includeStart >= 0 && includeEnd > includeStart
    ? settings.slice(includeStart + 'include('.length, includeEnd)
    : ''
  return [...include.matchAll(/["']([a-z0-9-]+)["']/g)].map((match) => match[1])
}

export function findDomainFrameworkImports(root) {
  const sourceRoot = resolve(root, 'domain/src/main')
  const violations = []
  for (const file of walk(sourceRoot, (path) => path.endsWith('.kt'))) {
    const lines = readFileSync(file, 'utf8').split(/\r?\n/)
    lines.forEach((line, index) => {
      if (FORBIDDEN_DOMAIN_IMPORT.test(line.trim())) {
        violations.push(`${normalize(root, file)}:${index + 1}: ${line.trim()}`)
      }
    })
  }
  return violations
}

export function findExecutableModuleDependencies(root) {
  const violations = []
  for (const owner of EXECUTABLE_MODULES) {
    const buildFile = resolve(root, owner, 'build.gradle.kts')
    if (!existsSync(buildFile)) continue
    const content = stripCodeComments(readFileSync(buildFile, 'utf8'))
    const lines = content.split(/\r?\n/)
    lines.forEach((line, index) => {
      for (const match of line.matchAll(PROJECT_DEPENDENCY)) {
        if (EXECUTABLE_MODULES.has(match.groups.target) && match.groups.target !== owner) {
          violations.push(`${normalize(root, buildFile)}:${index + 1}: ${owner} -> ${match.groups.target}`)
        }
      }
    })
  }
  return violations.sort()
}

export function readProjectDependencies(root) {
  const graph = new Map(EXPECTED_MODULES.map((module) => [module, new Set()]))
  for (const owner of EXPECTED_MODULES) {
    const buildFile = resolve(root, owner, 'build.gradle.kts')
    if (!existsSync(buildFile)) continue
    const content = stripCodeComments(readFileSync(buildFile, 'utf8'))
    for (const match of content.matchAll(PROJECT_DEPENDENCY)) {
      graph.get(owner).add(match.groups.target)
    }
  }
  return graph
}

export function findModuleBoundaryViolations(root) {
  const graph = readProjectDependencies(root)
  const violations = []
  for (const [owner, dependencies] of graph) {
    for (const dependency of dependencies) {
      if (!graph.has(dependency)) violations.push(`${owner} -> unknown module ${dependency}`)
      if (owner === 'domain') violations.push(`domain -> ${dependency}`)
      if (LIBRARY_MODULES.has(owner) && owner !== 'domain' && dependency !== 'domain') {
        violations.push(`${owner} -> ${dependency} (library may depend only on domain)`)
      }
      if (EXECUTABLE_MODULES.has(owner) && EXECUTABLE_MODULES.has(dependency)) {
        violations.push(`${owner} -> ${dependency} (executable-to-executable)`)
      }
    }
  }

  const visiting = new Set()
  const visited = new Set()
  const visit = (module, path = []) => {
    if (visiting.has(module)) {
      violations.push(`dependency cycle: ${[...path, module].join(' -> ')}`)
      return
    }
    if (visited.has(module)) return
    visiting.add(module)
    for (const dependency of graph.get(module) ?? []) {
      if (graph.has(dependency)) visit(dependency, [...path, module])
    }
    visiting.delete(module)
    visited.add(module)
  }
  for (const module of EXPECTED_MODULES) visit(module)

  for (const module of LIBRARY_MODULES) {
    const buildFile = resolve(root, module, 'build.gradle.kts')
    if (!existsSync(buildFile)) continue
    const content = stripCodeComments(readFileSync(buildFile, 'utf8'))
    if (/id\(\s*["']org\.springframework\.boot["']\s*\)/.test(content)) {
      violations.push(`${module}: Spring Boot plugin/main is reserved for api, batch, consumer`)
    }
  }
  return [...new Set(violations)].sort()
}

export function findRawKafkaTopicLiterals(root) {
  const violations = []
  const registry = 'domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/messaging/KafkaTopics.kt'
  const registryPath = resolve(root, registry)
  const registeredTopics = existsSync(registryPath)
    ? new Set([...readFileSync(registryPath, 'utf8').matchAll(KAFKA_LITERAL)].map((match) => match[1]))
    : new Set()
  for (const module of EXPECTED_MODULES) {
    const sourceRoot = resolve(root, module, 'src/main')
    for (const file of walk(sourceRoot, (path) => /\.(?:kt|ya?ml|properties)$/.test(path))) {
      const normalized = normalize(root, file)
      if (normalized === registry) continue
      const isKotlin = file.endsWith('.kt')
      const content = readFileSync(file, 'utf8')
      const lines = (isKotlin ? stripCodeComments(content) : content).split(/\r?\n/)
      lines.forEach((line, index) => {
        const matcher = isKotlin ? KAFKA_LITERAL : KAFKA_TOKEN
        for (const match of line.matchAll(matcher)) {
          if (isKotlin) {
            violations.push(`${normalized}:${index + 1}: ${match[1]}`)
          } else if (!registeredTopics.has(match[1])) {
            violations.push(`${normalized}:${index + 1}: unregistered ${match[1]}`)
          }
        }
      })
    }
  }
  return violations.sort()
}

export function findCompletedActiveTasks(root) {
  const taskRoot = resolve(root, '_workspace/tasks')
  if (!existsSync(taskRoot)) return []
  const violations = []
  for (const entry of readdirSync(taskRoot).sort()) {
    const file = resolve(taskRoot, entry)
    if (!entry.endsWith('.md') || !statSync(file).isFile()) continue
    const content = readFileSync(file, 'utf8')
    if (/^status:\s*DONE\s*$/m.test(content)) violations.push(normalize(root, file))
  }
  return violations
}

export function verify(root) {
  const actualModules = readIncludedModules(root)
  const expected = [...EXPECTED_MODULES].sort()
  const actual = [...actualModules].sort()
  return [
    {
      name: 'nine-module inventory',
      violations: JSON.stringify(actual) === JSON.stringify(expected) ? [] : [`expected=${expected.join(',')} actual=${actual.join(',')}`],
    },
    { name: 'domain framework isolation', violations: findDomainFrameworkImports(root) },
    { name: 'module dependency direction and cycle', violations: findModuleBoundaryViolations(root) },
    { name: 'Kafka topic registry ownership and config consistency', violations: findRawKafkaTopicLiterals(root) },
  ]
}

function main() {
  const root = resolve(process.argv[2] ?? process.cwd())
  const checks = verify(root)
  let failed = false
  for (const check of checks) {
    if (check.violations.length === 0) {
      console.log(`PASS ${check.name}`)
      continue
    }
    failed = true
    console.error(`FAIL ${check.name} (${check.violations.length})`)
    check.violations.forEach((violation) => console.error(`  - ${violation}`))
  }
  const completedActiveTasks = findCompletedActiveTasks(root)
  if (completedActiveTasks.length > 0) {
    console.warn(`WARN active task hygiene: ${completedActiveTasks.length} DONE task(s) remain in _workspace/tasks`)
    console.warn('  Run the read-only task hygiene report; do not archive or delete without explicit approval.')
  }
  if (failed) process.exitCode = 1
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : null
if (invokedPath === fileURLToPath(import.meta.url)) main()
