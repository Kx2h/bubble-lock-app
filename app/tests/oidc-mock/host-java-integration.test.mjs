import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, mkdir, readFile, rm } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import net from 'node:net';
import { createMockIdp } from './server.mjs';
const run = promisify(execFile);
const dir = dirname(fileURLToPath(import.meta.url));
const app = resolve(dir, '../..');
const src = resolve(app, 'app/src/main/java/org/privacybubble/app/auth');
const javaHome = process.env.HOST_JDK_HOME;
const jar = process.env.NIMBUS_JAR;
if (!javaHome || !jar) throw new Error('HOST_JDK_HOME and NIMBUS_JAR are required; see README');
async function portNumber() {
  const s = net.createServer(); await new Promise(r => s.listen(0,'127.0.0.1',r));
  const port = s.address().port; await new Promise(r => s.close(r)); return port;
}

test('Java OidcProvider and IdTokenVerifier use verified HTTPS for mock code exchange', async () => {
  await mkdir(resolve(app,'build'), {recursive:true});
  const temp = await mkdtemp(resolve(app,'build/oidc-java-'));
  let server;
  try {
    execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-keyout','server.key','-out','server.pem','-subj','/CN=localhost','-addext','subjectAltName=DNS:localhost'],{cwd:temp,stdio:'ignore'});
    const [cert,key] = await Promise.all(['server.pem','server.key'].map(p => readFile(resolve(temp,p))));
    await run(resolve(javaHome,'bin/keytool'), ['-importcert','-noprompt','-alias','mock','-file',resolve(temp,'server.pem'),'-keystore',resolve(temp,'test.p12'),'-storetype','PKCS12','-storepass','mock-password'], {timeout:15000});
    await run(resolve(javaHome,'bin/javac'), ['-cp',jar,'-d',temp,resolve(src,'OidcProvider.java'),resolve(src,'IdTokenVerifier.java'),resolve(src,'OidcRedirect.java'),resolve(dir,'HostJavaIntegration.java')], {timeout:30000});
    const port = await portNumber(), issuer = `https://localhost:${port}`;
    server = createMockIdp({issuer,cert,key});
    await new Promise((r,j) => server.once('error',j).listen(port,'127.0.0.1',r));
    const main = 'org.privacybubble.app.auth.HostJavaIntegration';
    const classpath = `${temp}:${jar}`;
    const untrusted = await run(resolve(javaHome,'bin/java'), ['-cp',classpath,main,issuer,'untrusted'], {timeout:20000});
    assert.match(untrusted.stdout,/untrusted TLS rejected/);
    const trusted = await run(resolve(javaHome,'bin/java'), [`-Djavax.net.ssl.trustStore=${resolve(temp,'test.p12')}`,'-Djavax.net.ssl.trustStoreType=PKCS12','-Djavax.net.ssl.trustStorePassword=mock-password','-cp',classpath,main,issuer,'trusted'], {timeout:20000});
    assert.match(trusted.stdout,/Java ID token verification and wrong nonce rejection/);
    await new Promise(r => server.close(r));
    server = createMockIdp({issuer,cert,key,now:() => Math.floor(Date.now()/1000) - 240});
    await new Promise((r,j) => server.once('error',j).listen(port,'127.0.0.1',r));
    const expired = await run(resolve(javaHome,'bin/java'), [`-Djavax.net.ssl.trustStore=${resolve(temp,'test.p12')}`,'-Djavax.net.ssl.trustStoreType=PKCS12','-Djavax.net.ssl.trustStorePassword=mock-password','-cp',classpath,main,issuer,'expired'], {timeout:20000});
    assert.match(expired.stdout,/stale caller time cannot revive expired ID token/);
    for (const mode of ['future-iat', 'future-nbf']) {
      await new Promise(r => server.close(r));
      server = createMockIdp({issuer,cert,key,...(mode === 'future-iat' ? {now:() => Math.floor(Date.now()/1000) + 240} : {testNbfOffsetSeconds:90})});
      await new Promise((r,j) => server.once('error',j).listen(port,'127.0.0.1',r));
      const result = await run(resolve(javaHome,'bin/java'), [`-Djavax.net.ssl.trustStore=${resolve(temp,'test.p12')}`,'-Djavax.net.ssl.trustStoreType=PKCS12','-Djavax.net.ssl.trustStorePassword=mock-password','-cp',classpath,main,issuer,mode], {timeout:20000});
      assert.match(result.stdout,/future caller time cannot accept premature ID token/);
    }
  } finally {
    if (server?.listening) await new Promise(r => server.close(r));
    await rm(temp,{recursive:true,force:true});
  }
});
