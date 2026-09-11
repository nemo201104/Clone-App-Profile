// Optional real-browser UI checks: npm install --prefix .cache/webtest playwright
const { chromium } = require('../.cache/webtest/node_modules/playwright');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve('module/webroot');
const server = http.createServer((req, res) => {
  const file = path.resolve(root, '.' + (req.url === '/' ? '/index.html' : req.url));
  if (!file.startsWith(root + path.sep)) { res.writeHead(403).end(); return; }
  try { res.setHeader('Content-Type', ({'.html':'text/html','.js':'text/javascript','.css':'text/css'})[path.extname(file)] || 'application/octet-stream'); res.end(fs.readFileSync(file)); }
  catch { res.writeHead(404).end(); }
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || (process.platform === 'win32' ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : undefined) });
  try {
    for (const colorScheme of ['light', 'dark']) {
      const page = await browser.newPage({ viewport: { width: 390, height: 844 }, colorScheme });
      const errors = []; page.on('pageerror', e => errors.push(e.message));
      await page.addInitScript(() => {
        const profiles = [
          { userId: 0, serialNumber: 0, name: 'Owner', type: 'android.os.usertype.full.SYSTEM', parentUserId: -1, state: 'RUNNING_UNLOCKED', enabled: true, moduleManaged: false, clonedApps: 0 },
          { userId: 10, serialNumber: 42, name: 'Clone 1', type: 'android.os.usertype.profile.CLONE', parentUserId: 0, state: 'RUNNING_UNLOCKED', enabled: true, moduleManaged: true, clonedApps: 1 },
          { userId: 11, serialNumber: 43, name: 'Clone 2', type: 'android.os.usertype.profile.CLONE', parentUserId: 0, state: 'RUNNING_UNLOCKED', enabled: true, moduleManaged: true, clonedApps: 0 }
        ];
        const clone = { packageName:'com.example.app', appLabel:'Example <img src=x onerror=alert(1)>', targetUserId:10, targetSerial:42, state:'ACTIVE', packageCloned:'Success', launcherIntegration:'Failed', launcherEntryState:'FAILED', launcherError:'Broker unavailable' };
        window.calls = [];
        window.ksu = { exec(command, options, callback) {
          const tokens = command.split(' ').slice(1), op = tokens[0]; window.calls.push(tokens);
          const details = {
            status: { used:3, max:4, cloned:1, android:'16', sdk:36, currentUser:0, kernelSU:true, cloneSupport:{ state:'SUPPORTED', canAddMoreProfiles:true }, profiles, pending:[] },
            apps:[{ packageName:'com.example.app', appLabel:'Example <img src=x onerror=alert(1)>' }],
            clones:[clone], logs:[]
          }[op] || {};
          const result = op === 'clone' ? { success:false, errorCode:'PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED', message:'Package cloned; launcher integration failed', details:{ packageCloned:'Success', launcherIntegration:'Failed', targetProfile:{userId:10,serialNumber:42}, launcherEntryState:'FAILED' } } : {success:true,errorCode:'SUCCESS',message:'Completed',details};
          setTimeout(() => window[callback](result.success ? 0 : 2, JSON.stringify(result), ''), 15);
        } };
      });
      await page.goto(`http://127.0.0.1:${server.address().port}/`);
      await page.locator('#apps .app').waitFor();
      await page.locator('#busy').waitFor({state:'hidden'});
      assert.equal(await page.locator('#apps img').count(), 0, 'untrusted labels cannot create DOM');
      assert.equal(await page.locator('#target option').count(), 3, 'owner excluded from clone target');
      await page.selectOption('#target', '10'); await page.locator('#apps .app').click(); await page.locator('#clone-submit').click();
      await page.locator('#confirm-yes').click();
      await page.locator('#result-title').filter({hasText:'Clone chưa hoàn tất'}).waitFor();
      assert.match(await page.locator('#result-fields').textContent(), /Package clonedSuccessLauncher integrationFailedTarget profileUser 10/);
      await page.evaluate(() => showResult({success:false,errorCode:'PACKAGE_NOT_FOUND',message:'Source package was removed',details:{}}, 'clone'));
      assert.match(await page.locator('#result-fields').textContent(), /Package clonedFailedLauncher integrationFailedTarget profileClone 1/);
      assert.match(await page.locator('#result-fields').textContent(), /Launcher entry stateNOT_CREATED/);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'mobile layout does not overflow');
      await page.locator('[data-page="profiles"]').click();
      assert.equal(await page.locator('#profile-list .danger').count(), 2, 'only module-owned profiles deletable');
      await page.locator('#profile-list .danger').first().click(); await page.locator('#confirm button[value="cancel"]').click();
      assert.equal(await page.evaluate(() => calls.some(c => c[0] === 'profile-delete')), false, 'cancel never mutates');
      fs.mkdirSync('build/screenshots',{recursive:true}); await page.screenshot({path:`build/screenshots/webui-${colorScheme}.png`,fullPage:true});
      assert.deepEqual(errors, []); await page.close();
    }
    console.log('Browser checks passed: mobile light/dark, partial success, unsafe labels, target/ownership filters and cancel.');
  } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
