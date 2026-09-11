'use strict';
/* KernelSU-Next WebViewInterface.exec(cmd, options, callback), verified against
   b93da6492492eee898da06828df86f8a3d5354be. Only allowlisted capctl argv cross this bridge. */
(() => {
  const one = new Set(['status', 'profiles', 'apps', 'clones', 'logs', 'doctor', 'profile-create', 'reconcile', 'update-check', 'export-log']);
  const pair = new Set(['clone', 'clone-remove', 'launcher-retry']);
  function command(op, args) {
    if (one.has(op) && args.length === 0) return op;
    if (op === 'profile-delete' && args.length === 1 && /^(0|[1-9][0-9]{0,8})$/.test(args[0])) return `${op} ${args[0]}`;
    if (pair.has(op) && args.length === 2 && /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(args[0]) && args[0].length <= 220 && /^(0|[1-9][0-9]{0,8})$/.test(args[1])) return `${op} ${args[0]} ${args[1]}`;
    throw new Error('Invalid operation or arguments');
  }
  async function call(op, ...args) {
    const cmd = '/data/adb/modules/clone_app_profile/bin/capctl ' + command(op, args.map(String));
    if (!window.ksu || typeof window.ksu.exec !== 'function') throw new Error('Mở WebUI trong KernelSU-Next sau khi cài module.');
    return new Promise((resolve, reject) => {
      const callback = 'cap_cb_' + crypto.getRandomValues(new Uint32Array(2)).join('_');
      const timer = setTimeout(() => { delete window[callback]; reject(new Error('Operation chưa phản hồi. Kiểm tra Doctor trước khi thử lại; lệnh trên thiết bị có thể vẫn đang chạy.')); }, 240000);
      window[callback] = (code, stdout, stderr) => {
        clearTimeout(timer); delete window[callback];
        try {
          const lines = String(stdout).trim().split('\n');
          const result = JSON.parse(lines[lines.length - 1]);
          if (typeof result.success !== 'boolean' || typeof result.errorCode !== 'string') throw new Error('Invalid backend result');
          if (code !== 0 && result.success) throw new Error('Backend exit code conflicts with response');
          resolve(result);
        } catch (error) { reject(new Error(`${error.message}\n${String(stderr).slice(0,2000)}`)); }
      };
      try { window.ksu.exec(cmd, '{}', callback); } catch (e) { clearTimeout(timer); delete window[callback]; reject(e); }
    });
  }
  window.cap = Object.freeze({ call, command });
})();
