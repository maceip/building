// SPDX-License-Identifier: GPL-3.0-or-later
'use strict';
const fs = require('node:fs');
const crypto = require('node:crypto');
const file = 'engine-check-count.txt';
const count = fs.existsSync(file) ? Number(fs.readFileSync(file, 'utf8')) + 1 : 1;
fs.writeFileSync('engine-check-count.pending', String(count));
fs.renameSync('engine-check-count.pending', file);
console.log('Node=' + process.version);
console.log('CALC=' + Array.from({length:100}, (_, i) => (i + 1) ** 2).reduce((a,b) => a+b, 0));
console.log('PERSIST=' + count);
console.log('SHA256=' + crypto.createHash('sha256').update('offline').digest('hex'));
setTimeout(() => console.log('OFFLINE_ENGINE_OK'), 10);
