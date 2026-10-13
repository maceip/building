# SPDX-License-Identifier: GPL-3.0-or-later
print('BEFORE_EXCEPTION', flush=True)
raise RuntimeError('intentional-script-error')
