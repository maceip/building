// SPDX-License-Identifier: Apache-2.0
// Relocate executable paths compiled into the pinned runtime packages. The next
// execve interceptor still handles Android's system-linker launch requirements.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <limits.h>
#include <stdio.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

extern char **environ;
// Resolve before any compiler worker forks. Calling dlsym in a forked worker can
// wait forever on a dynamic-linker lock inherited from another parent thread.
static ssize_t (*raw_readlink)(const char *, char *, size_t);
static int (*raw_access)(const char *, int);
static int (*raw_faccessat)(int, const char *, int, int);
static int (*raw_execve)(const char *, char *const[], char *const[]);
__attribute__((constructor))
static void prepare_process_functions(void) {
    raw_readlink = dlsym(RTLD_NEXT, "readlink");
    raw_access = dlsym(RTLD_NEXT, "access");
    raw_faccessat = dlsym(RTLD_NEXT, "faccessat");
    raw_execve = dlsym(RTLD_NEXT, "execve");
}

// A linker-launched PIE sees linker64 at /proc/self/exe. Give Node's libuv a stable,
// installer-owned entrypoint for spawn/fork. Only substitute when the actual Node
// image is mapped in THIS process; inherited environment must not affect Python,
// shells, compiler tools, or arbitrary project programs.
struct node_image { const char *binary; int found; };
static char *swift_executable_path;
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

// Swift on Linux reads /proc/self/cmdline instead of libc's adjusted argv. A
// system-linker launch leaves the loader's extra executable argument there.
// Use Swift's exported override API after verifying which image is actually
// mapped; never rewrite procfs or change arguments for unrelated processes.
__attribute__((constructor))
static void restore_swift_arguments(void) {
    typedef void (__attribute__((swiftcall)) *override_fn)(char **, int);
    override_fn override = (override_fn)dlsym(RTLD_DEFAULT, "_swift_stdlib_overrideUnsafeArgvArgc");
    if (!override) return;
    if (!raw_readlink) return;
    char executable[PATH_MAX];
    ssize_t length = raw_readlink("/proc/self/exe", executable, sizeof(executable) - 1);
    if (length <= 0) return;
    executable[length] = '\0';
    const char *leaf = strrchr(executable, '/');
    if (!leaf || strcmp(leaf + 1, "linker64") != 0) return;
    FILE *input = fopen("/proc/self/cmdline", "rb");
    if (!input) return;
    char **args = calloc(4097, sizeof(char *));
    char *buffer = NULL;
    size_t capacity = 0, count = 0, bytes = 0;
    int complete = args != NULL;
    while (complete && (length = getdelim(&buffer, &capacity, '\0', input)) >= 0) {
        bytes += (size_t)length;
        if (count == 4096 || bytes > 2 * 1024 * 1024 || !(args[count] = strdup(buffer))) {
            complete = 0;
            break;
        }
        count++;
    }
    free(buffer);
    fclose(input);
    if (complete && count >= 2 && args[1][0] == '/') {
        struct node_image image = {args[1], 0};
        dl_iterate_phdr(find_node_image, &image);
        if (image.found && (swift_executable_path = strdup(args[1]))) {
            free(args[1]);
            memmove(args + 1, args + 2, (count - 1) * sizeof(char *));
            override(args, (int)count - 1);
            return; // Swift retains this backing storage for the process lifetime.
        }
    }
    for (size_t i = 0; i < count; i++) free(args[i]);
    free(args);
}

