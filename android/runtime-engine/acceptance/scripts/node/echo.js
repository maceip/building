// SPDX-License-Identifier: GPL-3.0-or-later
const readline = require('node:readline');
console.log('READY_FOR_INPUT');
const input = readline.createInterface({input:process.stdin});
input.on('line', line => console.log('ECHO=' + line));
input.on('close', () => console.log('EOF_COMPLETE'));
