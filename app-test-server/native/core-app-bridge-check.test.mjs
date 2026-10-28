import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { existsSync } from 'node:fs';

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

for (const mode of ['direct', 'mqtt', 'mqtt-reconnect', 'mqtt-keyring', 'mqtt-keyring-outbox']) {
  const pinned = fileURLToPath(new URL('../../../../.local/keyring-app-pinned/', import.meta.url));
  const python = fileURLToPath(new URL('../../../../.local/keyring-gateway-venv/bin/python', import.meta.url));
  test(`${mode} mode updates only its isolated resource and cleans it up`,
    { skip: (mode === 'mqtt-keyring' || mode === 'mqtt-keyring-outbox') && (!existsSync(pinned) || (mode === 'mqtt-keyring-outbox' && !existsSync(python))) && 'Prepare pinned KEYRING worktree and outbox dependencies' }, async () => {
    let aeName, state = false, removed = false, puts = 0;
    const cse = http.createServer(async (req, res) => {
      const reply = (status, rsc, data) => {
        res.writeHead(status, {'Content-Type':'application/json','X-M2M-RSC':rsc,'X-M2M-RI':req.headers['x-m2m-ri']});
        res.end(JSON.stringify(data));
      };
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = chunks.length ? JSON.parse(Buffer.concat(chunks)) : null;
      if (req.method === 'POST' && req.url === '/Mobius') {
        aeName = body['m2m:ae'].rn;
        return reply(201, '2001', {'m2m:ae':{rn:aeName,ri:'Cabc123',aei:'Cabc123'}});
      }
      if (req.url === `/Mobius/${aeName}` && req.method === 'POST') return reply(201,'2001',{});
      if (req.url === `/Mobius/${aeName}` && req.method === 'DELETE') { removed = true; return reply(200,'2002',{}); }
      if (req.url === `/Mobius/${aeName}` && req.method === 'GET') return removed ? reply(404,'4004',{}) : reply(200,'2000',{'m2m:ae':{rn:aeName,ri:'Cabc123'}});
      if (req.url === `/Mobius/${aeName}/bubbleState` && req.method === 'PUT') { puts++; state = body['pb:binarySwitch'].state; return reply(200,'2004',{}); }
      if (req.url === `/Mobius/${aeName}/bubbleState` && req.method === 'GET') return reply(200,'2000',{'pb:binarySwitch':{rn:'bubbleState',cnd:'org.onem2m.common.moduleclass.binarySwitch',state,lbl:['unobserved']}});
      reply(404,'4004',{});
    });
    await new Promise(resolve => cse.listen(0, '127.0.0.1', resolve));
    try {
      const args = mode === 'mqtt-reconnect' ? [script, '--mqtt', '--mqtt-reconnect'] : mode === 'mqtt-keyring' ? [script, '--mqtt', '--keyring'] : mode === 'mqtt-keyring-outbox' ? [script, '--mqtt', '--keyring-outbox'] : mode === 'mqtt' ? [script, '--mqtt'] : [script];
      const child = spawn(process.execPath, args, {env:{...process.env,APP_CORE_TEST_CSE_URL:`http://127.0.0.1:${cse.address().port}/Mobius`}});
      let output = '';
      child.stdout.on('data', chunk => { output += chunk; });
      child.stderr.on('data', chunk => { output += chunk; });
      const exit = await new Promise((resolve,reject) => { child.once('error',reject); child.once('close',resolve); });
      assert.equal(exit,0,output);
      const result = output.trim().split('\n').map(JSON.parse).at(-1);
      const summary = result.results.find(x => x.bridge);
      assert.equal(summary.update.rsc,'2004');
      assert.equal(summary.stateAfter,mode !== 'mqtt-reconnect');
      assert.equal(summary.observation,'unknown');
      assert.equal(puts,mode === 'mqtt-reconnect' ? 2 : 1);
      assert.equal(removed,true);
      assert.ok(result.results.some(x => x.cleanup === 'verified'));
      if (mode === 'mqtt' || mode === 'mqtt-keyring') {
        assert.deepEqual(summary.mqtt,{topic:'privacy-bubble/gateway/app-isolated/telemetry',qos:1,retained:false,duplicate:'DUPLICATE',updateCount:1});
        if (mode === 'mqtt-keyring') assert.deepEqual(summary.keyring,{nullMeasurementsSkipped:true,duplicateSkipped:true,reopenPreserved:true,wireSequences:[0,1]});
      }
      else if (mode === 'mqtt-keyring-outbox') {
        assert.deepEqual(summary.outbox,{offlineRetained:true,corePubackRemoved:true,otherObservationPending:true,observationsQueued:1,brokerIngress:1,mtls:true,missingClientCertRejected:true});
      } else if (mode === 'mqtt-reconnect') {
        assert.deepEqual(summary.mqtt,{topic:'privacy-bubble/gateway/app-isolated/telemetry',qos:1,retained:false,duplicate:'DUPLICATE',updateCount:2,reconnected:true});
        assert.deepEqual(summary.reconnectUpdate,{status:200,rsc:'2004',correlated:true});
      }
      else assert.equal(summary.mqtt,undefined);
    } finally { await new Promise(resolve => cse.close(resolve)); }
  });
}
