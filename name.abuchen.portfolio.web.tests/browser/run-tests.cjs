'use strict';

const { readdirSync } = require('node:fs');
const { spawn } = require('node:child_process');
const { join } = require('node:path');

const directory = __dirname;
const tests = readdirSync(directory).filter(file => file.endsWith('.test.cjs')).sort();
const timeoutMs = Number(process.env.PP_WEB_BROWSER_TEST_TIMEOUT_MS || 120000);

function run(test) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [join(directory, test)], {
      stdio: 'inherit', env: process.env, detached: process.platform !== 'win32'
    });
    let timedOut = false;
    let forceKill;
    const timeout = setTimeout(() => {
      timedOut = true;
      console.error(`${test} exceeded the ${timeoutMs}ms test timeout; terminating its process group.`);
      try { process.kill(process.platform === 'win32' ? child.pid : -child.pid, 'SIGTERM'); } catch { }
      forceKill = setTimeout(() => {
        try { process.kill(process.platform === 'win32' ? child.pid : -child.pid, 'SIGKILL'); } catch { }
      }, 10000);
    }, timeoutMs);
    child.once('error', error => {
      clearTimeout(timeout);
      clearTimeout(forceKill);
      reject(error);
    });
    child.once('close', (code, signal) => {
      clearTimeout(timeout);
      clearTimeout(forceKill);
      if (timedOut) reject(new Error(`${test} timed out after ${timeoutMs}ms`));
      else if (code !== 0) reject(new Error(`${test} exited ${code ?? signal}`));
      else resolve();
    });
  });
}

(async () => {
  for (const test of tests) {
    console.log(`\n=== ${test} ===`);
    await run(test);
  }
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
