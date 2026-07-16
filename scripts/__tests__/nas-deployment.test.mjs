import assert from 'node:assert/strict'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import test from 'node:test'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

function run(command, args, options = {}) {
  return spawnSync(command, args, {
    cwd: root,
    encoding: 'utf8',
    ...options,
  })
}

test('docker target helper injects the explicit context without changing global state', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-docker-target-'))
  try {
    const log = join(fixture, 'docker.log')
    const fakeDocker = join(fixture, 'docker')
    writeFileSync(fakeDocker, `#!/bin/sh\nprintf '%s\\n' "$*" >> "${log}"\n`)
    chmodSync(fakeDocker, 0o755)

    const result = run('bash', ['-c', 'source scripts/lib/docker-target.sh; mj_docker ps'], {
      env: {
        ...process.env,
        PATH: `${fixture}:${process.env.PATH}`,
        MAGICJAR_DOCKER_CONTEXT: 'nas',
      },
    })
    assert.equal(result.status, 0, result.stderr)
    assert.equal(readFileSync(log, 'utf8').trim(), '--context nas ps')
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('docker target helper rejects a context whose endpoint does not match attestation', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-docker-endpoint-'))
  try {
    const fakeDocker = join(fixture, 'docker')
    writeFileSync(fakeDocker, '#!/bin/sh\nprintf "%s\\n" "ssh://wrong-host"\n')
    chmodSync(fakeDocker, 0o755)
    const result = run(
      'bash',
      ['-c', 'source scripts/lib/docker-target.sh; mj_assert_expected_endpoint'],
      {
        env: {
          ...process.env,
          PATH: `${fixture}:/usr/bin:/bin`,
          MAGICJAR_DOCKER_CONTEXT: 'nas',
        },
      },
    )
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /endpoint mismatch/)
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('remote smoke stops before mutable checks when endpoint attestation fails', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-smoke-endpoint-'))
  try {
    const log = join(fixture, 'calls.log')
    const fakeDocker = join(fixture, 'docker')
    writeFileSync(
      fakeDocker,
      `#!/bin/sh\nprintf 'docker:%s\\n' "$*" >> "${log}"\ncase "$FAKE_ENDPOINT_CASE" in mismatch) printf '%s\\n' 'ssh://wrong-host';; inspect-failure) exit 42;; empty) :;; esac\nexit 0\n`,
    )
    chmodSync(fakeDocker, 0o755)

    for (const command of ['curl', 'jq']) {
      const fakeCommand = join(fixture, command)
      writeFileSync(
        fakeCommand,
        `#!/bin/sh\nprintf '${command}:%s\\n' "$*" >> "${log}"\nexit 97\n`,
      )
      chmodSync(fakeCommand, 0o755)
    }

    for (const endpointCase of ['mismatch', 'inspect-failure', 'empty']) {
      writeFileSync(log, '')
      const result = run('bash', ['scripts/smoke.sh'], {
        env: {
          ...process.env,
          PATH: `${fixture}:/usr/bin:/bin`,
          MAGICJAR_DOCKER_CONTEXT: 'nas',
          SKIP_FRESHNESS_CHECK: '1',
          FAKE_ENDPOINT_CASE: endpointCase,
        },
      })

      assert.notEqual(result.status, 0, endpointCase)
      assert.match(result.stderr, /endpoint attestation 실패/, endpointCase)
      const calls = readFileSync(log, 'utf8').trim().split('\n')
      assert.equal(calls.length, 1, `${endpointCase}: ${calls.join(', ')}`)
      assert.match(calls[0], /^docker:context inspect nas --format /, endpointCase)
    }
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('release provenance requires an approved SHA and rejects untracked input', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-release-provenance-'))
  try {
    for (const args of [
      ['init', '-q'],
      ['config', 'user.name', 'test'],
      ['config', 'user.email', 'test@magicjar.local'],
    ]) {
      assert.equal(run('git', args, { cwd: fixture }).status, 0)
    }
    writeFileSync(join(fixture, 'tracked.txt'), 'baseline\n')
    writeFileSync(join(fixture, '.gitignore'), '*.ignored.kt\n')
    assert.equal(run('git', ['add', 'tracked.txt', '.gitignore'], { cwd: fixture }).status, 0)
    assert.equal(run('git', ['commit', '-qm', 'baseline'], { cwd: fixture }).status, 0)
    const sha = run('git', ['rev-parse', 'HEAD'], { cwd: fixture }).stdout.trim()
    const command = `source '${root}/scripts/lib/nas-deployment-safety.sh'; mj_require_release_provenance '${fixture}'`
    const baseEnv = { ...process.env }
    delete baseEnv.MAGICJAR_EXPECTED_GIT_SHA

    const missingApproval = run('bash', ['-c', command], { cwd: fixture, env: baseEnv })
    assert.notEqual(missingApproval.status, 0)
    assert.match(missingApproval.stderr, /EXPECTED_GIT_SHA/)

    const clean = run('bash', ['-c', command], {
      cwd: fixture,
      env: { ...baseEnv, MAGICJAR_EXPECTED_GIT_SHA: sha },
    })
    assert.equal(clean.status, 0, clean.stderr)

    writeFileSync(join(fixture, 'untracked.kt'), 'class Untracked\n')
    const dirty = run('bash', ['-c', command], {
      cwd: fixture,
      env: { ...baseEnv, MAGICJAR_EXPECTED_GIT_SHA: sha },
    })
    assert.notEqual(dirty.status, 0)
    assert.match(dirty.stderr, /untracked/)

    rmSync(join(fixture, 'untracked.kt'))
    mkdirSync(join(fixture, 'src'))
    writeFileSync(join(fixture, 'src/hidden.ignored.kt'), 'class Hidden\n')
    const ignored = run('bash', ['-c', command], {
      cwd: fixture,
      env: { ...baseEnv, MAGICJAR_EXPECTED_GIT_SHA: sha },
    })
    assert.notEqual(ignored.status, 0)
    assert.match(ignored.stderr, /ignored source/)
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('consumer and writer exclusivity guards fail closed', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-consumer-guard-'))
  try {
    const fakeDocker = join(fixture, 'docker')
    writeFileSync(
      fakeDocker,
      '#!/bin/sh\ncase "$*" in *" ps "*|*" ps") [ "${FAIL_PS:-0}" = 1 ] && exit 42; printf "%s\\n" magicjar-consumer;; esac\nexit 0\n',
    )
    chmodSync(fakeDocker, 0o755)
    const options = { cwd: root, env: { ...process.env, PATH: `${fixture}:/usr/bin:/bin` } }
    const consumer = run(
      'bash',
      ['-c', 'source scripts/lib/nas-deployment-safety.sh; mj_assert_consumer_stopped desktop-linux nas'],
      options,
    )
    assert.notEqual(consumer.status, 0)
    assert.match(consumer.stderr, /split-brain/)

    const psFailure = run(
      'bash',
      ['-c', 'source scripts/lib/nas-deployment-safety.sh; mj_assert_consumer_stopped desktop-linux nas'],
      { ...options, env: { ...options.env, FAIL_PS: '1' } },
    )
    assert.notEqual(psFailure.status, 0)
    assert.match(psFailure.stderr, /상태 조회 실패/)

    const writers = run(
      'bash',
      ['-c', 'source scripts/lib/nas-deployment-safety.sh; mj_assert_writers_stopped nas'],
      options,
    )
    assert.notEqual(writers.status, 0)
    assert.match(writers.stderr, /writer/)
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('NAS entrypoints expose help without contacting Docker', () => {
  for (const script of [
    'scripts/deploy-nas.sh',
    'scripts/migrate-docker-to-nas.sh',
    'scripts/deploy-nas-observability.sh',
    'scripts/install-nas-backup-entrypoint.sh',
  ]) {
    const result = run('bash', [script, '--help'], {
      env: { ...process.env, PATH: '/usr/bin:/bin' },
    })
    assert.equal(result.status, 0, `${script}: ${result.stderr}`)
    assert.match(result.stdout, /Usage:/)
  }
})

test('NAS observability renderer is remote-safe, digest-pinned, and loopback-only', (t) => {
  const docker = run('docker', ['compose', 'version'])
  if (docker.status !== 0) {
    t.skip('docker compose CLI unavailable')
    return
  }

  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-nas-observability-'))
  try {
    const envFile = join(fixture, '.env')
    const composeFile = join(fixture, 'observability.yml')
    writeFileSync(
      envFile,
      'GRAFANA_ADMIN_USER=admin\nGRAFANA_ADMIN_PASSWORD=test-only-not-default\nNAS_BIND_ADDRESS=127.0.0.1\nMAGICJAR_OBSERVABILITY_REVISION=0123456789abcdef0123456789abcdef01234567\n',
    )
    const render = run('python3', [
      'scripts/render-nas-observability-compose.py',
      '--output',
      composeFile,
    ])
    assert.equal(render.status, 0, render.stderr)

    const result = run('docker', [
      'compose',
      '--env-file',
      envFile,
      '-f',
      composeFile,
      'config',
      '--format',
      'json',
    ])
    assert.equal(result.status, 0, result.stderr)
    assert.doesNotMatch(result.stderr, /variable is not set/)
    const model = JSON.parse(result.stdout)
    assert.deepEqual(Object.keys(model.services).sort(), ['alertmanager', 'grafana', 'prometheus'])
    assert.equal(Object.keys(model.configs).length, 9)
    assert.equal(model.networks.magicjar_core.external, true)
    assert.equal(model.networks.magicjar_core.name, 'magicjar_default')

    for (const [name, service] of Object.entries(model.services)) {
      assert.match(service.image, /@sha256:[a-f0-9]{64}$/, `${name} image digest`)
      assert.equal(service.pull_policy, 'never', `${name} pull policy`)
      assert.equal(service.restart, 'unless-stopped', `${name} restart policy`)
      assert.equal(service.stop_grace_period, '30s', `${name} stop grace`)
      assert.equal(
        service.labels['org.opencontainers.image.revision'],
        '0123456789abcdef0123456789abcdef01234567',
      )
      for (const port of service.ports ?? []) {
        assert.equal(port.host_ip, '127.0.0.1', `${name} host bind`)
      }
      for (const volume of service.volumes ?? []) {
        assert.equal(volume.type, 'volume', `${name} must not use daemon-host bind mounts`)
      }
    }
    assert.equal(model.services.grafana.environment.GF_AUTH_ANONYMOUS_ENABLED, 'false')
    assert.equal(model.services.grafana.environment.GF_USERS_ALLOW_SIGN_UP, 'false')
    assert.match(model.configs.prometheus_rules.content, /\$\$labels\.application/)
    assert.match(model.configs.prometheus_config.content, /cluster: magicjar-nas/)
    assert.match(model.configs.prometheus_config.content, /environment: nas/)
    assert.doesNotMatch(model.configs.prometheus_config.content, /host\.docker\.internal|bootrun/)
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('NAS backup scheduler entrypoint is atomic and never bypasses DSM scheduler', () => {
  const backup = readFileSync(join(root, 'scripts/backup/nas-task-scheduler-backup.sh'), 'utf8')
  const installer = readFileSync(join(root, 'scripts/install-nas-backup-entrypoint.sh'), 'utf8')

  assert.match(backup, /umask 077/)
  assert.match(backup, /\$\{PG_FINAL\}\.partial/)
  assert.match(backup, /\$\{REDIS_FINAL\}\.partial/)
  assert.match(backup, /mkdir "\$LOCK_DIR"/)
  assert.match(backup, /pg_restore --list/)
  assert.match(backup, /redis-cli --raw BGSAVE/)
  assert.match(backup, /com\.docker\.compose\.project/)
  assert.match(backup, /com\.docker\.compose\.service/)
  assert.match(backup, /sha256sum|shasum -a 256/)
  assert.match(backup, /mv "\$PG_PARTIAL" "\$PG_FINAL"/)
  assert.match(backup, /mv "\$REDIS_PARTIAL" "\$REDIS_FINAL"/)
  assert.match(backup, /\/volume1\/docker\/magicjar\/backups/)
  assert.doesNotMatch(backup, /synoschedtask|crontab/)

  assert.match(installer, /mj_require_release_provenance/)
  assert.match(installer, /mj_require_remote_docker/)
  assert.match(installer, /--pull never/)
  assert.match(installer, /\/volume1\/docker\/magicjar\/ops/)
  assert.doesNotMatch(installer, /^\s*(?:sudo\s+)?(?:synoschedtask|crontab)\b/m)
})

test('NAS backup scheduler publishes PostgreSQL and Redis only after validated container identity', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-nas-backup-runtime-'))
  try {
    const fakeDocker = join(fixture, 'docker')
    writeFileSync(
      fakeDocker,
      `#!/bin/sh
set -eu
cmd="$1"; shift
case "$cmd" in
  inspect)
    container="$1"; shift
    [ "$container" = magicjar-postgres ] || [ "$container" = magicjar-redis ] || exit 2
    if [ "\${1:-}" = --format ]; then
      case "$2" in
        *State.Running*) printf '%s\\n' true ;;
        *compose.project*) printf '%s\\n' "\${FAKE_PROJECT:-magicjar}" ;;
        *compose.service*) [ "$container" = magicjar-postgres ] && printf '%s\\n' postgres || printf '%s\\n' redis ;;
      esac
    fi
    ;;
  exec)
    [ "\${1:-}" = -i ] && shift
    container="$1"; shift
    case "$*" in
      *pg_isready*) exit 0 ;;
      *pg_dump*) printf '%s\\n' FAKE_CUSTOM_FORMAT_DUMP ;;
      *pg_restore*--list*) cat >/dev/null ;;
      *redis-cli*PING*) printf '%s\\n' PONG ;;
      *redis-cli*LASTSAVE*) printf '%s\\n' 100 ;;
      *redis-cli*BGSAVE*) printf '%s\\n' 'Background saving started' ;;
      *redis-cli*INFO*persistence*) printf 'rdb_bgsave_in_progress:0\\r\\nrdb_last_bgsave_status:ok\\r\\n' ;;
      *) exit 3 ;;
    esac
    ;;
  cp)
    printf '%s\\n' FAKE_RDB > "$2"
    ;;
  *) exit 4 ;;
esac
`,
    )
    chmodSync(fakeDocker, 0o755)

    const backupRoot = join(fixture, 'backups')
    const result = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...process.env,
        MAGICJAR_DOCKER_BIN: fakeDocker,
        MAGICJAR_NAS_BACKUP_ROOT: backupRoot,
        MAGICJAR_NAS_BACKUP_MIN_FREE_KB: '1',
      },
    })
    assert.equal(result.status, 0, result.stderr)
    assert.match(result.stdout, /BACKUP_PASS/)
    const pgFiles = readdirSync(join(backupRoot, 'postgres/daily'))
    const redisFiles = readdirSync(join(backupRoot, 'redis/snapshots'))
    assert.equal(pgFiles.filter((name) => name.endsWith('.dump')).length, 1)
    assert.equal(pgFiles.filter((name) => name.endsWith('.sha256')).length, 1)
    assert.equal(pgFiles.filter((name) => name.endsWith('.meta')).length, 1)
    assert.equal(redisFiles.filter((name) => name.endsWith('.rdb')).length, 1)
    assert.equal(redisFiles.filter((name) => name.endsWith('.sha256')).length, 1)
    assert.equal(redisFiles.filter((name) => name.endsWith('.meta')).length, 1)
    assert.equal([...pgFiles, ...redisFiles].some((name) => name.endsWith('.partial')), false)

    const rejectedRoot = join(fixture, 'rejected')
    const rejected = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...process.env,
        MAGICJAR_DOCKER_BIN: fakeDocker,
        MAGICJAR_NAS_BACKUP_ROOT: rejectedRoot,
        MAGICJAR_NAS_BACKUP_MIN_FREE_KB: '1',
        FAKE_PROJECT: 'wrong-project',
      },
    })
    assert.notEqual(rejected.status, 0)
    assert.match(rejected.stderr, /unexpected Compose project/)
    assert.equal(readdirSync(join(rejectedRoot, 'postgres/daily')).length, 0)
    assert.equal(readdirSync(join(rejectedRoot, 'redis/snapshots')).length, 0)
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('cutover refuses to run without the explicit confirmation flag', () => {
  const result = run('bash', ['scripts/migrate-docker-to-nas.sh'], {
    env: { ...process.env, PATH: '/usr/bin:/bin' },
  })
  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /--confirm-cutover/)
})

test('cutover and restore scripts retain fail-closed ordering and parity gates', () => {
  const migration = readFileSync(join(root, 'scripts/migrate-docker-to-nas.sh'), 'utf8')
  const rollbackStart = migration.indexOf('restart_source_after_verified_target_stop()')
  const targetStop = migration.indexOf('tgt_compose stop api-app batch-app consumer-app frontend', rollbackStart)
  const targetProof = migration.indexOf('mj_assert_writers_stopped "$TARGET_CONTEXT"', targetStop)
  const sourceStart = migration.indexOf('src_docker start "${SOURCE_CORE[@]}"', targetProof)
  assert.ok(rollbackStart >= 0 && targetStop > rollbackStart && targetProof > targetStop && sourceStart > targetProof)
  assert.equal((migration.match(/strategy_position WHERE quantity > 0/g) ?? []).length, 2)
  assert.match(migration, /target_dump_sha=.*sha256sum/)
  assert.doesNotMatch(migration, /src_compose/)
  assert.match(migration, /SOURCE_CORE=\("\$\{SOURCE_WRITERS\[@\]\}" "\$\{SOURCE_NON_WRITERS\[@\]\}"\)/)
  assert.match(
    migration,
    /echo "▶ source writers stop: api\/batch\/consumer"\nSOURCE_APPS_STOPPED=1\nsrc_docker stop "\$\{SOURCE_WRITERS\[@\]\}"/,
  )
  assert.match(migration, /src_docker stop "\$\{SOURCE_NON_WRITERS\[@\]\}"/)
  assert.match(migration, /shasum -a 256 "\$\(basename "\$DUMP_FILE"\)"/)
  assert.match(migration, /diff -u "\$SOURCE_COUNTS" "\$TARGET_COUNTS"/)

  const restore = readFileSync(join(root, 'scripts/backup/pg-restore.sh'), 'utf8')
  assert.equal((restore.match(/--exit-on-error/g) ?? []).length, 2)
  assert.match(restore, /EXPECTED_DUMP_SHA=.*CHECKSUM_FILE/)
  assert.match(restore, /ACTUAL_DUMP_SHA=.*DUMP_FILE/)
  assert.match(restore, /SAFETY_CHECKSUM=.*SAFETY_FILE.*sha256/)
  assert.match(restore, /diff -u "\$\{COUNTS_FILE\}" "\$\{RESTORED_COUNTS\}"/)
  assert.match(restore, /WHERE success=false/)
})

test('remote drain fails closed when the target postgres is absent', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-remote-drain-'))
  try {
    const fakeDocker = join(fixture, 'docker')
    writeFileSync(
      fakeDocker,
      '#!/bin/sh\ncase "$*" in *"context inspect nas"*) printf "%s\\n" "ssh://magicjar-nas-docker";; esac\nexit 0\n',
    )
    chmodSync(fakeDocker, 0o755)
    const result = run('bash', ['scripts/check-inflight-drain.sh'], {
      env: {
        ...process.env,
        PATH: `${fixture}:/usr/bin:/bin`,
        MAGICJAR_DOCKER_CONTEXT: 'nas',
      },
    })
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /fail-closed/)
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('NAS compose publishes only frontend to the selected address and keeps internal ports closed', (t) => {
  const docker = run('docker', ['compose', 'version'])
  if (docker.status !== 0) {
    t.skip('docker compose CLI unavailable')
    return
  }

  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-nas-compose-'))
  try {
    const envFile = join(fixture, '.env')
    writeFileSync(
      envFile,
      'MAGICJAR_MODE=MOCK\nMAGICJAR_ALLOW_REAL=false\nSPRING_PROFILES_ACTIVE=dev,real-kis\n',
    )
    const result = run(
      'docker',
      [
        'compose',
        '--env-file',
        envFile,
        '-f',
        'docker/docker-compose.yml',
        '-f',
        'docker/docker-compose.dev.yml',
        '-f',
        'docker/docker-compose.nas.yml',
        'config',
        '--format',
        'json',
      ],
      {
        env: {
          ...process.env,
          MAGICJAR_ENV_FILE: envFile,
          NAS_BIND_ADDRESS: '127.0.0.1',
          MAGICJAR_GIT_SHA: '0123456789abcdef0123456789abcdef01234567',
          MAGICJAR_IMAGE_TAG: '0123456789ab',
        },
      },
    )
    assert.equal(result.status, 0, result.stderr)
    const config = JSON.parse(result.stdout)
    for (const service of ['postgres', 'kafka', 'redis', 'api-app', 'batch-app', 'consumer-app']) {
      assert.deepEqual(config.services[service].ports ?? [], [], `${service} must have no host ports`)
    }
    assert.deepEqual(config.services['api-app'].volumes ?? [], [], 'Mac bind mounts must be removed')
    assert.equal(config.services.frontend.ports.length, 1)
    assert.equal(config.services.frontend.ports[0].host_ip, '127.0.0.1')
    assert.equal(config.services.frontend.ports[0].published, '3000')
    for (const service of ['dozzle', 'kafka-ui']) {
      assert.equal(config.services[service].ports.length, 1)
      assert.equal(config.services[service].ports[0].host_ip, '127.0.0.1')
    }
    for (const service of Object.keys(config.services)) {
      assert.equal(config.services[service].restart, 'unless-stopped', `${service} restart policy`)
    }
    assert.equal(config.services['api-app'].stop_grace_period, '1m0s')
    assert.equal(config.services['batch-app'].stop_grace_period, '1m30s')
    assert.equal(config.services['consumer-app'].stop_grace_period, '1m30s')
    assert.equal(config.services.frontend.stop_grace_period, '10s')
    assert.equal(config.services.kafka.volumes[0].source, 'kafka_data')
    assert.equal(config.services.kafka.volumes[0].target, '/var/lib/kafka/data')
    assert.equal(config.services.kafka.environment.KAFKA_LOG_DIRS, '/var/lib/kafka/data')
    assert.equal(config.services.redis.volumes[0].source, 'redis_data')
    assert.equal(config.volumes.kafka_data.name, 'magicjar_kafka_data')
    assert.equal(config.volumes.redis_data.name, 'magicjar_redis_data')
    for (const service of ['postgres', 'kafka', 'redis', 'dozzle', 'kafka-ui']) {
      assert.match(config.services[service].image, /@sha256:[0-9a-f]{64}$/, `${service} image digest`)
    }
    for (const service of ['api-app', 'batch-app', 'consumer-app', 'frontend']) {
      assert.match(config.services[service].image, /:0123456789ab$/)
      assert.equal(
        config.services[service].labels['org.opencontainers.image.revision'],
        '0123456789abcdef0123456789abcdef01234567',
      )
    }
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('frontend image build uses the declared Node 24 version', () => {
  const nodeVersion = readFileSync(join(root, 'frontend/.node-version'), 'utf8').trim()
  const dockerfile = readFileSync(join(root, 'frontend/Dockerfile'), 'utf8')

  assert.equal(nodeVersion, '24.18.0')
  assert.match(dockerfile, new RegExp(`^FROM node:${nodeVersion}-alpine AS build$`, 'm'))
})
