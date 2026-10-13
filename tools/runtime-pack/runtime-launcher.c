// SPDX-License-Identifier: GPL-3.0-or-later
// APK-delivered process-group leader. The app terminates -pgid, including shell descendants.
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc < 3) {
        fputs("usage: runtime-launcher PID_FILE EXECUTABLE [ARG...]\n", stderr);
        return 126;
    }
    if (setsid() < 0) { perror("runtime setsid"); return 126; }
    // If the app dies, do not leave the leader running. Normal stop kills the entire group.
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0) { perror("runtime parent signal"); return 126; }
    int fd = open(argv[1], O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) { perror("runtime pid file"); return 126; }
    char buffer[48];
    int size = snprintf(buffer, sizeof(buffer), "%ld\n", (long)getpid());
    if (write(fd, buffer, (size_t)size) != size) { perror("runtime pid write"); close(fd); return 126; }
    close(fd);
    execv(argv[2], &argv[2]);
    perror("runtime exec");
    return errno == ENOENT ? 127 : 126;
}
