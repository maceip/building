// SPDX-License-Identifier: GPL-3.0-or-later
// Installer-owned entrypoint for the Node PIE in a Play-verified SplitCompat library.
// Android's linker loads the read-only signed payload; no chmod or native download.
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
int main(int argc, char **argv) {
    const char *binary = getenv("BUILDING_RUNTIME_NODE_BINARY");
    if (!binary || binary[0] != '/') {
        fputs("Node pack environment is missing\n", stderr);
        return 126;
    }
    char **args = calloc((size_t)argc + 2, sizeof(char *));
    if (!args) return 126;
    args[0] = "/system/bin/linker64";
    args[1] = (char *)binary;
    for (int i = 1; i < argc; ++i) args[i + 1] = argv[i];
    execv(args[0], args);
    perror("Node pack launch");
    return 126;
}
