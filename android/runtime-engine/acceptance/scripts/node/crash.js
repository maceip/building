// SPDX-License-Identifier: GPL-3.0-or-later
require('node:fs').writeSync(1, 'BEFORE_NATIVE_ABORT\n');
process.abort();
