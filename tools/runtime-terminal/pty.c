// SPDX-License-Identifier: GPL-3.0-or-later
// Public POSIX/JNI PTY transport. No Android private APIs, root, su, or adb.
#define _GNU_SOURCE
#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#ifdef __linux__
#include <sys/prctl.h>
#include <sys/syscall.h>
#endif
#ifdef __ANDROID__
#include <android/api-level.h>
#endif

#define JNI_METHOD(name) Java_dev_ide_android_workbench_terminal_RuntimePty_##name
#define STILL_RUNNING (-1000)

static void fail(JNIEnv *env, const char *operation, int error) {
    char message[256];
    snprintf(message, sizeof(message), "%s: %s (errno %d)", operation, strerror(error), error);
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type) (*env)->ThrowNew(env, type, message);
}

static char *utf8(JNIEnv *env, jbyteArray data) {
    if (!data) { fail(env, "Missing PTY argument", EINVAL); return NULL; }
    jsize length = (*env)->GetArrayLength(env, data);
    if (length > 1024 * 1024) { fail(env, "PTY argument too long", E2BIG); return NULL; }
    char *result = malloc((size_t)length + 1);
    if (!result) { fail(env, "PTY allocation", ENOMEM); return NULL; }
    (*env)->GetByteArrayRegion(env, data, 0, length, (jbyte *)result);
    if ((*env)->ExceptionCheck(env)) { free(result); return NULL; }
    if (memchr(result, '\0', (size_t)length)) { free(result); fail(env, "NUL in PTY argument", EINVAL); return NULL; }
    result[length] = '\0';
    return result;
}

static void free_vector(char **vector) {
    if (!vector) return;
    for (char **entry = vector; *entry; ++entry) free(*entry);
    free(vector);
}

static char **vector(JNIEnv *env, jobjectArray data) {
    if (!data) { fail(env, "Missing PTY arguments", EINVAL); return NULL; }
    jsize length = (*env)->GetArrayLength(env, data);
    if (length > 4096) { fail(env, "Too many PTY arguments", E2BIG); return NULL; }
    char **result = calloc((size_t)length + 1, sizeof(char *));
    if (!result) { fail(env, "PTY allocation", ENOMEM); return NULL; }
    for (jsize i = 0; i < length; ++i) {
        jbyteArray item = (jbyteArray)(*env)->GetObjectArrayElement(env, data, i);
        result[i] = utf8(env, item);
        (*env)->DeleteLocalRef(env, item);
        if (!result[i]) { free_vector(result); return NULL; }
    }
    return result;
}

static int cloexec_pipe(int fds[2]) {
    if (pipe(fds) < 0) return -1;
    if (fcntl(fds[0], F_SETFD, FD_CLOEXEC) < 0 || fcntl(fds[1], F_SETFD, FD_CLOEXEC) < 0) {
        int error = errno; close(fds[0]); close(fds[1]); errno = error; return -1;
    }
    return 0;
}

// Only async-signal-safe operations between fork and exec: ART is multithreaded.
static void child_error(int fd, int error) {
    ssize_t ignored = write(fd, &error, sizeof(error));
    (void)ignored;
    _exit(126);
}

JNIEXPORT jintArray JNICALL JNI_METHOD(nativeSpawn)(JNIEnv *env, jclass type,
        jobjectArray command, jbyteArray cwd, jobjectArray environment, jint columns, jint rows) {
    (void)type;
    char **argv = vector(env, command);
    if (!argv) return NULL;
    char **envp = vector(env, environment);
    char *directory = envp ? utf8(env, cwd) : NULL;
    int master = -1, slave = -1, errors[2] = {-1, -1}, saved = 0;
    pid_t pid = -1;
    const char *stage = "PTY create";
    if (!directory) goto cleanup;
    if (!argv[0] || argv[0][0] != '/' || columns < 2 || columns > 1000 || rows < 2 || rows > 1000) {
        saved = EINVAL; goto cleanup;
    }
    master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master < 0 || grantpt(master) < 0 || unlockpt(master) < 0) { saved = errno; goto cleanup; }
    char slave_name[PATH_MAX];
