import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, symlinkSync, utimesSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawn, spawnSync } from 'node:child_process'
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

test('Grafana verification uses the desired env-file credential rather than the existing container credential', () => {
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-grafana-auth-drift-'))
  try {
    const deploy = readFileSync(join(root, 'scripts/deploy-nas-observability.sh'), 'utf8')
    assert.match(deploy, /\.RestartCount/)
    assert.match(deploy, /git archive "\$MAGICJAR_EXPECTED_GIT_SHA"/)
    assert.match(deploy, /APPROVED_ROOT/)
    const functionStart = deploy.indexOf('verify_grafana_auth() {')
    const functionEnd = deploy.indexOf('\nverify_targets() {', functionStart)
    assert.ok(functionStart >= 0 && functionEnd > functionStart)
    const verifyFunction = deploy.slice(functionStart, functionEnd)
    const fakeBin = join(fixture, 'bin')
    mkdirSync(fakeBin, { recursive: true })
    const fakeNc = join(fakeBin, 'nc')
    writeFileSync(fakeNc, `#!/bin/sh
request="$(cat)"
expected="$(printf '%s' 'desired-admin:desired-password-123456789' | base64 | tr -d '\\n')"
case "$request" in
  *"Authorization: Basic $expected"*) printf 'HTTP/1.1 200 OK\\r\\nContent-Type: application/json\\r\\n\\r\\n{"login":"%s"}\\n' "\${FAKE_GRAFANA_LOGIN:-desired-admin}" ;;
  *) printf 'HTTP/1.1 401 Unauthorized\\r\\n\\r\\n' ;;
esac
`)
    chmodSync(fakeNc, 0o755)
    const harness = join(fixture, 'verify-grafana-auth.sh')
    writeFileSync(harness, `#!/usr/bin/env bash
set -Eeuo pipefail
ok() { :; }
mj_docker() {
  [ "$1" = exec ] || return 90
  shift
  [ "\${1:-}" = -i ] && shift
  [ "$1" = magicjar-grafana ] || return 91
  shift
  GF_SECURITY_ADMIN_USER=existing-admin \\
  GF_SECURITY_ADMIN_PASSWORD=existing-password-000000 \\
  PATH='${fakeBin}:/usr/bin:/bin' "$@"
}
${verifyFunction}
verify_grafana_auth desired-admin desired-password-123456789
`)
    chmodSync(harness, 0o755)
    const result = run('bash', [harness])
    assert.equal(result.status, 0, result.stderr)
    assert.doesNotMatch(`${result.stdout}\n${result.stderr}`, /desired-password-123456789/)
    const wrongIdentity = run('bash', [harness], {
      env: { ...process.env, FAKE_GRAFANA_LOGIN: 'different-admin' },
    })
    assert.notEqual(wrongIdentity.status, 0)
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('observability core invariance mismatch executes rollback', () => {
  const deploy = readFileSync(join(root, 'scripts/deploy-nas-observability.sh'), 'utf8')
  const start = deploy.indexOf('enforce_core_invariance() {')
  const end = deploy.indexOf('\n}\n', start)
  assert.ok(start >= 0 && end > start)
  const fn = deploy.slice(start, end + 3)
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-core-invariance-'))
  try {
    const rollbackLog = join(fixture, 'rollback.log')
    const result = run('bash', ['-ceu', `${fn}
snapshot_core_runtime() { printf '%s\\n' changed; }
obs_compose() { printf '%s\\n' "$*" > "$ROLLBACK_LOG"; }
enforce_core_invariance original rollback
`], {
      env: { ...process.env, ROLLBACK_LOG: rollbackLog },
    })
    assert.notEqual(result.status, 0)
    assert.equal(readFileSync(rollbackLog, 'utf8').trim(), 'down')
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('NAS backup scheduler entrypoint is atomic and never bypasses DSM scheduler', () => {
  const backup = readFileSync(join(root, 'scripts/backup/nas-task-scheduler-backup.sh'), 'utf8')
  const installer = readFileSync(join(root, 'scripts/install-nas-backup-entrypoint.sh'), 'utf8')

  assert.match(backup, /umask 077/)
  assert.match(backup, /PARTIAL_SET=.*\.partial/)
  assert.match(backup, /redis-cli --raw SET "\$LOCK_KEY" "\$LOCK_TOKEN" NX PX/)
  assert.match(backup, /redis\.call\("pexpire"/)
  assert.match(backup, /redis\.call\("del"/)
  assert.match(backup, /ln "\$LOCK_CANDIDATE" "\$LOCK_FILE"/)
  assert.match(backup, /assert_filesystem_lock_owned/)
  assert.match(backup, /"\$MV_BIN" -T "\$PARTIAL_SET" "\$FINAL_SET"/)
  assert.match(backup, /random_lock_token/)
  assert.match(backup, /\/proc\/sys\/kernel\/random\/uuid|\/dev\/urandom/)
  assert.match(backup, /cd "\$SET_ROOT"/)
  assert.match(backup, /\[ ! -L "\$SET_ROOT" \].*\[ -d "\$SET_ROOT" \]/)
  assert.doesNotMatch(backup, /mkdir -p "\$SET_ROOT"/)
  assert.ok((backup.match(/assert_lock_owned/g) ?? []).length >= 5)
  assert.match(backup, /pg_restore --list/)
  assert.match(backup, /redis-cli --raw BGSAVE/)
  assert.match(backup, /exec "\$REDIS_CONTAINER" cat \/data\/dump\.rdb/)
  assert.doesNotMatch(backup, /chmod 0600 "\$PARTIAL_SET"\/\*/)
  assert.match(backup, /backup set file mode is not 0600/)
  assert.match(backup, /com\.docker\.compose\.project/)
  assert.match(backup, /com\.docker\.compose\.service/)
  assert.match(backup, /sha256sum|shasum -a 256/)
  assert.match(backup, /AFTER_SAVE.*-gt.*BEFORE_SAVE/)
  const publishCommand = '"$MV_BIN" -T "$PARTIAL_SET" "$FINAL_SET"'
  assert.ok(backup.indexOf('published backup set shape invalid before publish') < backup.indexOf(publishCommand))
  assert.match(backup, /\[ ! -L "\$PARTIAL_SET\/\$required" \].*\[ -f "\$PARTIAL_SET\/\$required" \]/)
  assert.match(backup, /PUBLISHED_UNCOMMITTED/)
  assert.match(backup, /\.publish-owner/)
  assert.ok(backup.indexOf('PUBLISHED_UNCOMMITTED=1') < backup.indexOf(publishCommand))
  assert.match(backup, /\/volume1\/docker\/magicjar\/backups/)
  assert.doesNotMatch(backup, /synoschedtask|crontab/)

  assert.match(installer, /mj_require_release_provenance/)
  assert.match(installer, /mj_require_remote_docker/)
  assert.match(installer, /--pull never/)
  assert.match(installer, /--network none/)
  assert.match(installer, /--security-opt no-new-privileges/)
  assert.match(installer, /--entrypoint sh/)
  assert.match(installer, /--cap-drop ALL/)
  assert.match(installer, /--read-only/)
  assert.match(installer, /--format '\{\{\.Image\}\}'/)
  assert.match(installer, /docker-compose\.nas\.yml/)
  assert.match(installer, /git show "\$MAGICJAR_EXPECTED_GIT_SHA:scripts\/backup\/nas-task-scheduler-backup\.sh"/)
  assert.match(installer, /SOURCE_SNAPSHOT/)
  assert.doesNotMatch(installer, /< "\$SOURCE"/)
  assert.match(installer, /\.Config\.Image/)
  assert.match(installer, /image inspect/)
  assert.match(installer, /\/volume1\/docker\/magicjar\/ops/)
  assert.match(installer, /-v \/volume1:\/nas-volume1:ro/)
  assert.match(installer, /-v \/volume1\/docker\/magicjar:\/nas-magicjar/)
  assert.doesNotMatch(installer, /-v "\$TARGET_DIR:\/target"/)
  assert.match(installer, /mktemp .*nas-task-scheduler-backup\.sh\.tmp\.XXXXXX/)
  assert.match(installer, /sets="\$backups\/sets"/)
  assert.match(installer, /cd "\$ops"/)
  assert.match(installer, /mv -T "\$tmp" "\$final"/)
  assert.match(installer, /\[ ! -L "\$final" \].*\[ -f "\$final" \]/)
  assert.doesNotMatch(installer, /^\s*(?:sudo\s+)?(?:synoschedtask|crontab)\b/m)

  const swappedPaths = run('bash', ['scripts/install-nas-backup-entrypoint.sh'], {
    env: {
      ...process.env,
      MAGICJAR_NAS_OPS_DIR: '/volume1/docker/magicjar/backups',
      MAGICJAR_NAS_BACKUP_ROOT: '/volume1/docker/magicjar/ops',
    },
  })
  assert.notEqual(swappedPaths.status, 0)
  assert.match(swappedPaths.stderr, /unsafe target dir/)
})

test('NAS backup installer transaction rejects parent and final symlinks', () => {
  const fixture = mkdtempSync(join(realpathSync(tmpdir()), 'magicjar-installer-transaction-'))
  try {
    const installer = readFileSync(join(root, 'scripts/install-nas-backup-entrypoint.sh'), 'utf8')
    const transaction = installer.match(/"\$image" -ceu '\n([\s\S]*?)\n  ' sh "\$local_sha"/)
    assert.ok(transaction, 'installer transaction body must remain extractable')
    const fakeBin = join(fixture, 'bin')
    mkdirSync(fakeBin)
    writeFileSync(join(fakeBin, 'readlink'), '#!/bin/sh\npython3 -c "import os,sys; print(os.path.realpath(sys.argv[1]))" "$2"\n')
    writeFileSync(join(fakeBin, 'sha256sum'), '#!/bin/sh\nshasum -a 256 "$1"\n')
    writeFileSync(join(fakeBin, 'stat'), '#!/bin/sh\ncase "$2" in %d:%i) /usr/bin/stat -f %d:%i "$3" ;; *) /usr/bin/stat -f %Lp "$3" ;; esac\n')
    writeFileSync(join(fakeBin, 'mv'), '#!/bin/sh\n[ "${1:-}" != -T ] || shift\n/bin/mv "$@"\n')
    for (const command of ['readlink', 'sha256sum', 'stat', 'mv']) chmodSync(join(fakeBin, command), 0o755)
    const payload = '#!/bin/sh\nprintf "backup fixture\\n"\n'
    const expected = createHash('sha256').update(payload).digest('hex')

    const executeTransaction = (fixtureRoot) => {
      const volume = join(fixtureRoot, 'nas-volume1')
      mkdirSync(join(volume, 'docker/magicjar'), { recursive: true })
      const script = transaction[1]
        .replace(/^\s*trap .*$/gm, '')
        .replaceAll('/nas-volume1', volume)
        .replaceAll('/nas-magicjar', join(volume, 'docker/magicjar'))
      return run('sh', ['-ceu', script, 'sh', expected], {
        env: { ...process.env, PATH: `${fakeBin}:${process.env.PATH}` },
        input: payload,
      })
    }

    const happyRoot = join(fixture, 'happy')
    const happy = executeTransaction(happyRoot)
    assert.equal(happy.status, 0, happy.stderr)
    const installed = join(happyRoot, 'nas-volume1/docker/magicjar/ops/nas-task-scheduler-backup.sh')
    assert.equal(readFileSync(installed, 'utf8'), payload)

    const parentSymlinkRoot = join(fixture, 'parent-symlink')
    mkdirSync(join(parentSymlinkRoot, 'nas-volume1/docker'), { recursive: true })
    mkdirSync(join(parentSymlinkRoot, 'outside'))
    symlinkSync(join(parentSymlinkRoot, 'outside'), join(parentSymlinkRoot, 'nas-volume1/docker/magicjar'))
    const parentSymlink = executeTransaction(parentSymlinkRoot)
    assert.notEqual(parentSymlink.status, 0)
    assert.match(parentSymlink.stderr, /unsafe NAS path/)

    const setsSymlinkRoot = join(fixture, 'sets-symlink')
    mkdirSync(join(setsSymlinkRoot, 'nas-volume1/docker/magicjar/ops'), { recursive: true })
    mkdirSync(join(setsSymlinkRoot, 'nas-volume1/docker/magicjar/backups'), { recursive: true })
    mkdirSync(join(setsSymlinkRoot, 'outside-sets'))
    symlinkSync(join(setsSymlinkRoot, 'outside-sets'), join(setsSymlinkRoot, 'nas-volume1/docker/magicjar/backups/sets'))
    const setsSymlink = executeTransaction(setsSymlinkRoot)
    assert.notEqual(setsSymlink.status, 0)
    assert.match(setsSymlink.stderr, /unsafe NAS path/)

    const finalSymlinkRoot = join(fixture, 'final-symlink')
    const ops = join(finalSymlinkRoot, 'nas-volume1/docker/magicjar/ops')
    mkdirSync(join(finalSymlinkRoot, 'nas-volume1/docker/magicjar/backups/sets'), { recursive: true })
    mkdirSync(ops, { recursive: true })
    writeFileSync(join(finalSymlinkRoot, 'outside-entrypoint'), 'do not replace')
    symlinkSync(join(finalSymlinkRoot, 'outside-entrypoint'), join(ops, 'nas-task-scheduler-backup.sh'))
    const finalSymlink = executeTransaction(finalSymlinkRoot)
    assert.notEqual(finalSymlink.status, 0)
    assert.match(finalSymlink.stderr, /unsafe existing entrypoint/)
    assert.equal(readFileSync(join(finalSymlinkRoot, 'outside-entrypoint'), 'utf8'), 'do not replace')

    const finalDirectoryRoot = join(fixture, 'final-directory')
    const finalDirectory = join(finalDirectoryRoot, 'nas-volume1/docker/magicjar/ops/nas-task-scheduler-backup.sh')
    mkdirSync(join(finalDirectoryRoot, 'nas-volume1/docker/magicjar/backups/sets'), { recursive: true })
    mkdirSync(finalDirectory, { recursive: true })
    const directoryResult = executeTransaction(finalDirectoryRoot)
    assert.notEqual(directoryResult.status, 0)
    assert.match(directoryResult.stderr, /unsafe existing entrypoint/)
    assert.deepEqual(readdirSync(finalDirectory), [])
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('NAS backup scheduler publishes PostgreSQL and Redis only after validated container identity', async () => {
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
        *Config.Image*)
          if [ -n "\${FAKE_CONFIG_IMAGE:-}" ]; then printf '%s\\n' "$FAKE_CONFIG_IMAGE"
          elif [ "$container" = magicjar-postgres ]; then printf '%s\\n' 'postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777'
          else printf '%s\\n' 'redis@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99'; fi
          ;;
        *'{{.Image}}'*) printf 'sha256:%064d\\n' 0 ;;
      esac
    fi
    ;;
  exec)
    [ "\${1:-}" = -i ] && shift
    container="$1"; shift
    case "$*" in
      *pg_isready*) exit 0 ;;
      *pg_dump*) sleep "\${FAKE_DOCKER_DELAY:-0}"; printf '%s\\n' FAKE_CUSTOM_FORMAT_DUMP ;;
      *pg_restore*--list*) cat >/dev/null ;;
      *redis-cli*PING*) printf '%s\\n' PONG ;;
      *redis-cli*SET*NX*PX*)
        token=""; previous=""
        for argument in "$@"; do
          [ "$previous" = magicjar:nas-backup:lock ] && token="$argument"
          previous="$argument"
        done
        if mkdir "$FAKE_LEASE_DIR" 2>/dev/null; then
          printf '%s\\n' "$token" > "$FAKE_LEASE_DIR/token"
          printf '%s\\n' OK
        fi
        ;;
      *redis-cli*EVAL*)
        last=""; token=""
        for argument in "$@"; do token="$last"; last="$argument"; done
        owner="$(cat "$FAKE_LEASE_DIR/token" 2>/dev/null || true)"
        case "$*" in
          *redis.call*del*)
            if [ "$owner" = "$token" ]; then rm -rf "$FAKE_LEASE_DIR"; printf '%s\\n' 1; else printf '%s\\n' 0; fi
            ;;
          *) [ "$owner" = "$token" ] && printf '%s\\n' 1 || printf '%s\\n' 0 ;;
        esac
        ;;
      *redis-cli*LASTSAVE*) [ -f "$FAKE_DOCKER_STATE" ] && cat "$FAKE_DOCKER_STATE" || printf '%s\\n' 100 ;;
      *redis-cli*BGSAVE*)
        if [ -n "\${FAKE_BGSAVE_RESPONSE:-}" ]; then
          printf '%s\\n' "$FAKE_BGSAVE_RESPONSE"
        else
          printf '%s\\n' 101 > "$FAKE_DOCKER_STATE"
          printf '%s\\n' 'Background saving started'
        fi
        ;;
      *redis-cli*INFO*persistence*) printf 'rdb_bgsave_in_progress:0\\r\\nrdb_last_bgsave_status:ok\\r\\n' ;;
      *cat*\/data\/dump.rdb*) printf '%s\\n' FAKE_RDB ;;
      *) exit 3 ;;
    esac
    ;;
  image) printf 'sha256:%064d\\n' 0 ;;
  cp)
    printf '%s\\n' FAKE_RDB > "$2"
    ;;
  *) exit 4 ;;
