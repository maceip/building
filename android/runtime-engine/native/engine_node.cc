// SPDX-License-Identifier: GPL-3.0-or-later
#include "engine_abi.h"
#include <node.h>
#include <v8.h>
#include <atomic>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

static std::mutex mutex;
static node::Environment *active;
static std::atomic<bool> cancelled{false};
static void cancel_node() {
    cancelled.store(true);
    std::lock_guard<std::mutex> guard(mutex);
    if (active) node::Stop(active);
}

static int run_node(const ca_run_v1 *request) {
    if (!request || request->size != sizeof(*request)) return 126;
    // Permissions seal out child processes/workers/addons for predictable lifecycle;
    // filesystem access is still same-UID trusted code, NOT a security sandbox.
    std::vector<std::string> args{"app-node", "--permission", "--allow-fs-read=*",
                                 "--allow-fs-write=*", "--no-addons", request->entry};
    for (int i = 0; i < request->argc; ++i) args.emplace_back(request->argv[i]);
    auto init = node::InitializeOncePerProcess(args, {
        node::ProcessInitializationFlags::kDisableNodeOptionsEnv,
        node::ProcessInitializationFlags::kNoDefaultSignalHandling,
        node::ProcessInitializationFlags::kNoStdioInitialization,
        node::ProcessInitializationFlags::kNoParseGlobalDebugVariables,
        node::ProcessInitializationFlags::kNoAdjustResourceLimits,
        node::ProcessInitializationFlags::kNoInitializeV8,
        node::ProcessInitializationFlags::kNoInitializeNodeV8Platform});
    for (const auto &error : init->errors()) std::fprintf(stderr, "%s\n", error.c_str());
    if (init->early_return()) return init->exit_code();
    auto platform = node::MultiIsolatePlatform::Create(2);
    v8::V8::InitializePlatform(platform.get());
    if (!v8::V8::Initialize()) return 127;
    int code = 1;
    {
        std::vector<std::string> errors;
        auto flags = static_cast<node::EnvironmentFlags::Flags>(
                node::EnvironmentFlags::kTrackUnmanagedFds | node::EnvironmentFlags::kNoNativeAddons
                | node::EnvironmentFlags::kNoGlobalSearchPaths | node::EnvironmentFlags::kNoCreateInspector
                | node::EnvironmentFlags::kNoStartDebugSignalHandler);
        auto setup = node::CommonEnvironmentSetup::Create(platform.get(), &errors,
                                                          init->args(), init->exec_args(), flags);
        if (!setup) {
            for (const auto &error : errors) std::fprintf(stderr, "%s\n", error.c_str());
        } else {
            auto isolate = setup->isolate();
            auto env = setup->env();
            v8::Locker locker(isolate);
            v8::Isolate::Scope isolate_scope(isolate);
            v8::HandleScope handle_scope(isolate);
            v8::Context::Scope context_scope(setup->context());
            bool exited = false;
            node::SetProcessExitHandler(env, [&](node::Environment *current, int exit) {
                exited = true; code = exit; node::Stop(current);
            });
            {
                std::lock_guard<std::mutex> guard(mutex);
                active = env;
                if (cancelled.load()) node::Stop(env);
            }
            // Empty callback invokes Node's real main-module loader (CJS/ESM), not eval pretending to be Node.
            if (!cancelled.load()) {
                v8::TryCatch caught(isolate);
                auto loaded = node::LoadEnvironment(env, node::StartExecutionCallback{});
                if (loaded.IsEmpty()) {
                    // Unlike node's CLI main, the embedder is responsible for an
                    // exception escaping synchronous environment startup. Preserve
                    // the actual JS stack on the service's bounded stderr pipe.
                    if (caught.HasCaught() && !caught.HasTerminated()) {
                        v8::Local<v8::Value> stack;
                        if (!caught.StackTrace(setup->context()).ToLocal(&stack) || !stack->IsString()) stack = caught.Exception();
                        v8::String::Utf8Value text(isolate, stack);
                        if (*text) std::fprintf(stderr, "%s\n", *text);
                    }
                    if (!exited) code = 1;
                } else {
                    int loop_result = node::SpinEventLoop(env).FromMaybe(1);
                    if (!exited) code = loop_result;
                }
            }
            {
                std::lock_guard<std::mutex> guard(mutex);
                active = nullptr;
            }
            node::Stop(env);
        }
    }
    v8::V8::Dispose();
    v8::V8::DisposePlatform();
    node::TearDownOncePerProcess();
    return cancelled.load() ? 130 : code;
}
static const ca_engine_v1 api{CA_ENGINE_ABI, sizeof(api), "node", run_node, cancel_node};
extern "C" const ca_engine_v1 *ca_get_engine_v1() { return &api; }