#ifdef __linux__
    saved = ptsname_r(master, slave_name, sizeof(slave_name));
    if (saved != 0) goto cleanup;
#else
    const char *name = ptsname(master);
    if (!name || strlen(name) >= sizeof(slave_name)) { saved = errno ? errno : ENAMETOOLONG; goto cleanup; }
    strcpy(slave_name, name);
#endif
    slave = open(slave_name, O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (slave < 0) { saved = errno; goto cleanup; }
    struct termios attributes;
    if (tcgetattr(slave, &attributes) < 0) { saved = errno; goto cleanup; }
    attributes.c_iflag |= ICRNL | IXON;
#ifdef IUTF8
    attributes.c_iflag |= IUTF8;
#endif
    attributes.c_oflag |= OPOST | ONLCR;
    attributes.c_cflag |= CS8 | CREAD;
    attributes.c_lflag |= ICANON | ISIG | IEXTEN | ECHO | ECHOE | ECHOK;
    attributes.c_cc[VINTR] = 3;
    attributes.c_cc[VQUIT] = 28;
    attributes.c_cc[VERASE] = 127;
    attributes.c_cc[VKILL] = 21;
    attributes.c_cc[VEOF] = 4;
    attributes.c_cc[VSUSP] = 26;
    if (tcsetattr(slave, TCSANOW, &attributes) < 0) { saved = errno; goto cleanup; }
    struct winsize size = {.ws_col = (unsigned short)columns, .ws_row = (unsigned short)rows};
    if (ioctl(slave, TIOCSWINSZ, &size) < 0 || cloexec_pipe(errors) < 0) { saved = errno; goto cleanup; }
    struct rlimit limit;
    if (getrlimit(RLIMIT_NOFILE, &limit) < 0) { saved = errno; goto cleanup; }
    int max_fd = limit.rlim_cur > INT_MAX ? INT_MAX : (int)limit.rlim_cur;
    pid_t parent = getpid();
    stage = "PTY fork/exec";
    pid = fork();
    if (pid < 0) { saved = errno; goto cleanup; }
    if (pid == 0) {
        close(errors[0]);
        if (setsid() < 0) child_error(errors[1], errno);
#ifdef __linux__
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0) child_error(errors[1], errno);
        if (getppid() != parent) _exit(125);
#else
        (void)parent;
#endif
        if (ioctl(slave, TIOCSCTTY, 0) < 0) child_error(errors[1], errno);
        if (tcsetpgrp(slave, getpid()) < 0) child_error(errors[1], errno);
        for (int fd = 0; fd < 3; ++fd) if (dup2(slave, fd) < 0) child_error(errors[1], errno);
        for (int fd = 3; fd < max_fd; ++fd) if (fd != errors[1]) close(fd);
        struct sigaction action;
        memset(&action, 0, sizeof(action));
        action.sa_handler = SIG_DFL;
        sigemptyset(&action.sa_mask);
        for (int signal = 1; signal < NSIG; ++signal) {
            if (signal != SIGKILL && signal != SIGSTOP) sigaction(signal, &action, NULL);
        }
        sigset_t mask;
        sigemptyset(&mask);
        sigprocmask(SIG_SETMASK, &mask, NULL);
        if (chdir(directory) < 0) child_error(errors[1], errno);
        execve(argv[0], argv, envp);
        child_error(errors[1], errno);
    }
    close(slave); slave = -1;
    close(errors[1]); errors[1] = -1;
    // CLOEXEC reports successful exec without mistaking an exec failure for a live shell.
    ssize_t count;
    do { count = read(errors[0], &saved, sizeof(saved)); } while (count < 0 && errno == EINTR);
    if (count < 0) saved = errno;
    if (count > 0 && saved == 0) saved = EIO;
    if (saved != 0) goto cleanup;
    if (fcntl(master, F_SETFL, fcntl(master, F_GETFL) | O_NONBLOCK) < 0) { saved = errno; goto cleanup; }
    jint values[2] = {master, pid};
    jintArray result = (*env)->NewIntArray(env, 2);
    if (!result) { saved = ENOMEM; goto cleanup; }
    (*env)->SetIntArrayRegion(env, result, 0, 2, values);
    close(errors[0]);
    free_vector(argv); free_vector(envp); free(directory);
    return result;
