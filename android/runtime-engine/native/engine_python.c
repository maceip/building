/* SPDX-License-Identifier: GPL-3.0-or-later */
#include "engine_abi.h"
#include <Python.h>
#include <stdatomic.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static _Atomic int cancelled;
static pthread_mutex_t state_lock = PTHREAD_MUTEX_INITIALIZER;
static int running;
static int pending_interrupt(void *unused) {
    (void)unused;
    PyErr_SetNone(PyExc_KeyboardInterrupt);
    return -1;
}
static void cancel_python(void) {
    atomic_store(&cancelled, 1);
    pthread_mutex_lock(&state_lock);
    if (running) Py_AddPendingCall(pending_interrupt, NULL);
    pthread_mutex_unlock(&state_lock);
}
/* A supported-capability boundary, NOT a security sandbox. No managed child
 * process or writable native-code loader exists in this embedding profile. */
static int audit(const char *event, PyObject *args, void *unused) {
    (void)unused;
    if (!strcmp(event, "os.system") || !strcmp(event, "os.exec") || !strcmp(event, "os.fork")
            || !strcmp(event, "os.forkpty") || !strcmp(event, "os.posix_spawn")
            || !strcmp(event, "subprocess.Popen") || !strncmp(event, "ctypes.", 7)) {
        PyErr_SetString(PyExc_PermissionError, "Subprocesses/native extensions are not supported by this app engine");
        return -1;
    }
    if (!strcmp(event, "import") && PyTuple_Check(args) && PyTuple_Size(args) > 1) {
        PyObject *filename = PyTuple_GetItem(args, 1);
        if (PyUnicode_Check(filename)) {
            const char *path = PyUnicode_AsUTF8(filename);
            if (!path) return -1;
            size_t n = strlen(path);
            if ((n >= 3 && !strcmp(path + n - 3, ".so")) || (n >= 4 && !strcmp(path + n - 4, ".pyd"))) {
                PyErr_SetString(PyExc_ImportError, "Only built-in or bundled pure-Python modules are supported");
                return -1;
            }
        }
    }
    return 0;
}
/* Do not use PyErr_Print for SystemExit (it exits the process), or Py_RunMain
 * for KeyboardInterrupt (Modules/main.c:exit_sigint re-raises OS SIGINT).
 * A bound service owns terminal-state delivery, not a command-line interpreter. */
