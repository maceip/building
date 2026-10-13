# SPDX-License-Identifier: GPL-3.0-or-later
import sys
for n in range(20000):
    print('stdout-' + str(n) + '-' + 'x' * 100)
    print('stderr-' + str(n) + '-' + 'y' * 100, file=sys.stderr)
print('FLOOD_COMPLETE')
