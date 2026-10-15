// SPDX-License-Identifier: GPL-3.0-or-later
// Android forbids app-data hard links; the public API-30 no-replace rename keeps
// completed staging bytes atomic without overwriting a concurrently created file.
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif

static char *path_bytes(JNIEnv *env, jbyteArray value) {
    if (!value) { errno = EINVAL; return NULL; }
    jsize length = (*env)->GetArrayLength(env, value);
    if (length < 1 || length > 16384) { errno = EINVAL; return NULL; }
    char *result = malloc((size_t)length + 1);
    if (!result) { errno = ENOMEM; return NULL; }
    (*env)->GetByteArrayRegion(env, value, 0, length, (jbyte *)result);
    if ((*env)->ExceptionCheck(env)) { free(result); errno = EINVAL; return NULL; }
    if (memchr(result, '\0', (size_t)length) || result[0] != '/') { free(result); errno = EINVAL; return NULL; }
    result[length] = '\0';
    return result;
}

JNIEXPORT jint JNICALL Java_dev_ide_android_workbench_runtime_AndroidExternalWorkspaceFiles_renameNoReplace(
        JNIEnv *env, jobject receiver, jbyteArray source, jbyteArray target) {
    (void)receiver;
    int (*rename_no_replace)(int, const char *, int, const char *, unsigned int) = dlsym(RTLD_DEFAULT, "renameat2");
    if (!rename_no_replace) return ENOSYS;
    char *from = path_bytes(env, source);
    if (!from) return errno;
    char *to = path_bytes(env, target);
    if (!to) { int failure = errno; free(from); return failure; }
    int result = rename_no_replace(AT_FDCWD, from, AT_FDCWD, to, RENAME_NOREPLACE);
    int failure = result == 0 ? 0 : errno;
    free(from);
    free(to);
    return failure;
}
