// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform.proof

import dev.ide.platform.Lock
import dev.ide.platform.log.Log

/**
 * **Permix-KMP**: Type-safe, zero-dependency multiplatform permissions matrix integrated with
 * **Ghosts of Departed Proofs ([Proof])**.
 *
 * Ports the TypeScript `permix` API (`createPermix`, `setup`, `check`, template rules) to Kotlin
 * Multiplatform and upgrades it so every granted permission check can mint a compile-time
 * [Proof]<[PermixFact.Permitted]<Resource, Action, TargetName>>.
 */
interface PermixAction {
    val actionName: String
}

interface PermixResource<in A : PermixAction, Target> {
    val resourceName: String
}

object PermixFact {
    /**
     * Compile-time ghost proof that [A] on [R] for the named target [TargetName]
     * was checked and permitted by a [Permix] policy instance.
     */
    interface Permitted<out R : PermixResource<*, *>, out A : PermixAction, TargetName>
}

class PermixException(message: String) : IllegalStateException(message)

/**
 * Thread-safe multiplatform permission container.
 *
 * Every `prove(...)` or `requireProof(...)` call evaluates the active policy rules and, when allowed,
 * mints a [Proof]<[PermixFact.Permitted]> that downstream sensitive functions can demand in their
 * parameter or `context(...)` signature.
 */
class Permix {
    private val lock = Lock()
    private val rules = mutableMapOf<RuleKey, (Any?) -> Boolean>()
    private val auditListeners = mutableListOf<(PermixAuditEvent) -> Unit>()

    internal data class RuleKey(val resource: String, val action: String)

    data class PermixAuditEvent(
        val resource: String,
        val action: String,
        val targetSummary: String,
        val granted: Boolean,
    )

    class SetupScope internal constructor() {
        internal val configured = mutableMapOf<RuleKey, (Any?) -> Boolean>()

        fun <A : PermixAction, T, R : PermixResource<A, T>> allow(resource: R, action: A) {
            configured[RuleKey(resource.resourceName, action.actionName)] = { true }
        }

        fun <A : PermixAction, T, R : PermixResource<A, T>> deny(resource: R, action: A) {
            configured[RuleKey(resource.resourceName, action.actionName)] = { false }
        }

        @Suppress("UNCHECKED_CAST")
        fun <A : PermixAction, T, R : PermixResource<A, T>> rule(
            resource: R,
            action: A,
            predicate: (T) -> Boolean,
        ) {
            configured[RuleKey(resource.resourceName, action.actionName)] = { target ->
                predicate(target as T)
            }
        }
    }

    /** Atomically replaces the active permission rules with those defined in [block]. */
    fun setup(block: SetupScope.() -> Unit) {
        val scope = SetupScope().apply(block)
        lock.withLock {
            rules.clear()
            rules.putAll(scope.configured)
        }
    }

    fun addAuditListener(listener: (PermixAuditEvent) -> Unit) {
        lock.withLock { auditListeners += listener }
    }

    /** Evaluates whether [action] on [resource] is permitted for [target]. */
    fun <A : PermixAction, T, R : PermixResource<A, T>> check(
        resource: R,
        action: A,
        target: T,
    ): Boolean {
        val key = RuleKey(resource.resourceName, action.actionName)
        val predicate = lock.withLock { rules[key] }
        val granted = predicate?.invoke(target) ?: false
        val event = PermixAuditEvent(
            resource = resource.resourceName,
            action = action.actionName,
            targetSummary = target.toString().take(80),
            granted = granted,
        )
        val listeners = lock.withLock { auditListeners.toList() }
        listeners.forEach { runCatching { it(event) } }
        return granted
    }

    /**
     * Evaluates the permission rule for [namedTarget] and, if permitted, mints an unforgeable
     * [Proof]<[PermixFact.Permitted]<R, A, N>>. Returns `null` if denied.
     */
    fun <A : PermixAction, T, R : PermixResource<A, T>, N> prove(
        resource: R,
        action: A,
        namedTarget: Named<N, T>,
    ): Proof<PermixFact.Permitted<R, A, N>>? {
        if (!check(resource, action, namedTarget.value)) return null
        return Proof("Permitted(${resource.resourceName}:${action.actionName}:${namedTarget.value})")
    }

    /**
     * Evaluates the permission rule for [namedTarget] and returns a [Proof] or throws [PermixException].
     */
    fun <A : PermixAction, T, R : PermixResource<A, T>, N> requireProof(
        resource: R,
        action: A,
        namedTarget: Named<N, T>,
    ): Proof<PermixFact.Permitted<R, A, N>> =
        prove(resource, action, namedTarget)
            ?: throw PermixException(
                "Permission denied for ${resource.resourceName}.${action.actionName} on '${namedTarget.value}'"
            )
}

