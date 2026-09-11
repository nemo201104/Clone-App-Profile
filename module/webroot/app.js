'use strict';
const $ = id => document.getElementById(id);
const state = { apps: [], profiles: [], clones: [], icons: new Map(), selected: '', busy: false, createBlocked: true, currentUser: -1 };
// Presentation labels only. Backend/registry enums remain unchanged.
function display(value) {
  return ({ MODULE_MANAGED: 'Managed', EXTERNAL_OEM: 'External (OEM)', EXTERNAL_GENERIC: 'External',
    RUNNING_UNLOCKED: 'Running', RUNNING_LOCKED: 'Running (locked)', STOPPED: 'Stopped',
    NATIVE: 'Native', PROXY: 'Proxy', SUPPORTED: 'Supported', UNSUPPORTED: 'Unsupported', UNKNOWN: 'Unknown',
    ACTIVE: 'Active', MISSING: 'Missing', REMOVING: 'Removing', DELETING: 'Deleting', FAILED: 'Failed',
    READY_NATIVE: 'Ready (native)', READY_PROXY: 'Ready (proxy)', UNASSIGNED: 'Unassigned', NOT_CREATED: 'Not created',
    PENDING_REMOVAL: 'Removal pending', REMOVED: 'Removed', PENDING: 'Pending',
    INSTALLING_PROXY: 'Installing proxy', PROBING_PROXY: 'Verifying proxy', TRANSITIONING_TO_NATIVE: 'Switching to native'
  })[value] || value;
}
function node(tag, text, className) { const n = document.createElement(tag); if (text !== undefined) n.textContent = text; if (className) n.className = className; return n; }
function empty(container, text) { container.replaceChildren(node('p', text, 'muted empty')); }
function showResult(r, operation) {
  $('result').hidden = false; $('result').classList.toggle('error', !r.success);
  $('result-title').textContent = r.errorCode === 'PARTIAL_SUCCESS_LAUNCHER_INTEGRATION_FAILED' ? 'Partial Success — launcher integration failed' : r.errorCode === 'PARTIAL_SUCCESS_LAUNCHER_REMOVAL_PENDING' ? 'Partial Success — launcher removal pending' : r.success ? 'Success' : 'Failed';
  const details = r.details || {}; $('result-fields').replaceChildren();
  if (operation === 'clone' || operation === 'launcher-retry' || details.packageCloned !== undefined || r.errorCode === 'PACKAGE_INSTALL_FAILED') {
    const rows = [['Package cloned', details.packageCloned || 'Failed'], ['Launcher integration', details.launcherIntegration || 'Failed'], ['Target profile', details.targetProfile ? `User ${details.targetProfile.userId} · serial ${details.targetProfile.serialNumber}` : $('target').selectedOptions[0]?.textContent || '—'], ['Launcher entry state', details.launcherEntryState || 'NOT_CREATED'], ['Launcher mode', details.launcherMode || 'UNASSIGNED']];
    for (const [label, value] of rows) { const row = node('div', undefined, 'result-row'); row.append(node('span', label), node('strong', display(value))); $('result-fields').append(row); }
  }
  if (details.packageRemoved !== undefined || details.profileRemoved !== undefined) {
    for (const [label, value] of [['Package/profile removed', details.packageRemoved || details.profileRemoved ? 'Yes' : 'No'], ['Launcher entry state', details.launcherEntryState || 'REMOVED']]) {
      const row = node('div', undefined, 'result-row'); row.append(node('span', label), node('strong', display(value))); $('result-fields').append(row);
    }
  }
  $('result-fields').append(node('p', r.message)); $('result-json').textContent = JSON.stringify(r, null, 2);
  $('result').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}
