'use strict';
const $ = id => document.getElementById(id);
const state = { apps: [], profiles: [], clones: [], icons: new Map(), selected: '', busy: false, createBlocked: true, currentUser: -1 };
function node(tag, text, className) { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if (className) n.className = className; return n; }
function empty(container, text) { container.replaceChildren(node('p', text, 'muted empty')); }
function showResult(r, operation) {
  $('result').hidden = false; $('result').classList.toggle('error', !r.success);
  $('result-title').textContent = r.errorCode === 'PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED' ? 'Clone chưa hoàn tất — launcher cần xử lý' : r.errorCode === 'PARTIAL_SUCCESS_LAUNCHER_REMOVAL_PENDING' ? 'Xóa chưa hoàn tất — launcher đang cập nhật' : r.success ? 'Operation hoàn tất' : 'Operation thất bại';
  const details = r.details || {}; $('result-fields').replaceChildren();
  if (operation === 'clone' || operation === 'launcher-retry' || details.packageCloned !== undefined || r.errorCode === 'PACKAGE_INSTALL_FAILED') {
    const rows = [['Package cloned', details.packageCloned || 'Failed'], ['Launcher integration', details.launcherIntegration || 'Failed'], ['Target profile', details.targetProfile ? `User ${details.targetProfile.userId} · serial ${details.targetProfile.serialNumber}` : $('target').selectedOptions[0]?.textContent || '—'], ['Launcher entry state', details.launcherEntryState || 'NOT_CREATED'], ['Launcher mode', details.launcherMode || 'UNASSIGNED']];
    for (const [label, value] of rows) { const row = node('div', undefined, 'result-row'); row.append(node('span', label), node('strong', value)); $('result-fields').append(row); }
  }
  if (details.packageRemoved !== undefined || details.profileRemoved !== undefined) {
    for (const [label, value] of [['Package/profile removed', String(details.packageRemoved || details.profileRemoved)], ['Launcher entry state', details.launcherEntryState || 'REMOVED']]) {
      const row = node('div', undefined, 'result-row'); row.append(node('span', label), node('strong', value)); $('result-fields').append(row);
    }
  }
  $('result-fields').append(node('p', r.message)); $('result-json').textContent = JSON.stringify(r, null, 2);
  $('result').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}