esac
`,
    )
    chmodSync(fakeDocker, 0o755)
    const fakeMv = join(fixture, 'mv-no-target-directory')
    writeFileSync(fakeMv, '#!/bin/sh\n[ "${1:-}" != -T ] || shift\n/bin/mv "$@"\n')
    chmodSync(fakeMv, 0o755)

    const backupRoot = join(fixture, 'backups')
    mkdirSync(backupRoot, { recursive: true })
    mkdirSync(join(backupRoot, 'sets/.stale.partial.999999'), { recursive: true })
    writeFileSync(join(backupRoot, 'sets/.stale.partial.999999/postgres.dump'), 'crash residue')
    const staleDate = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000)
    utimesSync(join(backupRoot, 'sets/.stale.partial.999999'), staleDate, staleDate)
    const fakeState = join(fixture, 'redis-lastsave')
    const backupStartedAt = Date.now()
    const result = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...process.env,
        MAGICJAR_DOCKER_BIN: fakeDocker,
        MAGICJAR_MV_BIN: fakeMv,
        MAGICJAR_NAS_BACKUP_ROOT: backupRoot,
        MAGICJAR_NAS_BACKUP_MIN_FREE_KB: '1',
        FAKE_DOCKER_STATE: fakeState,
        FAKE_LEASE_DIR: join(fixture, 'main-lease'),
      },
    })
    assert.equal(result.status, 0, result.stderr)
    assert.ok(Date.now() - backupStartedAt < 5000, 'lease renewal cleanup must not wait for the full sleep interval')
    assert.match(result.stdout, /BACKUP_PASS/)

    const sets = readdirSync(join(backupRoot, 'sets'))
    assert.equal(sets.length, 1)
    assert.equal(sets[0].includes('.partial'), false)
    assert.deepEqual(readdirSync(join(backupRoot, 'sets', sets[0])).sort(), [
      'postgres.dump',
      'postgres.dump.meta',
      'postgres.dump.sha256',
      'redis.rdb',
      'redis.rdb.meta',
      'redis.rdb.sha256',
    ])

    const rejectedRoot = join(fixture, 'rejected')
    mkdirSync(join(rejectedRoot, 'sets'), { recursive: true })
    const rejected = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...process.env,
        MAGICJAR_DOCKER_BIN: fakeDocker,
        MAGICJAR_NAS_BACKUP_ROOT: rejectedRoot,
        MAGICJAR_NAS_BACKUP_MIN_FREE_KB: '1',
        FAKE_PROJECT: 'wrong-project',
        FAKE_DOCKER_STATE: join(fixture, 'rejected-redis-lastsave'),
        FAKE_LEASE_DIR: join(fixture, 'rejected-lease'),
      },
    })
    assert.notEqual(rejected.status, 0)
    assert.match(rejected.stderr, /unexpected Compose project/)
    assert.equal(readdirSync(join(rejectedRoot, 'sets')).length, 0)

    const wrongImageRoot = join(fixture, 'wrong-image')
    mkdirSync(join(wrongImageRoot, 'sets'), { recursive: true })
    const wrongImage = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...process.env,
        MAGICJAR_DOCKER_BIN: fakeDocker,
        MAGICJAR_NAS_BACKUP_ROOT: wrongImageRoot,
        MAGICJAR_NAS_BACKUP_MIN_FREE_KB: '1',
        FAKE_CONFIG_IMAGE: 'postgres:mutable',
      },
    })
    assert.notEqual(wrongImage.status, 0)
    assert.match(wrongImage.stderr, /unexpected approved image reference/)

    const staleRdbRoot = join(fixture, 'stale-rdb')
    mkdirSync(join(staleRdbRoot, 'sets'), { recursive: true })
    const staleRdb = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...process.env,
        MAGICJAR_DOCKER_BIN: fakeDocker,
        MAGICJAR_NAS_BACKUP_ROOT: staleRdbRoot,
        MAGICJAR_NAS_BACKUP_MIN_FREE_KB: '1',
        MAGICJAR_REDIS_BGSAVE_TIMEOUT_SECONDS: '0',
        FAKE_DOCKER_STATE: join(fixture, 'stale-rdb-lastsave'),
        FAKE_LEASE_DIR: join(fixture, 'stale-rdb-lease'),
        FAKE_BGSAVE_RESPONSE: 'ERR Background save already in progress',
      },
    })
    assert.notEqual(staleRdb.status, 0)
    assert.match(staleRdb.stderr, /Redis BGSAVE was not started/)
    assert.equal(readdirSync(join(staleRdbRoot, 'sets')).length, 0)

    const symlinkRoot = join(fixture, 'runtime-symlink')
    const outsideSets = join(fixture, 'runtime-symlink-outside')
    mkdirSync(symlinkRoot, { recursive: true })
    mkdirSync(outsideSets)
    symlinkSync(outsideSets, join(symlinkRoot, 'sets'))
    const symlinkResult = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...process.env,
        MAGICJAR_DOCKER_BIN: fakeDocker,
        MAGICJAR_NAS_BACKUP_ROOT: symlinkRoot,
        MAGICJAR_NAS_BACKUP_MIN_FREE_KB: '1',
      },
    })
    assert.notEqual(symlinkResult.status, 0)
    assert.match(symlinkResult.stderr, /unsafe backup sets directory/)
    assert.deepEqual(readdirSync(outsideSets), [])

    const publishSignalRoot = join(fixture, 'publish-signal')
    mkdirSync(join(publishSignalRoot, 'sets'), { recursive: true })
    const signalAfterMove = join(fixture, 'signal-after-move')
    writeFileSync(signalAfterMove, '#!/bin/sh\n[ "${1:-}" != -T ] || shift\n/bin/mv "$@"\nkill -TERM "$PPID"\n')
    chmodSync(signalAfterMove, 0o755)
    const publishSignal = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...process.env,
        MAGICJAR_DOCKER_BIN: fakeDocker,
        MAGICJAR_MV_BIN: signalAfterMove,
        MAGICJAR_NAS_BACKUP_ROOT: publishSignalRoot,
        MAGICJAR_NAS_BACKUP_MIN_FREE_KB: '1',
        FAKE_DOCKER_STATE: join(fixture, 'publish-signal-lastsave'),
        FAKE_LEASE_DIR: join(fixture, 'publish-signal-lease'),
      },
    })
    assert.notEqual(publishSignal.status, 0)
    assert.doesNotMatch(publishSignal.stdout, /BACKUP_PASS/)
    assert.deepEqual(readdirSync(join(publishSignalRoot, 'sets')), [])


  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('NAS backup Redis lease admits one owner and blocks publish after lease loss', async () => {
  const fixture = mkdtempSync(join(tmpdir(), 'magicjar-backup-redis-lease-'))
  try {
    const backupRoot = join(fixture, 'backups')
    mkdirSync(join(backupRoot, 'sets'), { recursive: true })
    const fakeState = join(fixture, 'redis-lastsave')
    const leaseDir = join(fixture, 'lease')
    const fakeDocker = join(fixture, 'docker')
    writeFileSync(fakeDocker, `#!/bin/sh
