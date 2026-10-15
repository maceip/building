// SPDX-License-Identifier: Apache-2.0
// Relocate executable paths compiled into the pinned runtime packages. The next
// execve interceptor still handles Android's system-linker launch requirements.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int beneath(const char *path, const char *directory) {
    const size_t length = strlen(directory);
    return length > 0 && strncmp(path, directory, length) == 0 && path[length] == '/';
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    static const char old_prefix[] = "/data/data/com.termux/files/usr/";
    int (*next_execve)(const char *, char *const[], char *const[]) = dlsym(RTLD_NEXT, "execve");
    if (!next_execve) { errno = ENOSYS; return -1; }
    const char *prefix = getenv("TERMUX__PREFIX");
    if (!prefix || prefix[0] != '/') return next_execve(path, argv, envp);

    char *relocated = NULL;
    if (strncmp(path, old_prefix, sizeof(old_prefix) - 1) == 0) {
        const char *suffix = path + sizeof(old_prefix) - 1;
        const size_t size = strlen(prefix) + strlen(suffix) + 2;
        relocated = malloc(size);
        if (!relocated) { errno = ENOMEM; return -1; }
        strcpy(relocated, prefix);
        strcat(relocated, "/");
        strcat(relocated, suffix);
        path = relocated;
    }

    // APK-owned executables are already executable. Linker-wrapping their private
    // symlinks makes /proc/self/exe identify linker64, breaking Node's child/fork API.
    const char *native = getenv("BUILDING_RUNTIME_NATIVE_DIR");
    char *resolved = NULL;
    if (native && native[0] == '/' && beneath(path, prefix)) {
        resolved = realpath(path, NULL);
        if (resolved && beneath(resolved, native)) path = resolved;
    }
    const int result = next_execve(path, argv, envp);
    const int saved_errno = errno;
    free(resolved);
    free(relocated);
    errno = saved_errno;
    return result;
}
