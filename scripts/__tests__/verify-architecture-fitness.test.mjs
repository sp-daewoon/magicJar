import assert from 'node:assert/strict'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'

import { inspectTaskHygiene, parseCliArgs, parseTask } from '../check-task-hygiene.mjs'
import {
  EXPECTED_MODULES,
  findCompletedActiveTasks,
  findDomainFrameworkImports,
  findExecutableModuleDependencies,
  findModuleBoundaryViolations,
  findRawKafkaTopicLiterals,
  readIncludedModules,
} from '../verify-architecture-fitness.mjs'

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'magicjar-fitness-'))
  const write = (relative, content) => {
    const target = join(root, relative)
    mkdirSync(join(target, '..'), { recursive: true })
    writeFileSync(target, content, 'utf8')
  }
  return { root, write, cleanup: () => rmSync(root, { recursive: true, force: true }) }
}

test('settings module inventory is parsed and compared with the locked nine-module boundary', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write('settings.gradle.kts', `include(${EXPECTED_MODULES.map((name) => `"${name}"`).join(', ')})`)

  assert.deepEqual(readIncludedModules(f.root).sort(), [...EXPECTED_MODULES].sort())
})

test('settings parser stops at the matching include parenthesis', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write('settings.gradle.kts', 'include("domain", "api")\nprintln("not-a-module")\n')

  assert.deepEqual(readIncludedModules(f.root), ['domain', 'api'])
})

test('settings parser ignores include examples inside comments', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write(
    'settings.gradle.kts',
    '// old: include("domain", "api")\n/* include("batch") */\ninclude("domain", "consumer", "rogue")\n',
  )

  assert.deepEqual(readIncludedModules(f.root), ['domain', 'consumer', 'rogue'])
})

test('settings parser ignores include examples inside nested block comments', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write(
    'settings.gradle.kts',
    '/* outer /* inner */ include("fake") */\ninclude("domain", "consumer")\n',
  )

  assert.deepEqual(readIncludedModules(f.root), ['domain', 'consumer'])
})

test('domain framework imports are reported with their source path', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write('domain/src/main/kotlin/example/Pure.kt', 'import org.springframework.stereotype.Component\n')

  assert.deepEqual(findDomainFrameworkImports(f.root), [
    'domain/src/main/kotlin/example/Pure.kt:1: import org.springframework.stereotype.Component',
  ])
})

test('executable modules cannot directly depend on another executable module', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write('api/build.gradle.kts', 'dependencies { implementation(project(":consumer")) }\n')

  assert.deepEqual(findExecutableModuleDependencies(f.root), [
    'api/build.gradle.kts:1: api -> consumer',
  ])
})

test('named Gradle project path dependencies are also detected', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write('api/build.gradle.kts', 'dependencies { implementation(project(path = ":consumer")) }\n')

  assert.deepEqual(findExecutableModuleDependencies(f.root), [
    'api/build.gradle.kts:1: api -> consumer',
  ])
})

test('module graph ignores comments and rejects reverse library dependencies and cycles', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write('indicator-impl/build.gradle.kts', '// implementation(project(":api"))\nimplementation(project(":domain"))\n')
  f.write('domain/build.gradle.kts', 'implementation(project(":indicator-impl"))\n')

  const violations = findModuleBoundaryViolations(f.root)
  assert.ok(violations.some((violation) => violation === 'domain -> indicator-impl'))
  assert.ok(violations.some((violation) => violation.startsWith('dependency cycle:')))
  assert.ok(!violations.some((violation) => violation.includes('indicator-impl -> api')))
})

test('Kafka wire literals outside the central registry are reported', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write(
    'domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/messaging/KafkaTopics.kt',
    'const val OK = "kafka.consumer.evt.ok.v1"\n',
  )
  f.write('consumer/src/main/kotlin/example/Listener.kt', 'val topic = "kafka.consumer.evt.bad.v1"\n')

  assert.deepEqual(findRawKafkaTopicLiterals(f.root), [
    'consumer/src/main/kotlin/example/Listener.kt:1: kafka.consumer.evt.bad.v1',
  ])
})