set -eu
cmd="$1"; shift
case "$cmd" in
  inspect)
    container="$1"; shift
    case "$*" in
      *State.Running*) printf '%s\\n' true ;;
      *compose.project*) printf '%s\\n' magicjar ;;
      *compose.service*) [ "$container" = magicjar-postgres ] && printf '%s\\n' postgres || printf '%s\\n' redis ;;
      *Config.Image*)
        if [ "$container" = magicjar-postgres ]; then printf '%s\\n' 'postgres@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777'
        else printf '%s\\n' 'redis@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99'; fi
        ;;
      *'{{.Image}}'*) printf 'sha256:%064d\\n' 0 ;;
    esac
    ;;
  exec)
    [ "\${1:-}" = -i ] && shift
    container="$1"; shift
    case "$*" in
      *pg_isready*) exit 0 ;;
      *pg_dump*) sleep "\${FAKE_DOCKER_DELAY:-0}"; printf '%s\\n' FAKE_CUSTOM_FORMAT_DUMP ;;
      *pg_restore*--list*) cat >/dev/null ;;
      *redis-cli*PING*) printf '%s\\n' PONG ;;
      *redis-cli*SET*NX*PX*)
        token=""
        previous=""
        for argument in "$@"; do
          [ "$previous" = magicjar:nas-backup:lock ] && token="$argument"
          previous="$argument"
        done
        if mkdir "$FAKE_LEASE_DIR" 2>/dev/null; then
          printf '%s\\n' "$token" > "$FAKE_LEASE_DIR/token"
          printf '%s\\n' OK
        fi
        ;;
      *redis-cli*EVAL*)
        last=""; token=""
        for argument in "$@"; do token="$last"; last="$argument"; done
        owner="$(cat "$FAKE_LEASE_DIR/token" 2>/dev/null || true)"
        case "$*" in
          *pexpire*)
            if [ "\${FAKE_FAIL_RENEW:-0}" = 1 ]; then printf '%s\\n' 0
            elif [ "$owner" = "$token" ]; then printf '%s\\n' 1
            else printf '%s\\n' 0; fi
            ;;
          *redis.call*del*)
            if [ "$owner" = "$token" ]; then rm -rf "$FAKE_LEASE_DIR"; printf '%s\\n' 1; else printf '%s\\n' 0; fi
            ;;
          *)
            count="$(cat "$FAKE_LEASE_DIR/assert-count" 2>/dev/null || printf '%s' 0)"
            count=$((count + 1))
            printf '%s\\n' "$count" > "$FAKE_LEASE_DIR/assert-count"
            if [ "\${FAKE_DROP_LEASE_ON_ASSERT:-0}" = "$count" ]; then printf '%s\\n' replacement-token > "$FAKE_LEASE_DIR/token"; printf '%s\\n' 0
            elif [ "$owner" = "$token" ]; then printf '%s\\n' 1
            else printf '%s\\n' 0; fi
            ;;
        esac
        ;;
      *redis-cli*LASTSAVE*) [ -f "$FAKE_DOCKER_STATE" ] && cat "$FAKE_DOCKER_STATE" || printf '%s\\n' 100 ;;
      *redis-cli*BGSAVE*) printf '%s\\n' 101 > "$FAKE_DOCKER_STATE"; printf '%s\\n' 'Background saving started' ;;
      *redis-cli*INFO*persistence*) printf 'rdb_bgsave_in_progress:0\\r\\nrdb_last_bgsave_status:ok\\r\\n' ;;
      *cat*\/data\/dump.rdb*) printf '%s\\n' FAKE_RDB ;;
      *) exit 3 ;;
    esac
    ;;
  image) printf 'sha256:%064d\\n' 0 ;;
  cp) printf '%s\\n' FAKE_RDB > "$2" ;;
  *) exit 4 ;;
