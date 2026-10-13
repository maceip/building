// SPDX-License-Identifier: GPL-3.0-or-later
for (const [label, action] of [
  ['SPAWN_DENIED', () => require('node:child_process').spawn('/system/bin/sh')],
  ['ADDON_DENIED', () => process.dlopen({exports:{}}, '/not-a-native-addon.so')],
  ['WORKER_DENIED', () => new (require('node:worker_threads').Worker)('1', {eval:true})],
]) {
  let denied = false;
  try { action(); } catch (error) { denied = error.code === 'ERR_ACCESS_DENIED' || error.code === 'ERR_DLOPEN_DISABLED'; }
  if (!denied) throw new Error('Capability boundary not enforced: ' + label);
  console.log(label);
}