test('Kafka topic examples in Kotlin comments are not runtime literal violations', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write(
    'domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/messaging/KafkaTopics.kt',
    'const val OK = "kafka.consumer.evt.ok.v1"\n',
  )
  f.write(
    'consumer/src/main/kotlin/example/Docs.kt',
    '/** Example: "kafka.consumer.evt.docs.v1" */\n// "kafka.consumer.evt.line-comment.v1"\nval topic = KafkaTopics.OK\n',
  )

  assert.deepEqual(findRawKafkaTopicLiterals(f.root), [])
})

test('Kafka topic examples inside nested Kotlin block comments are ignored', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write(
    'domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/messaging/KafkaTopics.kt',
    'const val OK = "kafka.consumer.evt.ok.v1"\n',
  )
  f.write(
    'consumer/src/main/kotlin/example/NestedDocs.kt',
    '/* outer /* inner */ "kafka.consumer.evt.nested.v1" */\nval topic = KafkaTopics.OK\n',
  )

  assert.deepEqual(findRawKafkaTopicLiterals(f.root), [])
})

test('resource Kafka topics must exist in the central registry', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write(
    'domain/src/main/kotlin/kr/spdaewoon/magicjar/domain/messaging/KafkaTopics.kt',
    'const val OK = "kafka.consumer.evt.ok.v1"\n',
  )
  f.write(
    'api/src/main/resources/application.yml',
    'known: kafka.consumer.evt.ok.v1\nunknown: kafka.consumer.evt.unknown.v1\n',
  )

  assert.deepEqual(findRawKafkaTopicLiterals(f.root), [
    'api/src/main/resources/application.yml:2: unregistered kafka.consumer.evt.unknown.v1',
  ])
})

test('DONE tasks left in the active task directory are reported', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write('_workspace/tasks/T-done.md', '---\nstatus: DONE\n---\n')
  f.write('_workspace/tasks/T-running.md', '---\nstatus: RUNNING\n---\n')
  f.write('_workspace/tasks/done/T-archived.md', '---\nstatus: DONE\n---\n')

  assert.deepEqual(findCompletedActiveTasks(f.root), ['_workspace/tasks/T-done.md'])
})

test('task hygiene reports DONE files without mutating or failing legacy cleanup debt', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  f.write(
    '_workspace/tasks/T-done.md',
    '---\nid: T-done\nagent: qa\nbranch: agent/qa/done\nstatus: DONE\nstartedAt: 2026-07-01T00:00:00+09:00\n---\n',
  )

  const report = inspectTaskHygiene(f.root)
  assert.equal(report.counts.DONE, 1)
  assert.deepEqual(report.errors, [])
  assert.match(report.warnings.join('\n'), /explicit approval/)
})

test('task hygiene rejects malformed metadata and duplicate active branches', (t) => {
  const f = fixture()
  t.after(f.cleanup)
  const active = (id) =>
    `---\nid: ${id}\nagent: qa\nbranch: agent/qa/shared\nstatus: RUNNING\nstartedAt: 2026-07-01T00:00:00+09:00\n---\n`
  f.write('_workspace/tasks/T-one.md', active('T-one'))
  f.write('_workspace/tasks/T-two.md', active('T-two'))
  f.write('_workspace/tasks/T-broken.md', 'no frontmatter\n')

  assert.deepEqual(parseTask(join(f.root, '_workspace/tasks/T-broken.md')).errors, ['missing frontmatter'])
  const report = inspectTaskHygiene(f.root)
  assert.ok(report.errors.some((error) => error.includes('duplicate active branch=agent/qa/shared')))
  assert.ok(report.errors.some((error) => error.includes('missing frontmatter')))
})

test('task hygiene CLI parses --details separately from the optional root', () => {
  assert.deepEqual(parseCliArgs(['--details'], '/repo'), { root: '/repo', details: true })
  assert.deepEqual(parseCliArgs(['/other', '--details'], '/repo'), { root: '/other', details: true })
})
