import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = fileURLToPath(new URL('./core-app-bridge-check.mjs', import.meta.url));
for (const mode of ['lost-response', 'wrong-identity']) {
  test(`uncertain AE creation ${mode} never deletes a resource`, async () => {
    const calls = [];
    const cse = http.createServer((req,res) => {
      calls.push(req.method);
      if (mode === 'lost-response') return req.socket.destroy();
      res.writeHead(201, {'Content-Type':'application/json','X-M2M-RSC':'2001','X-M2M-RI':req.headers['x-m2m-ri']});
      res.end(JSON.stringify({'m2m:ae':{rn:'not-the-requested-name',ri:'Ctest',aei:'Ctest'}}));
    });
    await new Promise(resolve => cse.listen(0, '127.0.0.1', resolve));
    try {
      const child = spawn(process.execPath, [script], {env:{...process.env,APP_CORE_TEST_CSE_URL:`http://127.0.0.1:${cse.address().port}/Mobius`}});
      let output = '';
      child.stdout.on('data', chunk => { output += chunk; });
      child.stderr.on('data', chunk => { output += chunk; });
      const exit = await new Promise((resolve,reject) => { child.once('error',reject); child.once('close',resolve); });
      assert.notEqual(exit,0);
      assert.ok(calls.includes('POST'));
      assert.ok(!calls.includes('DELETE'));
      const lines = output.trim().split('\n').map(JSON.parse);
      assert.match(lines[0].testAeName, /^app-bridge-[a-f0-9]{16}$/);
      assert.equal(lines.at(-1).aeName, lines[0].testAeName);
      assert.ok(lines.at(-1).results.some(x => x.cleanup === 'manual-review'));
    } finally { await new Promise(resolve => cse.close(resolve)); }
  });
}
