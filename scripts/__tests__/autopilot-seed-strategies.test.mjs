import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import {
  LIQUIDITY_ANCHORS,
  STRATEGIES,
  buildPreflightSql,
  buildTransactionalSeedSql,
  createPsqlClient,
  parseArgs,
  runSeed,
} from '../autopilot-seed-strategies.mjs';

const VALID_ARGS = ['--user-id', '67731510', '--docker-context', 'nas'];

function fakeClient(preflight = [['1', '1', '0', '0', '0', '0']]) {
  const calls = { queries: [], execs: [] };
  return {
    calls,
    query(sql) {
      calls.queries.push(sql);
      return preflight;
    },
    exec(sql) {
      calls.execs.push(sql);
    },
  };
}

function silentLogger() {
  const messages = [];
  return {
    messages,
    log(message) {
      messages.push(String(message));
    },
    error(message) {
      messages.push(String(message));
    },
  };
}

function runPrePushHook({ pushContainsSeed, headContainsSeed = false, diffFails = false }) {
  const root = mkdtempSync(join(tmpdir(), 'magicjar-pre-push-test-'));
  const fakeBin = join(root, 'bin');
  const callLog = join(root, 'node-calls.log');
  const localSha = 'b'.repeat(40);
  const remoteSha = 'a'.repeat(40);
  try {
    mkdirSync(join(root, '.husky'), { recursive: true });
    mkdirSync(join(root, 'gradle', 'wrapper'), { recursive: true });
    mkdirSync(join(root, 'scripts', '__tests__'), { recursive: true });
    mkdirSync(fakeBin, { recursive: true });
    copyFileSync(new URL('../../.husky/pre-push', import.meta.url), join(root, '.husky', 'pre-push'));
    writeFileSync(join(root, 'gradle', 'wrapper', 'gradle-wrapper.jar'), 'fixture');
    writeFileSync(join(root, 'gradlew'), '#!/bin/sh\nexit 0\n');
    chmodSync(join(root, 'gradlew'), 0o755);

    writeFileSync(
      join(fakeBin, 'git'),
      `#!/bin/sh
if [ "$1" = "rev-parse" ]; then
  if [ "$2" = "--abbrev-ref" ] && [ "$3" = "HEAD" ]; then
    echo agent/qa/unrelated-checkout
  else
    echo upstream/main
  fi
  exit 0
fi
if [ "$1" = "diff" ]; then
  [ "$FAKE_DIFF_FAILS" = "1" ] && exit 7
  range="$3"
  if [ "$range" = "$FAKE_REMOTE_SHA..$FAKE_LOCAL_SHA" ] && [ "$FAKE_PUSH_SEED" = "1" ]; then
    echo scripts/autopilot-seed-strategies.mjs
  elif [ "$range" = "@{u}..HEAD" ] && [ "$FAKE_HEAD_SEED" = "1" ]; then
    echo scripts/autopilot-seed-strategies.mjs
  else
    echo docs/unrelated.md
  fi
  exit 0
fi
exit 0
`,
    );
    writeFileSync(join(fakeBin, 'node'), '#!/bin/sh\nprintf "%s\\n" "$*" >> "$FAKE_CALL_LOG"\nexit 0\n');
    chmodSync(join(fakeBin, 'git'), 0o755);
    chmodSync(join(fakeBin, 'node'), 0o755);

    const result = spawnSync('/bin/sh', ['.husky/pre-push'], {
      cwd: root,
      encoding: 'utf8',
      input: `refs/heads/agent/strategy/seed ${localSha} refs/heads/agent/strategy/seed ${remoteSha}\n`,
      env: {
        ...process.env,
        PATH: `${fakeBin}:${process.env.PATH}`,
        FAKE_CALL_LOG: callLog,
        FAKE_DIFF_FAILS: diffFails ? '1' : '0',
        FAKE_HEAD_SEED: headContainsSeed ? '1' : '0',
        FAKE_LOCAL_SHA: localSha,
        FAKE_PUSH_SEED: pushContainsSeed ? '1' : '0',
        FAKE_REMOTE_SHA: remoteSha,
      },
    });
    const calls = readFileSync(callLog, { encoding: 'utf8', flag: 'a+' });
    return { calls, result };
  } finally {
    rmSync(root, { force: true, recursive: true });
  }
}