async function confirmAction(title, description) {
  $('confirm-title').textContent = title; $('confirm-text').textContent = description; $('confirm').returnValue = 'cancel'; $('confirm').showModal();
  return new Promise(resolve => $('confirm').addEventListener('close', () => resolve($('confirm').returnValue === 'confirm'), { once: true }));
}
async function task(label, fn) {
  if (state.busy) return;
  state.busy = true; $('busy').hidden = false; $('busy-text').textContent = label;
  document.querySelectorAll('button, select, input').forEach(el => el.disabled = true);
  try { await fn(); } catch (e) { showResult({ success: false, errorCode: 'WEBUI_ERROR', message: e.message, details: {} }); }
  finally { state.busy = false; $('busy').hidden = true; document.querySelectorAll('button, select, input').forEach(el => el.disabled = false); updateCloneButton(); }
}
function checked(r) { if (!r.success) { showResult(r); throw new Error(r.message); } return r.details; }
function renderApps() {
  const filter = $('search').value.toLocaleLowerCase(); const visible = state.apps.filter(a => `${a.appLabel} ${a.packageName}`.toLocaleLowerCase().includes(filter));
  $('apps').replaceChildren();
  for (const app of visible) {
    const button = node('button', undefined, 'app' + (state.selected === app.packageName ? ' selected' : '')); button.setAttribute('role', 'option'); button.setAttribute('aria-selected', String(state.selected === app.packageName));
    const icon = node('span', app.appLabel.slice(0, 1).toUpperCase(), 'app-icon');
    if (state.icons.has(app.packageName)) { const img = node('img'); img.alt = ''; img.src = state.icons.get(app.packageName); icon.replaceChildren(img); }
    const words = node('span'); words.append(node('strong', app.appLabel), node('small', app.packageName)); button.append(icon, words);
    button.addEventListener('click', () => { state.selected = app.packageName; renderApps(); updateCloneButton(); }); $('apps').append(button);
  }
  if (!visible.length) empty($('apps'), 'Không có app phù hợp.');
}
function updateCloneButton() { $('clone-submit').disabled = state.busy || !state.selected || !$('target').value; $('create-profile').disabled = state.busy || state.createBlocked; }
function renderProfiles() {
  const selected = $('target').value; $('target').replaceChildren(node('option', 'Chọn profile')); $('target').firstChild.value = '';
  $('profile-list').replaceChildren();
  for (const p of state.profiles) {
    const short = p.type.replace('android.os.usertype.', '');
    if (p.type === 'android.os.usertype.profile.CLONE' && p.parentUserId === state.currentUser && p.enabled && !p.partial) { const option = node('option', `${p.name} · User ${p.userId} · ${p.profileClassification || (p.moduleManaged ? 'MODULE_MANAGED' : 'EXTERNAL_GENERIC')}`); option.value = p.userId; $('target').append(option); }
    const card = node('article', undefined, 'item-card'); card.append(node('h3', p.name), node('p', `${short} · User ${p.userId} · serial ${p.serialNumber}`), node('p', `Parent: ${p.parentUserId < 0 ? '—' : p.parentUserId} · ${p.state}`), node('p', `Module Managed: ${p.moduleManaged ? 'Yes' : 'No'} · Cloned: ${p.clonedApps}`));
    if (p.profileClassification) card.append(node('p', p.profileClassification));
    if (p.moduleManaged) { const button = node('button', 'Delete Profile', 'danger'); button.onclick = async () => { if (await confirmAction('Delete Profile', `Xóa ${p.name} (user ${p.userId}, ${short}), ${p.clonedApps} clones và toàn bộ data trong profile?`)) await operate('profile-delete', p.userId); }; card.append(button); }
    $('profile-list').append(card);
  }
  if ([...$('target').options].some(o => o.value === selected)) $('target').value = selected;
}
function renderClones() {
  $('clone-list').replaceChildren();
  for (const c of state.clones) {
    const card = node('article', undefined, 'item-card');card.append(node('h3', c.appLabel), node('p', c.packageName), node('p', `Target: ${c.targetUserId} · serial ${c.targetSerial} · ${c.state}`), node('p', `Package cloned: ${c.packageRemoved ? 'Removed' : c.packageCloned || 'Success'}`), node('p', `Launcher integration: ${c.launcherIntegration || 'Failed'} · ${c.launcherEntryState}`));
    if (c.proxyLabel) card.append(node('p', `Launcher: ${c.proxyLabel}`));
    card.append(node('p', `Launcher mode: ${c.launcherMode || 'UNASSIGNED'} · ${c.profileClassification || 'EXTERNAL_GENERIC'}`));
    if (c.launcherError) card.append(node('p', c.launcherError, 'error-text'));
    const retry = node('button', 'Retry launcher'); retry.onclick = () => operate('launcher-retry', c.packageName, c.targetUserId);
    const remove = node('button', 'Remove Cloned', 'danger'); remove.onclick = async () => { if (await confirmAction('Remove Cloned', `Gỡ ${c.appLabel} khỏi profile ${c.targetUserId}, xóa sandbox và launcher entry tương ứng?`)) await operate('clone-remove', c.packageName, c.targetUserId); };
    if (c.state === 'ACTIVE') card.append(retry);
    if (c.state === 'REMOVING') card.append(node('p', 'Chọn Remove Cloned hoặc Delete Profile để hoàn tất cleanup đang chờ.'));
    card.append(remove);$('clone-list').append(card);
  }
  if (!state.clones.length) empty($('clone-list'), 'Chưa có clone do module quản lý.');
}
async function refresh() {
  const status = checked(await cap.call('status')); state.profiles = status.profiles;
  state.currentUser = status.currentUser; state.createBlocked = status.used >= status.max || status.cloneSupport.state !== 'SUPPORTED';
  $('profiles-count').textContent = `${status.used}/${status.max}`; $('clones-count').textContent = status.cloned;
  $('environment').textContent = `Android ${status.android} / API ${status.sdk} · ${status.kernelSU ? 'KernelSU detected' : 'KernelSU not detected'} · Parent ${status.currentUser}`;
  $('support').textContent = `Clone Profile Support: ${status.cloneSupport.state} · ${status.used >= status.max ? 'Đã đạt giới hạn tổng users' : status.cloneSupport.canAddMoreProfiles ? 'Còn capacity để tạo profile' : 'Capacity API báo giới hạn; framework sẽ kiểm tra khi tạo'}`;
  $('pending').hidden = !status.pending.length; $('pending').textContent = 'Có operation bị gián đoạn. Mở Doctor và hướng dẫn RECOVERY trước khi tạo thêm clone/profile.';
  state.apps = checked(await cap.call('apps')); state.clones = checked(await cap.call('clones'));
  if (window.ksu && typeof window.ksu.getPackagesIcons === 'function') {
    try { for (const i of JSON.parse(window.ksu.getPackagesIcons(JSON.stringify(state.apps.map(a => a.packageName)), 64))) if (/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(i.icon)) state.icons.set(i.packageName, i.icon); } catch (e) { console.info('Optional icon API unavailable:', e.message); }
  }
  renderProfiles(); renderApps(); renderClones(); updateCloneButton();
}
async function operate(op, ...args) { await task('Đang thực hiện ' + op + '…', async () => { const r = await cap.call(op, ...args); await refresh(); showResult(r, op); }); }
document.querySelectorAll('[data-page]').forEach(button => button.onclick = () => {
  document.querySelectorAll('[data-page]').forEach(b => b.classList.toggle('selected', b === button));
  document.querySelectorAll('.page').forEach(p => p.hidden = p.id !== 'page-' + button.dataset.page);
  if (button.dataset.page === 'logs') task('Đang đọc logs…', async () => { const rows = checked(await cap.call('logs')); $('log-list').replaceChildren(); for (const r of rows.reverse()) { const tr = node('tr'); for (const key of ['timestamp', 'action', 'package', 'userId', 'result', 'message']) tr.append(node('td', String(r[key]))); $('log-list').append(tr); } });
});
$('search').oninput = renderApps; $('target').onchange = updateCloneButton;
$('refresh').onclick = () => task('Đang làm mới…', refresh);
$('create-profile').onclick = async () => { if (await confirmAction('Create Profile', 'Tạo và khởi động một profile.CLONE mới cho parent hiện tại?')) await operate('profile-create'); };
$('clone-submit').onclick = async () => { if (await confirmAction('Clone App', `Clone ${state.selected} vào ${$('target').selectedOptions[0].textContent}? Module sẽ kiểm tra launcher rồi tạo proxy entry nếu cần.`)) await operate('clone', state.selected, $('target').value); };
$('reconcile').onclick = () => operate('reconcile'); $('doctor').onclick = () => task('Đang chẩn đoán…', async () => showResult(await cap.call('doctor')));
$('export').onclick = () => task('Đang xuất logs…', async () => showResult(await cap.call('export-log')));
$('update-check').onclick = () => task('Đang kiểm tra update…', async () => showResult(await cap.call('update-check')));
task('Đang tải module…', refresh);
