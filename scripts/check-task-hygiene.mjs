#!/usr/bin/env node

import { execFileSync } from 'node:child_process'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { basename, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const REQUIRED_FIELDS = ['id', 'agent', 'branch', 'status', 'startedAt']
const ACTIVE_STATUSES = new Set(['RUNNING', 'BLOCKED'])
const VALID_STATUSES = new Set([...ACTIVE_STATUSES, 'DONE'])

function git(root, args) {
  try {
    return execFileSync('git', args, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim()
  } catch {
    return ''
  }
}

export function parseTask(path) {
  const content = readFileSync(path, 'utf8')
  const frontmatter = content.match(/^---\n([\s\S]*?)\n---(?:\n|$)/)?.[1]
  if (!frontmatter) return { path, meta: {}, errors: ['missing frontmatter'] }
  const meta = {}
  for (const line of frontmatter.split(/\r?\n/)) {
    const match = line.match(/^([A-Za-z][A-Za-z0-9]*):\s*(.*)$/)
    if (!match) continue
    const value = match[2].trim().replace(/^['"]|['"]$/g, '')
    meta[match[1]] = value === 'null' ? null : value
  }
  const errors = []
  for (const field of REQUIRED_FIELDS) {
    if (meta[field] === undefined || meta[field] === null || meta[field] === '') errors.push(`missing ${field}`)
  }
  if (meta.status && !VALID_STATUSES.has(meta.status)) errors.push(`invalid status=${meta.status}`)
  if (meta.id && basename(path, '.md') !== meta.id) errors.push(`filename/id mismatch (${basename(path, '.md')} != ${meta.id})`)
  return { path, meta, errors }
}

export function inspectTaskHygiene(root) {
  const taskRoot = resolve(root, '_workspace/tasks')
  const files = existsSync(taskRoot)
    ? readdirSync(taskRoot)
        .filter((name) => name.endsWith('.md') && name !== 'README.md')
        .map((name) => join(taskRoot, name))
        .filter((path) => statSync(path).isFile())
        .sort()
    : []
  const tasks = files.map(parseTask)
  const errors = tasks.flatMap((task) => task.errors.map((error) => `${basename(task.path)}: ${error}`))
  const warnings = []
  const counts = { RUNNING: 0, BLOCKED: 0, DONE: 0, UNKNOWN: 0 }
  const ids = new Map()
  const activeBranches = new Map()

  for (const task of tasks) {
    const status = task.meta.status
    if (counts[status] === undefined) counts.UNKNOWN += 1
    else counts[status] += 1
    if (task.meta.id) {
      if (ids.has(task.meta.id)) errors.push(`duplicate id=${task.meta.id}`)
      ids.set(task.meta.id, task.path)
    }
    if (ACTIVE_STATUSES.has(status) && task.meta.branch) {
      const siblings = activeBranches.get(task.meta.branch) ?? []
      siblings.push(task.meta.id ?? basename(task.path, '.md'))
      activeBranches.set(task.meta.branch, siblings)
    }
  }

  for (const [branch, idsForBranch] of activeBranches) {
    if (idsForBranch.length > 1) errors.push(`duplicate active branch=${branch}: ${idsForBranch.join(', ')}`)
  }

  const currentBranch = git(root, ['branch', '--show-current'])
  const currentTasks = activeBranches.get(currentBranch) ?? []
  if (currentTasks.length === 0 && currentBranch && currentBranch !== 'main') {
    warnings.push(`current branch has no active task: ${currentBranch}`)
  }

  if (counts.DONE > 0) warnings.push(`${counts.DONE} DONE task(s) remain in active root; archive requires explicit approval`)

  const existingBranches = new Set(git(root, ['branch', '--format=%(refname:short)']).split('\n').filter(Boolean))
  const worktreeBranches = new Set(
    git(root, ['worktree', 'list', '--porcelain'])
      .split('\n')
      .filter((line) => line.startsWith('branch refs/heads/'))
      .map((line) => line.slice('branch refs/heads/'.length)),
  )
  const orphanCandidates = [...activeBranches.keys()].filter(
    (branch) => !existingBranches.has(branch) && !worktreeBranches.has(branch),
  )
  if (orphanCandidates.length > 0) {
    warnings.push(`${orphanCandidates.length} active task branch(es) are absent locally; inspect manually, never auto-complete`)
  }

  return { tasks, counts, errors: [...new Set(errors)].sort(), warnings, currentBranch, orphanCandidates }
}

export function parseCliArgs(argv, cwd) {
  const details = argv.includes('--details')
  const rootArgument = argv.find((argument) => argument !== '--details')
  return { root: resolve(rootArgument ?? cwd), details }
}

function main() {
  const options = parseCliArgs(process.argv.slice(2), process.cwd())
  const report = inspectTaskHygiene(options.root)
  console.log(
    `TASK_HYGIENE total=${report.tasks.length} RUNNING=${report.counts.RUNNING} BLOCKED=${report.counts.BLOCKED} DONE=${report.counts.DONE}`,
  )
  report.warnings.forEach((warning) => console.warn(`WARN ${warning}`))
  report.errors.forEach((error) => console.error(`FAIL ${error}`))
  if (options.details) {
    report.orphanCandidates.forEach((branch) => console.warn(`  orphan-candidate ${branch}`))
  }
  if (report.errors.length > 0) process.exitCode = 1
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : null
if (invokedPath === fileURLToPath(import.meta.url)) main()
