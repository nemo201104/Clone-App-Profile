const test = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const context = { window: {}, crypto: require('node:crypto').webcrypto, setTimeout, clearTimeout };
vm.runInNewContext(fs.readFileSync('module/webroot/bridge.js', 'utf8'), context);
const { command, call } = context.window.cap;
test('allowlisted commands and sibling routing', () => {
  assert.equal(command('clone', ['com.zing.zalo', '10']), 'clone com.zing.zalo 10');
  assert.equal(command('clone', ['com.zing.zalo', '11']), 'clone com.zing.zalo 11');
  assert.equal(command('profile-delete', ['12']), 'profile-delete 12');
});
test('reject arbitrary actions, shell metacharacters and positional injection', () => {
  for (const pkg of ['com.x;id', 'com.x$(id)', 'com.x`id`', 'com.x\nwhoami', '../x', 'com.x -a MAIN']) assert.throws(() => command('clone', [pkg, '10']));
  for (const id of ['all', 'current', '-1', '10;id', '1\n', '01']) assert.throws(() => command('profile-delete', [id]));
  assert.throws(() => command('shell', ['id']));
  assert.throws(() => command('status', ['extra']));
});
test('launcher failure remains partial even with nonzero backend exit', async () => {
  context.window.ksu = { exec(cmd, options, cb) { context.window[cb](2, JSON.stringify({ success: false, errorCode: 'PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED', details: { packageCloned: 'Success', launcherIntegration: 'Failed' } }), ''); } };
  const result = await call('clone', 'com.example.app', '10');
  assert.equal(result.success, false);
  assert.equal(result.details.packageCloned, 'Success');
});

test('missing manager bridge fails before invoking any command', async () => {
  context.window.ksu = undefined;
  await assert.rejects(call('doctor'), /KernelSU/);
});

test('missing binary and permission failures cannot be reported as success', async () => {
  for (const [exit, stderr] of [[127, 'capctl: not found'], [126, 'Permission denied']]) {
    context.window.ksu = { exec(cmd, options, cb) { context.window[cb](exit, '', stderr); } };
    await assert.rejects(call('doctor'));
  }
});

test('framework permission failure retains backend error and operation identity', async () => {
  const response = { success:false, errorCode:'FRAMEWORK_API_FAILED', message:'Permission denied', details:{}, operationId:'permission-test' };
  context.window.ksu = { exec(cmd, options, cb) { context.window[cb](1, JSON.stringify(response), ''); } };
  assert.equal((await call('doctor')).operationId, 'permission-test');
  assert.equal((await call('doctor')).errorCode, 'FRAMEWORK_API_FAILED');
});

test('a contradictory nonzero success response is rejected', async () => {
  context.window.ksu = { exec(cmd, options, cb) { context.window[cb](1, JSON.stringify({success:true, errorCode:'SUCCESS'}), ''); } };
  await assert.rejects(call('doctor'), /Backend exit code conflicts with response/);
});
