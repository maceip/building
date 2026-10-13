# SPDX-License-Identifier: GPL-3.0-or-later
import sys
print('READY_FOR_INPUT', flush=True)
for line in sys.stdin:
    print('ECHO=' + line.rstrip('\n'), flush=True)
print('EOF_COMPLETE', flush=True)
