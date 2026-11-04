// SPDX-License-Identifier: GPL-3.0-or-later
require('node:fs').writeSync(1, 'BEFORE_EXIT\n');
process.exit(7);
