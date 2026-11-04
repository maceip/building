# SPDX-License-Identifier: GPL-3.0-or-later
import asyncio
import hashlib
import json
import os
from pathlib import Path
import socket
import ssl
import sys
import zlib

counter = Path('counter.txt')
previous = int(counter.read_text()) if counter.exists() else 0
pending = Path('counter.pending')
pending.write_text(str(previous + 1))
os.replace(pending, counter)
print('ENGINE=' + sys.version.split()[0])
print('CALC=' + str(sum(i * i for i in range(1, 101))))
print('PERSIST=' + str(previous + 1))
print('UTF8=你好 café')
print('ZLIB=' + str(zlib.decompress(zlib.compress(b'offline')) == b'offline'))
print('TLS=' + ssl.OPENSSL_VERSION)
print('JSON=' + json.dumps({'hash': hashlib.sha256(b'app-engine').hexdigest()}))
print('CWD=' + os.getcwd())
print('stderr-from-real-python', file=sys.stderr)
async def offline():
    await asyncio.sleep(0.01)
    return 'ASYNC=ok'
print(asyncio.run(offline()))
print('COMPLETE')