static int exception_code(void) {
    PyObject *error = PyErr_GetRaisedException();
    if (!error) return 1;
    int result = 1;
    if (PyObject_IsInstance(error, PyExc_SystemExit) == 1) {
        PyObject *code = PyObject_GetAttrString(error, "code");
        if (!code) { PyErr_Clear(); result = 1; }
        else if (code == Py_None) result = 0;
        else if (PyLong_Check(code)) {
            long value = PyLong_AsLong(code);
            if (PyErr_Occurred()) { PyErr_Clear(); result = 1; }
            else result = (int)(value & 255);
        } else {
            PyObject *text = PyObject_Str(code);
            if (text) {
                const char *utf8 = PyUnicode_AsUTF8(text);
                if (utf8) fprintf(stderr, "%s\n", utf8);
                Py_DECREF(text);
            }
            PyErr_Clear();
        }
        Py_XDECREF(code);
    } else {
        if (PyObject_IsInstance(error, PyExc_KeyboardInterrupt) == 1) result = 130;
        PyErr_DisplayException(error); /* Prints real chained traceback, never exits. */
    }
    Py_DECREF(error);
    return result;
}
static int run_python(const struct ca_run_v1 *request) {
    if (!request || request->size != sizeof(*request)) return 126;
    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.use_environment = 0;
    config.site_import = 0;
    config.user_site_directory = 0;
    config.write_bytecode = 0;
    config.install_signal_handlers = 0; /* ART owns process signals. */
    config.buffered_stdio = 0;
    config.parse_argv = 0;
    config.module_search_paths_set = 1;
    PyStatus status;
    char **argv = calloc((size_t)request->argc + 2, sizeof(char *));
    if (!argv) { PyConfig_Clear(&config); return 127; }
    argv[0] = (char *)request->entry;
    for (int i = 0; i < request->argc; ++i) argv[i + 1] = request->argv[i];
    status = PyConfig_SetBytesArgv(&config, request->argc + 1, argv);
    free(argv);
    if (PyStatus_Exception(status)) goto failed;
    status = PyConfig_SetBytesString(&config, &config.program_name, "app-python");
    if (PyStatus_Exception(status)) goto failed;
    status = PyConfig_SetBytesString(&config, &config.home, request->home);
    if (PyStatus_Exception(status)) goto failed;
    status = PyConfig_SetBytesString(&config, &config.run_filename, request->entry);
    if (PyStatus_Exception(status)) goto failed;
    size_t length = strlen(request->home) + 32;
    char *stdlib = malloc(length);
    if (!stdlib) { PyConfig_Clear(&config); return 127; }
    snprintf(stdlib, length, "%s/python314.zip", request->home);
    wchar_t *path = Py_DecodeLocale(stdlib, NULL);
    free(stdlib);
    if (!path) { PyConfig_Clear(&config); return 127; }
    status = PyWideStringList_Append(&config.module_search_paths, path);
    PyMem_RawFree(path);
    if (PyStatus_Exception(status)) goto failed;
    path = Py_DecodeLocale(request->app_root, NULL);
    if (!path) { PyConfig_Clear(&config); return 127; }
    status = PyWideStringList_Append(&config.module_search_paths, path);
    PyMem_RawFree(path);
    if (PyStatus_Exception(status)) goto failed;
    if (PySys_AddAuditHook(audit, NULL) < 0) { PyConfig_Clear(&config); return 127; }
    status = Py_InitializeFromConfig(&config);
    if (PyStatus_Exception(status)) goto failed;
    PyConfig_Clear(&config);
    /* CPython 3.14 Android initializes Python streams to logcat unconditionally.
     * Use the public file-descriptor IO API to reconnect to the already-installed
     * bounded pipes, without any private CPython ABI or interpreter-source patch. */
    if (PyRun_SimpleString("import io, sys\n"
            "sys.stdin = io.TextIOWrapper(io.FileIO(0, 'r', closefd=False), encoding='utf-8')\n"
            "sys.stdout = io.TextIOWrapper(io.FileIO(1, 'w', closefd=False), encoding='utf-8', write_through=True)\n"
            "sys.stderr = io.TextIOWrapper(io.FileIO(2, 'w', closefd=False), encoding='utf-8', write_through=True)\n"
            "sys.__stdin__, sys.__stdout__, sys.__stderr__ = sys.stdin, sys.stdout, sys.stderr\n") != 0) {
        Py_FinalizeEx(); return 127;
    }
    int result = 0;
    PyObject *main_module = PyImport_AddModule("__main__"); /* borrowed */
    PyObject *globals = main_module ? PyModule_GetDict(main_module) : NULL;
    PyObject *filename = PyUnicode_DecodeFSDefault(request->entry);
    PyObject *parent = filename ? PyObject_CallMethod(filename, "rsplit", "si", "/", 1) : NULL;
    if (!globals || !filename || !parent
            || PyDict_SetItemString(globals, "__file__", filename) < 0
            || PyDict_SetItemString(globals, "__package__", Py_None) < 0
            || PyDict_SetItemString(globals, "__spec__", Py_None) < 0) {
        result = exception_code();
    } else {
        PyObject *search = PySys_GetObject("path"); /* borrowed */
        if (!search || PyList_Insert(search, 0, PyList_GetItem(parent, 0)) < 0) result = exception_code();
        else {
            FILE *file = fopen(request->entry, "rb");
            if (!file) { fprintf(stderr, "Cannot open bundled Python entry\n"); result = 126; }
            else {
                pthread_mutex_lock(&state_lock);
                running = 1;
                int stopped = atomic_load(&cancelled);
                pthread_mutex_unlock(&state_lock);
                if (stopped) { fclose(file); result = 130; }
                else {
                    PyObject *value = PyRun_FileExFlags(file, request->entry, Py_file_input, globals, globals, 1, NULL);
                    if (!value) result = exception_code();
                    else Py_DECREF(value);
                }
            }
        }
    }
    /* Pair with cancel's mutex before finalizing: no Py_AddPendingCall can race
     * with the destruction of the interpreter's pending-call queue. */
    pthread_mutex_lock(&state_lock); running = 0; pthread_mutex_unlock(&state_lock);
    Py_XDECREF(parent); Py_XDECREF(filename);
    if (Py_FinalizeEx() < 0 && result == 0) result = 120;
    return atomic_load(&cancelled) ? 130 : result;
 failed:
    fprintf(stderr, "Python initialization: %s\n", status.err_msg ? status.err_msg : "configuration failed");
    int code = PyStatus_IsExit(status) ? status.exitcode : 127;
    PyConfig_Clear(&config);
    return code;
}
static const struct ca_engine_v1 api = {CA_ENGINE_ABI, sizeof(api), "python", run_python, cancel_python};
const struct ca_engine_v1 *ca_get_engine_v1(void) { return &api; }
