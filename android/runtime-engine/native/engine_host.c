/* SPDX-License-Identifier: GPL-3.0-or-later */
#define _GNU_SOURCE
#include "engine_abi.h"
#include <jni.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static _Atomic int used;
static _Atomic int cancelled;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static const struct ca_engine_v1 *engine;

static void fail(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}
static char *bytes(JNIEnv *env, jobjectArray array, int index) {
    jbyteArray value = (jbyteArray)(*env)->GetObjectArrayElement(env, array, index);
    if (!value) return NULL;
    jsize length = (*env)->GetArrayLength(env, value);
    if (length > 32768) { (*env)->DeleteLocalRef(env, value); return NULL; }
    char *text = malloc((size_t)length + 1);
    if (text) {
        (*env)->GetByteArrayRegion(env, value, 0, length, (jbyte *)text);
        text[length] = 0;
        if (memchr(text, 0, (size_t)length)) { free(text); text = NULL; }
    }
    (*env)->DeleteLocalRef(env, value);
    return text;
}

JNIEXPORT jint JNICALL Java_dev_ide_runtime_engine_NativeBridge_run(
        JNIEnv *env, jclass type, jobjectArray fields, jobjectArray arguments,
        jint input, jint output, jint error) {
    (void)type;
    int result = 127, redirected = 0, saved[3] = {-1, -1, -1};
    char *values[6] = {0};
    char **argv = NULL;
    int argc = 0;
    if (atomic_exchange(&used, 1)) { fail(env, "Engine process already used"); goto done; }
    if (!fields || !arguments || (*env)->GetArrayLength(env, fields) != 6) {
        fail(env, "Invalid engine ABI request"); goto done;
    }
    for (int i = 0; i < 6; ++i) {
        values[i] = bytes(env, fields, i);
        if (!values[i]) { fail(env, "Invalid UTF-8 request field"); goto done; }
    }
    if (strcmp(values[0], "node") && strcmp(values[0], "python")) {
        fail(env, "Unsupported engine"); goto done;
    }
    argc = (*env)->GetArrayLength(env, arguments);
    if (argc > 128) { fail(env, "Too many arguments"); goto done; }
    argv = calloc((size_t)argc + 1, sizeof(char *));
    if (!argv) { fail(env, "Allocation failure"); goto done; }
    for (int i = 0; i < argc; ++i) {
        argv[i] = bytes(env, arguments, i);
        if (!argv[i]) { fail(env, "Invalid UTF-8 argument"); goto done; }
    }
    char library[PATH_MAX];
    if (snprintf(library, sizeof library, "%s/libengine_%s.so", values[1], values[0]) >= (int)sizeof library) {
        fail(env, "Library path too long"); goto done;
    }
    void *handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (!handle) { fail(env, dlerror()); goto done; }
    const struct ca_engine_v1 *(*get_api)(void) = dlsym(handle, "ca_get_engine_v1");
    const struct ca_engine_v1 *api = get_api ? get_api() : NULL;
    if (!api || api->abi != CA_ENGINE_ABI || api->size != sizeof(*api)
            || strcmp(api->id, values[0]) || !api->run || !api->cancel) {
        fail(env, "Engine ABI/version mismatch"); goto done;
    }
    /* Libraries deliberately remain loaded until the service process retires. */
    if (chdir(values[4]) || setenv("HOME", values[4], 1) || setenv("TMPDIR", values[4], 1)) {
        fail(env, "Cannot set private engine working directory"); goto done;
    }
    unsetenv("NODE_OPTIONS"); unsetenv("NODE_PATH"); unsetenv("PYTHONHOME"); unsetenv("PYTHONPATH");
    unsetenv("LD_PRELOAD"); unsetenv("LD_LIBRARY_PATH");
    setenv("PYTHONDONTWRITEBYTECODE", "1", 1);
    int descriptors[3] = {input, output, error};
    for (int i = 0; i < 3; ++i) {
        saved[i] = fcntl(i, F_DUPFD_CLOEXEC, 3);
        if (saved[i] < 0 || dup2(descriptors[i], i) < 0) {
            fail(env, "Cannot attach stdio pipes"); goto done;
        }
        redirected = i + 1;
    }
    setvbuf(stdout, NULL, _IONBF, 0); setvbuf(stderr, NULL, _IONBF, 0);
    pthread_mutex_lock(&lock); engine = api; pthread_mutex_unlock(&lock);
    if (atomic_load(&cancelled)) { result = 130; goto done; }
    struct ca_run_v1 request = {sizeof(request), values[2], values[3], values[4], values[5], argc, argv};
    result = api->run(&request);
    fflush(NULL);
 done:
    pthread_mutex_lock(&lock); engine = NULL; pthread_mutex_unlock(&lock);
    for (int i = 0; i < redirected; ++i) dup2(saved[i], i);
    for (int i = 0; i < 3; ++i) if (saved[i] >= 0) close(saved[i]);
    close(input); close(output); close(error);
    if (argv) { for (int i = 0; i < argc; ++i) free(argv[i]); free(argv); }
    for (int i = 0; i < 6; ++i) free(values[i]);
    return result;
}

JNIEXPORT void JNICALL Java_dev_ide_runtime_engine_NativeBridge_cancel(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    atomic_store(&cancelled, 1);
    pthread_mutex_lock(&lock);
    if (engine) engine->cancel();
    pthread_mutex_unlock(&lock);
}

JNIEXPORT jint JNICALL Java_dev_ide_runtime_engine_NativeBridge_writeInput(
        JNIEnv *env, jclass type, jint fd, jbyteArray data) {
    (void)type;
    if (!data) return -1;
    jsize size = (*env)->GetArrayLength(env, data);
    if (size < 1 || size > 4096) return -1;
    jbyte buffer[4096];
    (*env)->GetByteArrayRegion(env, data, 0, size, buffer);
    if ((*env)->ExceptionCheck(env)) return -1;
    int flags = fcntl(fd, F_GETFL);
    if (flags < 0 || fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) return -1;
    ssize_t count;
    do { count = write(fd, buffer, (size_t)size); } while (count < 0 && errno == EINTR);
    return count < 0 ? ((errno == EAGAIN || errno == EWOULDBLOCK) ? 0 : -1) : (jint)count;
}
