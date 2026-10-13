// SPDX-License-Identifier: GPL-3.0-or-later
const fs = require('node:fs');
for (let n = 0; n < 20000; n++) {
  fs.writeSync(1, 'stdout-' + n + '-' + 'x'.repeat(100) + '\n');
  fs.writeSync(2, 'stderr-' + n + '-' + 'y'.repeat(100) + '\n');
}
console.log('FLOOD_COMPLETE');