esac
`)
    chmodSync(fakeDocker, 0o755)
    const fakeMv = join(fixture, 'mv-no-target-directory')
    writeFileSync(fakeMv, '#!/bin/sh\n[ "${1:-}" != -T ] || shift\n/bin/mv "$@"\n')
    chmodSync(fakeMv, 0o755)

    const concurrentEnv = {
      ...process.env,
      MAGICJAR_DOCKER_BIN: fakeDocker,
      MAGICJAR_MV_BIN: fakeMv,
      MAGICJAR_NAS_BACKUP_ROOT: backupRoot,
      MAGICJAR_NAS_BACKUP_MIN_FREE_KB: '1',
      MAGICJAR_NAS_BACKUP_LOCK_TTL_MS: '30000',
      MAGICJAR_NAS_BACKUP_LOCK_RENEW_SECONDS: '1',
      FAKE_DOCKER_DELAY: '2',
      FAKE_DOCKER_STATE: fakeState,
      FAKE_LEASE_DIR: leaseDir,
    }
    const launch = () => {
      const child = spawn('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
        cwd: root,
        env: concurrentEnv,
        stdio: ['ignore', 'pipe', 'pipe'],
      })
      let stdout = ''
      let stderr = ''
      child.stdout.on('data', (chunk) => { stdout += chunk.toString() })
      child.stderr.on('data', (chunk) => { stderr += chunk.toString() })
      return new Promise((resolveExit) => child.on('close', (status) => resolveExit({ status, stdout, stderr })))
    }

    const [first, second] = await Promise.all([launch(), launch()])
    assert.equal([first, second].filter((result) => result.status === 0).length, 1, JSON.stringify({ first, second }))
    assert.equal([first, second].filter((result) => /another backup is running/.test(result.stderr)).length, 1)
    assert.equal(readdirSync(join(backupRoot, 'sets')).length, 1)
    assert.equal(existsSync(leaseDir), false, 'the owner must release its own lease')

    const lostRoot = join(fixture, 'lost-lease')
    mkdirSync(join(lostRoot, 'sets'), { recursive: true })
    const lostLease = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...concurrentEnv,
        MAGICJAR_NAS_BACKUP_ROOT: lostRoot,
        FAKE_DOCKER_DELAY: '0',
        FAKE_DOCKER_STATE: join(fixture, 'lost-lastsave'),
        FAKE_LEASE_DIR: join(fixture, 'lost-lease-key'),
        FAKE_DROP_LEASE_ON_ASSERT: '3',
      },
    })
    assert.notEqual(lostLease.status, 0)
    assert.match(lostLease.stderr, /lease was lost before publish/)
    assert.equal(readdirSync(join(lostRoot, 'sets')).length, 0)
    assert.equal(readFileSync(join(fixture, 'lost-lease-key/token'), 'utf8').trim(), 'replacement-token')

    const commitFenceRoot = join(fixture, 'commit-fence')
    mkdirSync(join(commitFenceRoot, 'sets'), { recursive: true })
    const commitFence = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...concurrentEnv,
        MAGICJAR_NAS_BACKUP_ROOT: commitFenceRoot,
        FAKE_DOCKER_DELAY: '0',
        FAKE_DOCKER_STATE: join(fixture, 'commit-fence-lastsave'),
        FAKE_LEASE_DIR: join(fixture, 'commit-fence-key'),
        FAKE_DROP_LEASE_ON_ASSERT: '5',
      },
    })
    assert.notEqual(commitFence.status, 0)
    assert.match(commitFence.stderr, /lease was lost at commit boundary/)
    assert.equal(readdirSync(join(commitFenceRoot, 'sets')).length, 0)

    const renewFailureRoot = join(fixture, 'renew-failure')
    mkdirSync(join(renewFailureRoot, 'sets'), { recursive: true })
    const renewFailure = run('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
      env: {
        ...concurrentEnv,
        MAGICJAR_NAS_BACKUP_ROOT: renewFailureRoot,
        FAKE_DOCKER_DELAY: '2',
        FAKE_DOCKER_STATE: join(fixture, 'renew-failure-lastsave'),
        FAKE_LEASE_DIR: join(fixture, 'renew-failure-key'),
        FAKE_FAIL_RENEW: '1',
      },
    })
    assert.notEqual(renewFailure.status, 0)
    assert.doesNotMatch(renewFailure.stdout, /BACKUP_PASS/)
    assert.equal(readdirSync(join(renewFailureRoot, 'sets')).length, 0)

    const runSignalCase = async (signal, expectedStatus) => {
      const signalRoot = join(fixture, `signal-${signal}`)
      const signalLeaseDir = join(fixture, `signal-${signal}-key`)
      mkdirSync(join(signalRoot, 'sets'), { recursive: true })
      const child = spawn('sh', ['scripts/backup/nas-task-scheduler-backup.sh'], {
        cwd: root,
        env: {
          ...concurrentEnv,
          MAGICJAR_NAS_BACKUP_ROOT: signalRoot,
          FAKE_DOCKER_DELAY: '1',
          FAKE_DOCKER_STATE: join(fixture, `signal-${signal}-lastsave`),
          FAKE_LEASE_DIR: signalLeaseDir,
        },
        stdio: ['ignore', 'pipe', 'pipe'],
      })
      let stdout = ''
      let stderr = ''
      child.stdout.on('data', (chunk) => { stdout += chunk.toString() })
      child.stderr.on('data', (chunk) => { stderr += chunk.toString() })
      for (let attempt = 0; attempt < 100 && !existsSync(signalLeaseDir); attempt += 1) {
        await new Promise((resolveWait) => setTimeout(resolveWait, 10))
      }
      assert.equal(existsSync(signalLeaseDir), true, `${signal} case must acquire the lease`)
      child.kill(signal)
      const status = await new Promise((resolveExit) => child.on('close', resolveExit))
      assert.equal(status, expectedStatus, JSON.stringify({ signal, stdout, stderr }))
      assert.doesNotMatch(stdout, /BACKUP_PASS/)
      assert.equal(readdirSync(join(signalRoot, 'sets')).length, 0)
      assert.equal(existsSync(signalLeaseDir), false, `${signal} cleanup must release its token`)
    }
    await runSignalCase('SIGHUP', 129)
    await runSignalCase('SIGINT', 130)
    await runSignalCase('SIGTERM', 143)
  } finally {
    rmSync(fixture, { recursive: true, force: true })
  }
})

test('R4 recovery commands pin the NAS Docker context', () => {
  const runbook = readFileSync(join(root, 'docs/operations/runbook/R4-db-recovery.md'), 'utf8')
  assert.doesNotMatch(runbook, /^docker (?!--context nas\b)/m)
  assert.match(runbook, /docker --context nas stop magicjar-api magicjar-batch magicjar-consumer/)
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
