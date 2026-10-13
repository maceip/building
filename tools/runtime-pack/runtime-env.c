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
    execvp(argv[i], &argv[i]);
    int saved = errno;
    perror("env");
    return saved == ENOENT ? 127 : 126;
}
