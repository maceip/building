# SPDX-License-Identifier: GPL-3.0-or-later
import os
print('BEFORE_NATIVE_ABORT', flush=True)
os.abort()