cleanup:
    if (pid > 0) {
        kill(-pid, SIGKILL); kill(pid, SIGKILL);
        while (waitpid(pid, NULL, 0) < 0 && errno == EINTR) {}
    }
    if (master >= 0) close(master);
    if (slave >= 0) close(slave);
    if (errors[0] >= 0) close(errors[0]);
    if (errors[1] >= 0) close(errors[1]);
    free_vector(argv); free_vector(envp); free(directory);
    if (!(*env)->ExceptionCheck(env)) fail(env, stage, saved ? saved : EIO);
    return NULL;
}

JNIEXPORT jint JNICALL JNI_METHOD(nativeRead)(JNIEnv *env, jclass type, jint fd, jbyteArray output) {
    (void)type;
    struct pollfd item = {.fd = fd, .events = POLLIN};
    int ready;
    do { ready = poll(&item, 1, 100); } while (ready < 0 && errno == EINTR);
    if (ready == 0) return 0;
    if (ready < 0) { fail(env, "PTY poll", errno); return -1; }
    jsize capacity = (*env)->GetArrayLength(env, output);
    if (capacity < 1) { fail(env, "PTY buffer", EINVAL); return -1; }
    char buffer[16384];
    size_t size = (size_t)capacity < sizeof(buffer) ? (size_t)capacity : sizeof(buffer);
    ssize_t count;
    do { count = read(fd, buffer, size); } while (count < 0 && errno == EINTR);
    if (count > 0) { (*env)->SetByteArrayRegion(env, output, 0, (jsize)count, (const jbyte *)buffer); return (jint)count; }
    if (count == 0 || errno == EIO) return -1; // Linux master returns EIO when the slave closes.
    if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
    fail(env, "PTY read", errno);
    return -1;
}

JNIEXPORT jint JNICALL JNI_METHOD(nativeWrite)(JNIEnv *env, jclass type, jint fd, jbyteArray input, jint offset, jint length) {
    (void)type;
    jsize size = (*env)->GetArrayLength(env, input);
    if (offset < 0 || length < 0 || offset > size || length > size - offset) { fail(env, "PTY write range", EINVAL); return -1; }
    char buffer[16384];
    jint count = length < (jint)sizeof(buffer) ? length : (jint)sizeof(buffer);
    (*env)->GetByteArrayRegion(env, input, offset, count, (jbyte *)buffer);
    if ((*env)->ExceptionCheck(env)) return -1;
    ssize_t written;
    do { written = write(fd, buffer, (size_t)count); } while (written < 0 && errno == EINTR);
    if (written >= 0) return (jint)written;
    if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
    fail(env, "PTY write", errno);
    return -1;
}

JNIEXPORT void JNICALL JNI_METHOD(nativeResize)(JNIEnv *env, jclass type, jint fd, jint columns, jint rows) {
    (void)type;
    if (columns < 2 || columns > 1000 || rows < 2 || rows > 1000) { fail(env, "PTY size", EINVAL); return; }
    struct winsize size = {.ws_col = (unsigned short)columns, .ws_row = (unsigned short)rows};
    // The kernel sends SIGWINCH to the actual foreground job, not merely the shell.
    if (ioctl(fd, TIOCSWINSZ, &size) < 0) fail(env, "PTY resize", errno);
}

