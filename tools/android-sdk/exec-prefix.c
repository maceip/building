// SPDX-License-Identifier: Apache-2.0
// Relocate executable paths compiled into the pinned runtime packages. The next
// execve interceptor still handles Android's system-linker launch requirements.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int execve(const char *path, char *const argv[], char *const envp[]) {
    static const char old_prefix[] = "/data/data/com.termux/files/usr/";
    int (*next_execve)(const char *, char *const[], char *const[]) = dlsym(RTLD_NEXT, "execve");
    if (!next_execve) { errno = ENOSYS; return -1; }
    const char *prefix = getenv("TERMUX__PREFIX");
    if (!prefix || prefix[0] != '/' || strncmp(path, old_prefix, sizeof(old_prefix) - 1) != 0)
        return next_execve(path, argv, envp);

    const char *suffix = path + sizeof(old_prefix) - 1;
    const size_t size = strlen(prefix) + strlen(suffix) + 2;
    char *relocated = malloc(size);
    if (!relocated) { errno = ENOMEM; return -1; }
    strcpy(relocated, prefix);
    strcat(relocated, "/");
    strcat(relocated, suffix);
    const int result = next_execve(relocated, argv, envp);
    const int saved_errno = errno;
    free(relocated);
    errno = saved_errno;
    return result;
}
