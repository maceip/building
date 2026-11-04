// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';
const fs = require('node:fs');
const crypto = require('node:crypto');
const zlib = require('node:zlib');
const previous = fs.existsSync('counter.txt') ? Number(fs.readFileSync('counter.txt', 'utf8')) : 0;
fs.writeFileSync('counter.pending', String(previous + 1));
fs.renameSync('counter.pending', 'counter.txt');
console.log('ENGINE=' + process.version);
console.log('CALC=' + Array.from({length:100}, (_, i) => (i + 1) ** 2).reduce((a,b) => a+b, 0));
console.log('PERSIST=' + (previous + 1));
console.log('UTF8=你好 café');
console.log('ZLIB=' + (zlib.inflateSync(zlib.deflateSync('offline')).toString() === 'offline'));
console.log('HASH=' + crypto.createHash('sha256').update('app-engine').digest('hex'));
console.log('CWD=' + process.cwd());
console.error('stderr-from-real-node');
setTimeout(() => console.log('ASYNC=ok\nCOMPLETE'), 10);