test('parseArgs requires one explicit positive PostgreSQL BIGINT user ID', () => {
  for (const argv of [
    [],
    ['--user-id'],
    ['--user-id', ''],
    ['--user-id', '0'],
    ['--user-id', '-1'],
    ['--user-id', '1.5'],
    ['--user-id', 'abc'],
    ['--user-id', '9223372036854775808'],
    ['--user-id', '1', '--user-id', '2'],
    ['--user-id', '1', '--unknown'],
    ['--user-id', '1'],
    ['--user-id', '1', '--docker-context', 'bad context'],
    ['--user-id', '1', '--docker-context', 'nas', '--docker-context', 'default'],
  ]) {
    assert.throws(() => parseArgs(argv));
  }
  assert.deepEqual(parseArgs(VALID_ARGS), { userId: '67731510', dockerContext: 'nas', dryRun: false });
  assert.deepEqual(parseArgs(['--dry-run', ...VALID_ARGS]), { userId: '67731510', dockerContext: 'nas', dryRun: true });
  assert.throws(
    () => parseArgs(['--user-id=67731510', '--docker-context', 'nas']),
    (error) => error.message === 'unsupported argument',
  );
});

test('psql client passes Docker context as argv and SQL only through stdin', () => {
  const calls = [];
  const execFile = (file, args, options) => {
    calls.push({ file, args, options });
    return '1|1|0|0|0|0\n';
  };
  const client = createPsqlClient('nas', execFile);
  assert.deepEqual(client.query('SELECT 1;'), [['1', '1', '0', '0', '0', '0']]);
  assert.equal(calls[0].file, 'docker');
  assert.deepEqual(calls[0].args.slice(0, 4), ['--context', 'nas', 'exec', '-i']);
  assert.equal(calls[0].options.input, 'SELECT 1;\n');
  assert.equal(calls[0].args.includes('SELECT 1;'), false);
});

test('pre-push runs seed tests for the pushed ref even when another branch is checked out', () => {
  const { calls, result } = runPrePushHook({ pushContainsSeed: true });
  assert.equal(result.status, 0, result.stderr);
  assert.match(calls, /--test scripts\/__tests__\/autopilot-seed-strategies\.test\.mjs/);
});

test('pre-push does not run seed tests for an unrelated pushed ref', () => {
  const { calls, result } = runPrePushHook({ pushContainsSeed: false, headContainsSeed: true });
  assert.equal(result.status, 0, result.stderr);
  assert.doesNotMatch(calls, /--test scripts\/__tests__\/autopilot-seed-strategies\.test\.mjs/);
});

test('pre-push fails closed when pushed-ref diff cannot be computed', () => {
  const { result } = runPrePushHook({ pushContainsSeed: true, diffFails: true });
  assert.notEqual(result.status, 0);
});

test('missing user account fails closed before mutation', () => {
  const client = fakeClient([['0', '0', '0', '0', '0', '0']]);
  assert.throws(() => runSeed({ args: VALID_ARGS, client, logger: silentLogger() }), /user account/i);
  assert.equal(client.calls.execs.length, 0);
});

test('missing exact REAL credential fails closed before mutation', () => {
  const client = fakeClient([['1', '0', '0', '0', '0', '0']]);
  assert.throws(() => runSeed({ args: VALID_ARGS, client, logger: silentLogger() }), /REAL credential/i);
  assert.equal(client.calls.execs.length, 0);
});

test('preflight does not inspect consent and scopes targets by user, owner, and name', () => {
  const sql = buildPreflightSql('67731510');
  assert.match(sql, /user_id\s*=\s*67731510/);
  assert.match(sql, /account_type\s*=\s*'REAL'/);
  assert.match(sql, /owner\s*=\s*'AUTOPILOT'/);
  assert.doesNotMatch(sql, /real_trading_consent/i);
});