// pidfds pin the target task, unlike a getsid(pid) -> kill(pid) sequence where PID
// reuse could signal an unrelated same-UID process. No libc API-level link needed.
static int open_pidfd(pid_t pid) {
#if defined(__linux__) && defined(SYS_pidfd_open) && defined(SYS_pidfd_send_signal)
#ifdef __ANDROID__
    // Old Android zygote seccomp filters can SIGSYS unknown syscalls rather than return
    // ENOSYS. Do not probe on pre-Android-12 app processes; use the conservative path.
    if (android_get_device_api_level() < 31) { errno = ENOSYS; return -1; }
#endif
    return (int)syscall(SYS_pidfd_open, pid, 0);
#else
    (void)pid; errno = ENOSYS; return -1;
#endif
}

static int signal_pidfd(int fd, int signal) {
#if defined(__linux__) && defined(SYS_pidfd_send_signal)
    return (int)syscall(SYS_pidfd_send_signal, fd, signal, NULL, 0);
#else
    (void)fd; (void)signal; errno = ENOSYS; return -1;
#endif
}

JNIEXPORT jboolean JNICALL JNI_METHOD(nativeSupportsSessionCleanup)(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    int fd = open_pidfd(getpid());
    if (fd < 0) return JNI_FALSE;
    int result = signal_pidfd(fd, 0);
    close(fd);
#ifdef __linux__
    DIR *processes = opendir("/proc");
    if (!processes) return JNI_FALSE;
    closedir(processes);
#endif
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL JNI_METHOD(nativeSignalSession)(JNIEnv *env, jclass type, jint fd, jint leader, jint signal) {
    (void)env; (void)type; (void)fd;
    if (leader <= 1 || leader == getpid()) return JNI_FALSE;
    int complete = 0;
#ifdef __linux__
    DIR *processes = opendir("/proc");
    if (processes) {
        complete = 1;
        struct dirent *entry;
        while ((entry = readdir(processes)) != NULL) {
            char *end;
            long value = strtol(entry->d_name, &end, 10);
            if (*end || value <= 1 || value > INT_MAX || value == getpid() || value == leader) continue;
            pid_t child = (pid_t)value;
            // The preliminary check reduces fd/syscall work for unrelated system tasks.
            // The second check AFTER opening the handle is the one authorizing the signal.
            if (getsid(child) != leader) continue;
            int handle = open_pidfd(child);
            if (handle < 0) { if (errno != ESRCH) complete = 0; continue; }
            if (getsid(child) == leader && signal_pidfd(handle, signal) < 0 && errno != ESRCH) complete = 0;
            close(handle);
        }
        closedir(processes);
    }
#endif
    // Only the leader's numerical PID/group is pinned against reuse by WNOWAIT.
    // Never signal a scanned PGID or a stale tcgetpgrp() result by number.
    kill(-leader, signal);
    kill(leader, signal);
    return complete ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL JNI_METHOD(nativePeekExit)(JNIEnv *env, jclass type, jint pid) {
    (void)type;
    siginfo_t info;
    memset(&info, 0, sizeof(info));
    if (waitid(P_PID, (id_t)pid, &info, WEXITED | WNOHANG | WNOWAIT) < 0) {
        if (errno == EINTR) return STILL_RUNNING;
        fail(env, "PTY wait", errno); return STILL_RUNNING;
    }
    if (info.si_pid == 0) return STILL_RUNNING;
    // Leave the leader waitable (not reaped) until all process groups are stopped, so
    // its PID/session ID cannot be recycled and accidentally target an unrelated process.
    return info.si_code == CLD_EXITED ? info.si_status : 128 + info.si_status;
}

JNIEXPORT jint JNICALL JNI_METHOD(nativeReap)(JNIEnv *env, jclass type, jint pid) {
    (void)type;
    int status;
    pid_t result;
    do { result = waitpid(pid, &status, WNOHANG); } while (result < 0 && errno == EINTR);
    if (result == 0) return STILL_RUNNING;
    if (result < 0) { fail(env, "PTY reap", errno); return STILL_RUNNING; }
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}

JNIEXPORT void JNICALL JNI_METHOD(nativeClose)(JNIEnv *env, jclass type, jint fd) {
    (void)env; (void)type;
    close(fd); // Never retry close(EINTR): another thread could have reused the descriptor.
}
