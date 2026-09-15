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
  try { res.setHeader('Content-Type', ({'.html':'text/html','.js':'text/javascript','.css':'text/css','.png':'image/png'})[path.extname(file)] || 'application/octet-stream'); res.end(fs.readFileSync(file)); }
  catch { res.writeHead(404).end(); }
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH || (process.platform === 'win32' ? 'C:/Program Files/Google/Chrome/Application/chrome.exe' : undefined) });
  try {
    for (const colorScheme of ['light', 'dark']) for (const width of [320, 390, 960]) {
      const page = await browser.newPage({ viewport: { width, height: 844 }, colorScheme });
      const errors = [], requests = []; page.on('pageerror', e => errors.push(e.message));
      page.on('request', request => requests.push(request.url()));
      await page.addInitScript(() => {
        const profiles = [
          { userId: 0, serialNumber: 0, name: 'Owner', type: 'android.os.usertype.full.SYSTEM', parentUserId: -1, state: 'RUNNING_UNLOCKED', enabled: true, moduleManaged: false, clonedApps: 0 },
          { userId: 10, serialNumber: 42, name: 'Clone 1', type: 'android.os.usertype.profile.CLONE', parentUserId: 0, state: 'RUNNING_UNLOCKED', enabled: true, moduleManaged: true, clonedApps: 1 },
          { userId: 11, serialNumber: 43, name: 'OEM Clone', type: 'android.os.usertype.profile.CLONE', parentUserId: 0, state: 'RUNNING_UNLOCKED', enabled: true, moduleManaged: false, profileClassification: 'EXTERNAL_OEM', clonedApps: 1 }
        ];
        const clone = { packageName:'com.example.app', appLabel:'Example <img src=x onerror=alert(1)>', targetUserId:10, targetSerial:42, state:'ACTIVE', packageCloned:'Success', launcherIntegration:'Failed', launcherEntryState:'FAILED', launcherMode:'PROXY', profileClassification:'MODULE_MANAGED', launcherError:'Broker unavailable' };
        const native = { ...clone, targetUserId:11, targetSerial:43, launcherMode:'NATIVE', profileClassification:'EXTERNAL_OEM', launcherIntegration:'Success', launcherEntryState:'READY_NATIVE', launcherError:'' };
        window.calls = [];
        window.ksu = { exec(command, options, callback) {
          const tokens = command.split(' ').slice(1), op = tokens[0]; window.calls.push(tokens);
          const details = {
            status: { used:3, max:4, cloned:1, android:'16', sdk:36, currentUser:0, kernelSU:true, cloneSupport:{ state:'SUPPORTED', canAddMoreProfiles:true }, profiles, pending:[] },
            apps:[{ packageName:'com.example.app', appLabel:'Example <img src=x onerror=alert(1)>' }],
            clones:[clone,native], logs:[]
          }[op] || {};
          const result = op === 'clone' ? { success:false, errorCode:'PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED', message:'Package cloned; launcher integration failed', details:{ packageCloned:'Success', launcherIntegration:'Failed', targetProfile:{userId:10,serialNumber:42}, launcherEntryState:'FAILED' } } : {success:true,errorCode:'SUCCESS',message:'Completed',details};
          setTimeout(() => window[callback](result.success ? 0 : 2, JSON.stringify(result), ''), 15);
        } };
      });
      await page.goto(`http://127.0.0.1:${server.address().port}/`);
      await page.locator('#apps .app').waitFor();
      await page.locator('#busy').waitFor({state:'hidden'});
      assert.equal(await page.locator('html').getAttribute('lang'), 'en');
      assert.match(await page.locator('.brand-title').innerText(), /Author: nemoforge/);
      assert.equal(await page.locator('.brand-title .tag').innerText(), 'v1.0.2');
      assert.equal(await page.locator('footer a').getAttribute('href'), 'https://nemoforge.github.io');
      assert.equal(await page.locator('img.mark').evaluate(img => img.complete && img.naturalWidth === 500 && img.naturalHeight === 500), true, 'source logo loads');
      assert.equal(await page.locator('html').evaluate(el => getComputedStyle(el).getPropertyValue('--color-primary').trim()), '#0E60E2');
      assert(requests.every(url => url.startsWith(`http://127.0.0.1:${server.address().port}/`)), 'WebUI branding makes no Internet requests');
      assert(!requests.some(url => url.endsWith('/banner.png')), 'Manager reads its local banner outside WebUI networking');
      const logo = await page.locator('.mark').boundingBox(), title = await page.locator('.brand-title').boundingBox();
      assert(logo.x + logo.width <= title.x && title.x + title.width <= width, 'logo and title do not overlap');
      const luminance = color => color.match(/[\d.]+/g).slice(0, 3).map(Number).map(v => v / 255).map(v => v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4).reduce((s, v, i) => s + v * [.2126, .7152, .0722][i], 0);
      const contrast = (a, b) => (Math.max(luminance(a), luminance(b)) + .05) / (Math.min(luminance(a), luminance(b)) + .05);
      const colors = locator => locator.evaluate(el => { const c = getComputedStyle(el); return { fg:c.color, bg:c.backgroundColor, outline:c.outlineColor }; });
      const selected = await colors(page.locator('nav button.selected'));
      assert.equal(selected.bg, 'rgb(14, 96, 226)'); assert(contrast(selected.fg, selected.bg) >= 4.5, 'selected text contrast');
      const link = await colors(page.locator('footer a')), body = await colors(page.locator('html'));
      assert(contrast(link.fg, body.bg) >= 4.5, 'link text contrast');
      await page.locator('#search').focus();
      const focus = await colors(page.locator('#search'));
      assert(contrast(focus.outline, body.bg) >= 3, 'visible focus ring');
      assert.equal(await page.locator('#apps img').count(), 0, 'untrusted labels cannot create DOM');
      assert.equal(await page.locator('#target option').count(), 3, 'owner excluded from clone target');
      await page.selectOption('#target', '10'); await page.locator('#apps .app').click(); await page.locator('#clone-submit').click();
      const primary = await colors(page.locator('#confirm-yes')); assert(contrast(primary.fg, primary.bg) >= 4.5, 'primary text contrast');
      await page.locator('#confirm-yes').click();
      await page.locator('#result-title').filter({hasText:'Partial Success'}).waitFor();
      assert.match(await page.locator('#result-fields').textContent(), /Package clonedSuccessLauncher integrationFailedTarget profileUser 10/);
      await page.evaluate(() => showResult({success:false,errorCode:'PACKAGE_NOT_FOUND',message:'Source package was removed',details:{}}, 'clone'));
      assert.match(await page.locator('#result-fields').textContent(), /Package clonedFailedLauncher integrationFailedTarget profileClone 1/);
      assert.match(await page.locator('#result-fields').textContent(), /Launcher entry stateNot created/);
      await page.evaluate(() => showResult({success:false,errorCode:'PARTIAL_SUCCESS_LAUNCHER_REMOVAL_PENDING',message:'Retry removal',details:{packageRemoved:true,launcherEntryState:'PENDING_REMOVAL'}}));
      assert.match(await page.locator('#result-fields').textContent(), /Package\/profile removedYesLauncher entry stateRemoval pending/);
      assert.match(await page.locator('#clone-list').textContent(), /Launcher mode: Proxy · Managed/);
      assert.match(await page.locator('#clone-list').textContent(), /Launcher mode: Native · External \(OEM\)/);
      assert.match(await page.locator('#target').textContent(), /OEM Clone · User 11 · External \(OEM\)/);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'mobile layout does not overflow');
      await page.locator('[data-page="profiles"]').click();
      assert.equal(await page.locator('#profile-list .danger').count(), 1, 'external OEM profile cannot be deleted');
      await page.locator('#profile-list .danger').first().click();
      assert.equal(await page.locator('#confirm-yes').getAttribute('class'), 'danger-fill', 'destructive confirmation never primary blue');
      const danger = await colors(page.locator('#confirm-yes')); assert(contrast(danger.fg, danger.bg) >= 4.5, 'danger text contrast');
      assert(+danger.bg.match(/\d+/g)[0] > +danger.bg.match(/\d+/g)[2], 'destructive action stays red');
      await page.locator('#confirm button[value="cancel"]').click();
      assert.equal(await page.evaluate(() => calls.some(c => c[0] === 'profile-delete')), false, 'cancel never mutates');
      assert.doesNotMatch(await page.locator('body').innerText(), /[\u0102\u0103\u0110\u0111\u01a0\u01a1\u01af\u01b0\u1ea0-\u1ef9\u4e00-\u9fff]/, 'English module copy');
      await page.locator('[data-page="clone"]').click();
      await page.mouse.move(0, 0);
      await page.waitForTimeout(200); // Capture settled CSS transitions.
      fs.mkdirSync('build/screenshots',{recursive:true}); await page.screenshot({path:`build/screenshots/webui-${colorScheme}-${width}.png`,fullPage:true});
      assert.deepEqual(errors, []); await page.close();
    }
    console.log('Browser checks passed: 320/390/960px light/dark, logo, blue tokens, contrast/focus, red destructive confirmation, English copy and existing UI flows.');
  } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
