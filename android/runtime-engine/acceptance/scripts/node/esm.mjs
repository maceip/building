// SPDX-License-Identifier: GPL-3.0-or-later
import { createHash } from 'node:crypto';
await new Promise(resolve => setTimeout(resolve, 10));
console.log('ESM=' + createHash('sha256').update('offline').digest('hex'));
