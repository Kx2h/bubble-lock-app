import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { checkKeyringSource, keyringProjection } from './keyring-projection.mjs';

const path = fileURLToPath(new URL('../../../../.local/keyring-app-pinned/', import.meta.url));

test('KEYRING mode rejects replay of fabricated reconnect projections', () => {
  const script = fileURLToPath(new URL('./core-app-bridge-check.mjs', import.meta.url));
  const result = spawnSync(process.execPath, [script, '--mqtt', '--keyring', '--mqtt-reconnect'], { encoding: 'utf8' });
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Usage/);
  assert.doesNotMatch(result.stdout, /testAeName/);
});

// This integration test deliberately uses the separately pinned team checkout.
// Other server tests remain usable before that checkout is prepared.
test('pinned KEYRING validator and durable projection enforce nullable measurements',
  { skip: !existsSync(path) && 'Prepare the pinned KEYRING worktree; see native README' }, async () => {
    const source = await checkKeyringSource();
    const result = await keyringProjection(source, 'app-test-isolated', 'Csynthetic');
    assert.equal(result.nullMeasurementsSkipped, true);
    assert.equal(result.duplicateSkipped, true);
    assert.equal(result.reopenPreserved, true);
    assert.deepEqual(result.wireSequences, [0, 1]);
    assert.equal(result.telemetry.sequence, 0);
    assert.equal(result.telemetry.firmwareSequence, 2);
    assert.equal(result.telemetry.state, true);
  });
