// SPDX-License-Identifier: GPL-3.0-or-later
// Minimal POSIX env helper for APK-delivered npm/Python shebangs; no GNU extension claim.
#define _GNU_SOURCE
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
extern char **environ;

int main(int argc, char **argv) {
    int i = 1;
    if (i < argc && (strcmp(argv[i], "-i") == 0 || strcmp(argv[i], "--ignore-environment") == 0)) { clearenv(); i++; }
    while (i < argc && strcmp(argv[i], "-u") == 0) {
        if (++i >= argc) { fputs("env: -u needs a name\n", stderr); return 125; }
        unsetenv(argv[i++]);
    }
    if (i < argc && strcmp(argv[i], "--") == 0) i++;
    while (i < argc && strchr(argv[i], '=') != NULL) {
        if (putenv(argv[i++]) != 0) { perror("env"); return 125; }
    }
    if (i == argc) { for (char **e = environ; *e; e++) puts(*e); return 0; }
    if (argv[i][0] == '-') { fputs("env: unsupported option\n", stderr); return 125; }
    const char *command = argv[i];
    if (strchr(command, '/') != NULL) {
        execve(command, &argv[i], environ);
    } else {
        const char *search = getenv("PATH");
        char *paths = strdup(search ? search : "/system/bin");
        if (!paths) { perror("env"); return 125; }
        char *next = paths;
        char *part;
        int denied = 0;
        while ((part = strsep(&next, ":")) != NULL) {
            char *path = NULL;
            if (asprintf(&path, "%s/%s", *part ? part : ".", command) < 0) { free(paths); return 125; }
            // execvp inside the upstream interceptor bypasses the APK path relocator;
            // direct execve keeps Node's /proc/self/exe and child_process identity intact.
            execve(path, &argv[i], environ);
            int failure = errno;
            free(path);
            if (failure == EACCES) denied = 1;
            else if (failure != ENOENT && failure != ENOTDIR) { free(paths); errno = failure; goto failed; }
        }
        free(paths);
        errno = denied ? EACCES : ENOENT;
    }
failed:
    ;
    int saved = errno;
    perror("env");
    return saved == ENOENT ? 127 : 126;
}
