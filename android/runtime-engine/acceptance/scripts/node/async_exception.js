// SPDX-License-Identifier: GPL-3.0-or-later
console.log('BEFORE_ASYNC_EXCEPTION');
setTimeout(() => { throw new Error('intentional-async-error'); }, 10);