async function confirmAction(title, description, destructive = false) {
  $('confirm-yes').classList.toggle('primary', !destructive); $('confirm-yes').classList.toggle('danger-fill', destructive);
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
  if (!visible.length) empty($('apps'), 'No matching apps.');
}
function updateCloneButton() { $('clone-submit').disabled = state.busy || !state.selected || !$('target').value; $('create-profile').disabled = state.busy || state.createBlocked; }
function renderProfiles() {
  const selected = $('target').value; $('target').replaceChildren(node('option', 'Select a profile')); $('target').firstChild.value = '';
  $('profile-list').replaceChildren();
  for (const p of state.profiles) {
    const short = p.type.replace('android.os.usertype.', '');
    if (p.type === 'android.os.usertype.profile.CLONE' && p.parentUserId === state.currentUser && p.enabled && !p.partial) { const option = node('option', `${p.name} · User ${p.userId} · ${display(p.profileClassification || (p.moduleManaged ? 'MODULE_MANAGED' : 'EXTERNAL_GENERIC'))}`); option.value = p.userId; $('target').append(option); }
    const card = node('article', undefined, 'item-card'); card.append(node('h3', p.name), node('p', `${short} · User ${p.userId} · serial ${p.serialNumber}`), node('p', `Parent: ${p.parentUserId < 0 ? '—' : p.parentUserId} · Status: ${display(p.state)}`), node('p', `Managed: ${p.moduleManaged ? 'Yes' : 'No'} · Cloned Apps: ${p.clonedApps}`));
    if (p.profileClassification) card.append(node('p', display(p.profileClassification)));
    if (p.moduleManaged) { const button = node('button', 'Delete Profile', 'danger'); button.onclick = async () => { if (await confirmAction('Delete Profile', `Delete ${p.name} (user ${p.userId}), including ${p.clonedApps} cloned apps and all data in this profile?`, true)) await operate('profile-delete', p.userId); }; card.append(button); }
    $('profile-list').append(card);
  }
  if ([...$('target').options].some(o => o.value === selected)) $('target').value = selected;
}
function renderClones() {
  $('clone-list').replaceChildren();
  for (const c of state.clones) {
    const card = node('article', undefined, 'item-card');card.append(node('h3', c.appLabel), node('p', c.packageName), node('p', `Target Profile: ${c.targetUserId} · serial ${c.targetSerial} · ${display(c.state)}`), node('p', `Package cloned: ${c.packageRemoved ? 'Removed' : c.packageCloned || 'Success'}`), node('p', `Launcher integration: ${c.launcherIntegration || 'Failed'} · ${display(c.launcherEntryState)}`));
    if (c.proxyLabel) card.append(node('p', `Launcher: ${c.proxyLabel}`));
    card.append(node('p', `Launcher mode: ${display(c.launcherMode || 'UNASSIGNED')} · ${display(c.profileClassification || 'EXTERNAL_GENERIC')}`));
    if (c.launcherError) card.append(node('p', c.launcherError, 'error-text'));
    const retry = node('button', 'Retry launcher'); retry.onclick = () => operate('launcher-retry', c.packageName, c.targetUserId);
    const remove = node('button', 'Remove Cloned', 'danger'); remove.onclick = async () => { if (await confirmAction('Remove Cloned', `Remove ${c.appLabel} from profile ${c.targetUserId}, including its sandbox and launcher entry?`, true)) await operate('clone-remove', c.packageName, c.targetUserId); };
    if (c.state === 'ACTIVE') card.append(retry);
    if (c.state === 'REMOVING') card.append(node('p', 'Use Remove Cloned or Delete Profile to finish the pending cleanup.'));
    card.append(remove);$('clone-list').append(card);
  }
  if (!state.clones.length) empty($('clone-list'), 'No cloned apps managed by this module yet.');
}
async function refresh() {
  const status = checked(await cap.call('status')); state.profiles = status.profiles;
  state.currentUser = status.currentUser; state.createBlocked = status.used >= status.max || status.cloneSupport.state !== 'SUPPORTED';
  $('profiles-count').textContent = `${status.used}/${status.max}`; $('clones-count').textContent = status.cloned;
  $('environment').textContent = `Android ${status.android} / API ${status.sdk} · ${status.kernelSU ? 'KernelSU detected' : 'KernelSU not detected'} · Parent ${status.currentUser}`;
  $('support').textContent = `Clone Profile Support: ${display(status.cloneSupport.state)} · ${status.used >= status.max ? 'User limit reached' : status.cloneSupport.canAddMoreProfiles ? 'Space available for another profile' : 'Reported capacity is limited; Android will check again during creation'}`;
  $('pending').hidden = !status.pending.length; $('pending').textContent = 'An operation was interrupted. Check Diagnostics and the recovery guide before creating another clone or profile.';
  state.apps = checked(await cap.call('apps')); state.clones = checked(await cap.call('clones'));
  if (window.ksu && typeof window.ksu.getPackagesIcons === 'function') {
    try { for (const i of JSON.parse(window.ksu.getPackagesIcons(JSON.stringify(state.apps.map(a => a.packageName)), 64))) if (/^data:image\/png;base64,[A-Za-z0-9+/=]+$/.test(i.icon)) state.icons.set(i.packageName, i.icon); } catch (e) { console.info('Optional icon API unavailable:', e.message); }
  }
  renderProfiles(); renderApps(); renderClones(); updateCloneButton();
}
async function operate(op, ...args) { await task('Working…', async () => { const r = await cap.call(op, ...args); await refresh(); showResult(r, op); }); }
document.querySelectorAll('[data-page]').forEach(button => button.onclick = () => {
  document.querySelectorAll('[data-page]').forEach(b => b.classList.toggle('selected', b === button));
  document.querySelectorAll('.page').forEach(p => p.hidden = p.id !== 'page-' + button.dataset.page);
  if (button.dataset.page === 'logs') task('Loading logs…', async () => { const rows = checked(await cap.call('logs')); $('log-list').replaceChildren(); for (const r of rows.reverse()) { const tr = node('tr'); for (const key of ['timestamp', 'action', 'package', 'userId', 'result', 'message']) tr.append(node('td', String(r[key]))); $('log-list').append(tr); } });
});
$('search').oninput = renderApps; $('target').onchange = updateCloneButton;
$('refresh').onclick = () => task('Refreshing…', refresh);
$('create-profile').onclick = async () => { if (await confirmAction('Create Profile', 'Create and start a new clone profile for the current parent user?')) await operate('profile-create'); };
$('clone-submit').onclick = async () => { if (await confirmAction('Clone App', `Clone ${state.selected} into ${$('target').selectedOptions[0].textContent}? Launcher integration will be checked before creating a proxy if needed.`)) await operate('clone', state.selected, $('target').value); };
$('reconcile').onclick = () => operate('reconcile'); $('doctor').onclick = () => task('Running diagnostics…', async () => showResult(await cap.call('doctor')));
$('export').onclick = () => task('Saving logs…', async () => showResult(await cap.call('export-log')));
$('update-check').onclick = () => task('Checking for updates…', async () => showResult(await cap.call('update-check')));
task('Loading module…', refresh);
