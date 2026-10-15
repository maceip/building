// SPDX-License-Identifier: Apache-2.0
// Relocate executable paths compiled into the pinned runtime packages. The next
// execve interceptor still handles Android's system-linker launch requirements.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <link.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

// A linker-launched PIE sees linker64 at /proc/self/exe. Give Node's libuv a stable,
// installer-owned entrypoint for spawn/fork. Only substitute when the actual Node
// image is mapped in THIS process; inherited environment must not affect Python,
// shells, compiler tools, or arbitrary project programs.
struct node_image { const char *binary; int found; };
static int find_node_image(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    struct node_image *image = data;
    struct stat actual, expected;
    if (info->dlpi_name && info->dlpi_name[0] == '/' &&
        stat(info->dlpi_name, &actual) == 0 && stat(image->binary, &expected) == 0 &&
        actual.st_dev == expected.st_dev && actual.st_ino == expected.st_ino) {
        image->found = 1;
        return 1;
    }
    return 0;
}

ssize_t readlink(const char *path, char *buffer, size_t size) {
    ssize_t (*next_readlink)(const char *, char *, size_t) = dlsym(RTLD_NEXT, "readlink");
    if (!next_readlink) { errno = ENOSYS; return -1; }
    const char *binary = getenv("BUILDING_RUNTIME_NODE_BINARY");
    const char *launcher = getenv("BUILDING_RUNTIME_NODE_LAUNCHER");
    if (size > 0 && strcmp(path, "/proc/self/exe") == 0 && binary && launcher && launcher[0] == '/') {
        char real[PATH_MAX];
        ssize_t n = next_readlink(path, real, sizeof(real) - 1);
        if (n > 0) {
            real[n] = '\0';
            if (strcmp(real, "/system/bin/linker64") == 0 || strstr(real, "/bin/linker64") != NULL) {
                struct node_image image = {binary, 0};
                dl_iterate_phdr(find_node_image, &image);
                if (image.found) {
                    size_t length = strlen(launcher);
                    if (length > size) length = size;
                    memcpy(buffer, launcher, length);
                    return (ssize_t)length;
                }
            }
        }
    }
    return next_readlink(path, buffer, size);
}

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
