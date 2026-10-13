# SPDX-License-Identifier: GPL-3.0-or-later
import asyncio
import hashlib
import os
import sys
from pathlib import Path

counter = Path('engine-check-count.txt')
count = int(counter.read_text()) + 1 if counter.exists() else 1
Path('engine-check-count.pending').write_text(str(count))
os.replace('engine-check-count.pending', counter)
print('CPython=' + sys.version.split()[0])
print('CALC=' + str(sum(i * i for i in range(1, 101))))
print('PERSIST=' + str(count))
print('SHA256=' + hashlib.sha256(b'offline').hexdigest())
async def main():
    await asyncio.sleep(0.01)
    print('OFFLINE_ENGINE_OK')
asyncio.run(main())