// ---------------------------------------------------------------------------------------------------
// Built-in IDE Agent Permix Schema (suggest-only / ask-each / full-auto)
// ---------------------------------------------------------------------------------------------------

enum class AgentPolicyTier {
    /** Suggest only — agent can read & plan, but cannot write files, run commands, or install packages. */
    PLAN_ONLY,
    /** Edit & build — agent can read, write workspace files, and run builds; asks before package installs or git pushes. */
    ASK_EACH,
    /** Full auto in this project — all workspace actions permitted within project bounds. */
    AUTO_ACCEPT,
}

sealed class AgentAction(override val actionName: String) : PermixAction {
    data object Read : AgentAction("read")
    data object Write : AgentAction("write")
    data object Delete : AgentAction("delete")
    data object Execute : AgentAction("execute")
    data object InstallPackage : AgentAction("install_package")
    data object GitPush : AgentAction("git_push")
}

object AgentResources {
    object WorkspaceFile : PermixResource<AgentAction, String> {
        override val resourceName: String = "workspace.file"
    }

    object ShellCommand : PermixResource<AgentAction, String> {
        override val resourceName: String = "workbench.shell"
    }

    object PackageManager : PermixResource<AgentAction, String> {
        override val resourceName: String = "workbench.packages"
    }

    object GitRemote : PermixResource<AgentAction, String> {
        override val resourceName: String = "vcs.remote"
    }
}

/**
 * Factory for configuring a [Permix] instance from the user's selected [AgentPolicyTier].
 */
object AgentPermixTemplates {
    private val logger = Log.logger("Permix")

    fun forTier(
        tier: AgentPolicyTier,
        workspaceRoot: String = "/",
        approvalCallback: ((resource: String, action: String, target: String) -> Boolean)? = null,
    ): Permix = Permix().apply {
        addAuditListener { event ->
            if (!event.granted) {
                logger.warn("Permission denied: ${event.resource}.${event.action} on ${event.targetSummary}")
            }
        }
        setup {
            val normRoot = workspaceRoot.replace('\\', '/').trimEnd('/')
            fun isInsideWorkspace(path: String): Boolean {
                val norm = path.replace('\\', '/')
                return !norm.split('/').contains("..") &&
                    (normRoot.isEmpty() || norm == normRoot || norm.startsWith("$normRoot/"))
            }

            // Reading inside the workspace is allowed in all tiers
            rule(AgentResources.WorkspaceFile, AgentAction.Read) { path -> isInsideWorkspace(path) }

            when (tier) {
                AgentPolicyTier.PLAN_ONLY -> {
                    deny(AgentResources.WorkspaceFile, AgentAction.Write)
                    deny(AgentResources.WorkspaceFile, AgentAction.Delete)
                    deny(AgentResources.ShellCommand, AgentAction.Execute)
                    deny(AgentResources.PackageManager, AgentAction.InstallPackage)
                    deny(AgentResources.GitRemote, AgentAction.GitPush)
                }

                AgentPolicyTier.ASK_EACH -> {
                    rule(AgentResources.WorkspaceFile, AgentAction.Write) { path -> isInsideWorkspace(path) }
                    rule(AgentResources.WorkspaceFile, AgentAction.Delete) { path ->
                        isInsideWorkspace(path) && (approvalCallback?.invoke("workspace.file", "delete", path) ?: false)
                    }
                    rule(AgentResources.ShellCommand, AgentAction.Execute) { cmd ->
                        cmd.isNotBlank() && (approvalCallback?.invoke("workbench.shell", "execute", cmd) ?: true)
                    }
                    rule(AgentResources.PackageManager, AgentAction.InstallPackage) { pkg ->
                        approvalCallback?.invoke("workbench.packages", "install_package", pkg) ?: false
                    }
                    rule(AgentResources.GitRemote, AgentAction.GitPush) { remote ->
                        approvalCallback?.invoke("vcs.remote", "git_push", remote) ?: false
                    }
                }

                AgentPolicyTier.AUTO_ACCEPT -> {
                    rule(AgentResources.WorkspaceFile, AgentAction.Write) { path -> isInsideWorkspace(path) }
                    rule(AgentResources.WorkspaceFile, AgentAction.Delete) { path -> isInsideWorkspace(path) }
                    rule(AgentResources.ShellCommand, AgentAction.Execute) { cmd -> cmd.isNotBlank() }
                    rule(AgentResources.PackageManager, AgentAction.InstallPackage) { pkg -> pkg.isNotBlank() }
                    rule(AgentResources.GitRemote, AgentAction.GitPush) { remote -> remote.isNotBlank() }
                }
            }
        }
    }
}