test('any duplicate or non-PAUSED target aborts all writes', () => {
  for (const row of [
    [['1', '1', '2', '0', '1', '0']],
    [['1', '1', '1', '1', '0', '0']],
    [['1', '1', '1', '0', '0', '1']],
  ]) {
    const client = fakeClient(row);
    assert.throws(() => runSeed({ args: VALID_ARGS, client, logger: silentLogger() }));
    assert.equal(client.calls.execs.length, 0);
  }
});

test('dry-run validates prerequisites but performs no mutation or raw SQL logging', () => {
  const client = fakeClient([['1', '1', '2', '0', '0', '0']]);
  const logger = silentLogger();
  const result = runSeed({ args: [...VALID_ARGS, '--dry-run'], client, logger });
  assert.equal(result.insertCount, 4);
  assert.equal(client.calls.execs.length, 0);
  const output = logger.messages.join('\n');
  assert.doesNotMatch(output, /67731510/);
  assert.doesNotMatch(output, /user=/);
  assert.doesNotMatch(output, /INSERT INTO|BEGIN;/);
});

test('transactional seed is insert-only, PAUSED, overnight-safe, deterministic, and atomic', () => {
  const sql = buildTransactionalSeedSql('67731510');
  assert.match(sql, /^BEGIN;/);
  assert.match(sql, /COMMIT;\s*$/);
  assert.match(sql, /pg_advisory_xact_lock/);
  assert.match(sql, /SET LOCAL lock_timeout = '5s';/);
  assert.match(sql, /LOCK TABLE strategy IN SHARE ROW EXCLUSIVE MODE;/);
  assert.ok(sql.indexOf('LOCK TABLE strategy') < sql.indexOf('seed user account prerequisite failed'));
  assert.match(sql, /user_account[\s\S]*FOR UPDATE/);
  assert.match(sql, /user_kis_credential[\s\S]*FOR UPDATE/);
  assert.match(sql, /status,\s*allow_overnight/);
  assert.match(sql, /'PAUSED',\s*false/);
  assert.match(sql, /user_id\s*=\s*67731510/);
  assert.match(sql, /owner\s*=\s*'AUTOPILOT'/);
  assert.doesNotMatch(sql, /\bUPDATE\s+strategy\b/i);
  assert.doesNotMatch(sql, /discovery_scorecard/i);
  assert.equal((sql.match(/INSERT INTO strategy/g) || []).length, STRATEGIES.length);
  for (const code of LIQUIDITY_ANCHORS) assert.match(sql, new RegExp(code));
});

test('successful run executes exactly one transaction and never activates strategies', () => {
  const client = fakeClient([['1', '1', '0', '0', '0', '0']]);
  const result = runSeed({ args: VALID_ARGS, client, logger: silentLogger() });
  assert.equal(result.insertCount, 6);
  assert.equal(client.calls.execs.length, 1);
  assert.doesNotMatch(client.calls.execs[0], /status\s*=\s*'ACTIVE'/i);
});

test('database failures are sanitized and do not leak command output', () => {
  const secret = 'super-secret-account-value';
  const client = {
    query() {
      throw new Error(secret);
    },
    exec() {
      throw new Error(secret);
    },
  };
  const logger = silentLogger();
  assert.throws(
    () => runSeed({ args: VALID_ARGS, client, logger }),
    (error) => error.message === 'autopilot seed preflight failed',
  );
  assert.doesNotMatch(logger.messages.join('\n'), new RegExp(secret));
});

test('transaction failures are sanitized and do not leak command output', () => {
  const secret = 'transaction-secret-output';
  const client = fakeClient();
  client.exec = () => {
    throw new Error(secret);
  };
  const logger = silentLogger();
  assert.throws(
    () => runSeed({ args: VALID_ARGS, client, logger }),
    (error) => error.message === 'autopilot seed transaction failed',
  );
  assert.doesNotMatch(logger.messages.join('\n'), new RegExp(secret));
});