ssize_t readlink(const char *path, char *buffer, size_t size) {
    ssize_t (*next_readlink)(const char *, char *, size_t) = raw_readlink;
    if (!next_readlink) { errno = ENOSYS; return -1; }
    if (size > 0 && strcmp(path, "/proc/self/exe") == 0 && swift_executable_path) {
        size_t length = strlen(swift_executable_path);
        if (length > size) length = size;
        memcpy(buffer, swift_executable_path, length);
        return (ssize_t)length;
    }
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

// Populated by AndroidRuntimeHost only after checking the signed split manifests
// and every native payload hash. SplitCompat intentionally leaves these files
// read-only/non-executable; execution uses the system linker's supported loader.
static int split_payload(const char *path, const char *prefix) {
    if (!beneath(path, prefix)) return 0;
    const char *directories = getenv("BUILDING_RUNTIME_SPLIT_NATIVE_DIRS");
    if (!directories) return 0;
    char resolved[PATH_MAX];
    if (!realpath(path, resolved)) return 0;
    const char *name = strrchr(resolved, '/');
    if (!name || strncmp(name + 1, "libdevruntime_", 14) != 0) return 0;
    for (const char *start = directories; *start;) {
        const char *end = strchr(start, ':');
        size_t length = end ? (size_t)(end - start) : strlen(start);
        if (length && strncmp(resolved, start, length) == 0 && resolved[length] == '/') return 1;
        if (!end) break;
        start = end + 1;
    }
    return 0;
}

int access(const char *path, int mode) {
    int (*next_access)(const char *, int) = raw_access;
    if (!next_access) { errno = ENOSYS; return -1; }
    const char *prefix = getenv("TERMUX__PREFIX");
    // Native build drivers test X_OK before spawning. For verified split files,
    // report availability through our linker entrypoint without chmod'ing them.
    if ((mode & X_OK) && prefix && split_payload(path, prefix)) mode = (mode & ~X_OK) | R_OK;
    return next_access(path, mode);
}

int faccessat(int directory, const char *path, int mode, int flags) {
    int (*next_faccessat)(int, const char *, int, int) = raw_faccessat;
    if (!next_faccessat) { errno = ENOSYS; return -1; }
    const char *prefix = getenv("TERMUX__PREFIX");
    // Dash checks PATH candidates with faccessat(AT_EACCESS), not access().
    if ((mode & X_OK) && prefix && split_payload(path, prefix)) mode = (mode & ~X_OK) | R_OK;
    return next_faccessat(directory, path, mode, flags);
}

static int private_executable_elf(const char *path);

int execve(const char *path, char *const argv[], char *const envp[]) {
    static const char old_prefix[] = "/data/data/com.termux/files/usr/";
    int (*next_execve)(const char *, char *const[], char *const[]) = raw_execve;
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

    if (split_payload(path, prefix) || private_executable_elf(path)) {
        size_t count = 0;
        while (argv[count]) count++;
        char **args = calloc(count + 3, sizeof(char *));
        if (!args) { free(relocated); errno = ENOMEM; return -1; }
        // The linker/libc handoff preserves the caller's argv[0]. Swift's driver
        // and multicall tools rely on that name even though the kernel runs linker64.
        args[0] = count ? argv[0] : (char *)path;
        args[1] = (char *)path;
        size_t offset = 2;
        for (size_t i = 1; i < count; i++) args[offset++] = argv[i];
        int result = next_execve("/system/bin/linker64", args, envp);
        int saved_errno = errno;
        free(args); free(relocated); errno = saved_errno;
        return result;
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

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

// SwiftPM spawns freshly compiled manifests and SDK-supplied host tools in private
// app storage, where direct exec is denied. Adapt both spawn and execve callers.
// Accept executable ELF files there through the linker, preserving the normal
// execute-bit check; read-only delivered payloads use their separate verified path.
static int private_executable_elf(const char *path) {
    const char *root = getenv("TERMUX_APP__DATA_DIR");
    const char *legacy = getenv("TERMUX_APP__LEGACY_DATA_DIR");
    char resolved[PATH_MAX];
    if (!realpath(path, resolved)) return 0;
    if ((!root || !beneath(resolved, root)) && (!legacy || !beneath(resolved, legacy))) return 0;
    struct stat st;
    if (stat(resolved, &st) != 0 || !S_ISREG(st.st_mode) || !(st.st_mode & 0111)) return 0;
    int fd = open(resolved, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    unsigned char magic[4];
    ssize_t size = read(fd, magic, sizeof(magic));
    close(fd);
    return size == 4 && memcmp(magic, "\177ELF", 4) == 0;
}

// Preserve caller file actions and attributes while adapting the executable.
int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    int (*next)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *, char *const[], char *const[]) = dlsym(RTLD_NEXT, "posix_spawn");
    if (!next) return ENOSYS;
    const char *prefix = getenv("TERMUX__PREFIX");
    if ((prefix && split_payload(path, prefix)) || private_executable_elf(path)) {
        size_t count = 0;
        while (argv[count]) count++;
        char **args = calloc(count + 3, sizeof(char *));
        if (!args) return ENOMEM;
        args[0] = count ? argv[0] : (char *)path;
        args[1] = (char *)path;
        for (size_t i = 1; i < count; i++) args[i+1] = argv[i];
        int result = next(pid, "/system/bin/linker64", actions, attr, args, envp);
        free(args);
        return result;
    }
    return next(pid, path, actions, attr, argv, envp);
}
int posix_spawnp(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (strchr(path, '/')) return posix_spawn(pid, path, actions, attr, argv, envp);
    int (*next)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *, char *const[], char *const[]) = dlsym(RTLD_NEXT, "posix_spawnp");
    return next ? next(pid, path, actions, attr, argv, envp) : ENOSYS;
}
