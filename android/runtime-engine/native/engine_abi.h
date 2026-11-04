/* SPDX-License-Identifier: GPL-3.0-or-later */
#ifndef CODEASSIST_ENGINE_ABI_H
#define CODEASSIST_ENGINE_ABI_H
#include <stdint.h>

#define CA_ENGINE_ABI 1u
#if defined(__cplusplus)
extern "C" {
#endif
/* Exactly one run per process. No host VM handles cross this ABI. All strings are UTF-8.
 * Engine code is trusted same-UID code, not sandboxed. Caller owns all strings through run().
 * Cancel is best effort; the bound service enforces a separate hard process deadline. */
struct ca_run_v1 {
    uint32_t size;
    const char *home;
    const char *app_root;
    const char *work_dir;
    const char *entry;
    int argc;
    char **argv;
};
struct ca_engine_v1 {
    uint32_t abi;
    uint32_t size;
    const char *id;
    int (*run)(const struct ca_run_v1 *request);
    void (*cancel)(void);
};
__attribute__((visibility("default"))) const struct ca_engine_v1 *ca_get_engine_v1(void);
#if defined(__cplusplus)
}
#endif
#endif
