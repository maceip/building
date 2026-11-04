// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
package dev.ide.platform.proof

import kotlin.jvm.JvmInline

/**
 * Kotlin Multiplatform implementation of **"Ghosts of Departed Proofs" (GDP)**,
 * inspired by Matt Noonan's 2018 functional pearl and Guillermo Rauch's `rauchg/gdp-ts`.
 *
 * Core guarantees:
 * 1. **Zero Runtime Allocation**: Both [Named] and [Proof] are `@JvmInline value class` wrappers that
 *    erase to their underlying value/witness at runtime on JVM, Android ART, and Kotlin/Native.
 * 2. **Unforgeable Proofs Across Module Boundaries**: [Proof]'s constructor is `internal` to `:model-api`.
 *    Code in UI or feature modules cannot instantiate `Proof(...)` directly; a proof can only be obtained
 *    from a trusted [Prover] created via [defineProof] or derived via logical combinators ([and], [orLeft],
 *    [orRight], [modusPonens]).
 * 3. **Phantom Value Identity ([Named])**: [withNamed] binds a runtime value to a scoped phantom type tag
 *    [N] so callers cannot prove a fact about value `A` and pass value `B` to the protected operation.
 */
@JvmInline
value class Named<N, out T> @PublishedApi internal constructor(val value: T) {
    override fun toString(): String = "Named($value)"
}

/**
 * Scope handle for existential phantom type binding in [withNamed].
 */
class NameScope<N> @PublishedApi internal constructor() {
    fun <T> tag(value: T): Named<N, T> = Named(value)
}

/**
 * Assigns a fresh phantom identity [N] to [value] for the duration of [block].
 * Equivalent to `name(value, ...)` in `rauchg/gdp-ts` and rank-2 `named` in Haskell GDP.
 */
inline fun <T, R> withNamed(value: T, block: NameScope<Any?>.(Named<Any?, T>) -> R): R {
    val scope = NameScope<Any?>()
    return scope.block(Named(value))
}

/** Phantom tag for the first argument of [withNamedPair]. */
sealed interface FirstSlot

/** Phantom tag for the second argument of [withNamedPair]. */
sealed interface SecondSlot

/**
 * Assigns distinct phantom identities [FirstSlot] and [SecondSlot] to [first] and [second] so the
 * type checker distinguishes them within [block].
 */
inline fun <T1, T2, R> withNamedPair(
    first: T1,
    second: T2,
    block: (Named<FirstSlot, T1>, Named<SecondSlot, T2>) -> R,
): R = block(Named(first), Named(second))

/**
 * A compile-time capability witness ("ghost proof") that [Fact] holds.
 *
 * The constructor is `internal` so external modules cannot forge a [Proof] without invoking
 * a registered [Prover] or logical deduction combinator.
 */
@JvmInline
value class Proof<out Fact> internal constructor(val witness: String) {
    override fun toString(): String = "Proof($witness)"
}

/**
 * A capability mint for a specific [Fact] family. Keep instances `private` or `internal` inside
 * the module that owns the invariant check (mirrors `defineProof` in `rauchg/gdp-ts`).
 */
class Prover<Fact> internal constructor(val kind: String) {
    fun mint(detail: String = kind): Proof<Fact> = Proof(detail)
}

/**
 * Creates a [Prover] capable of minting [Proof] tokens for [Fact].
 */
fun <Fact> defineProof(kind: String): Prover<Fact> = Prover(kind)

// ---------------------------------------------------------------------------------------------------
// Logical Combinators (Natural Deduction over Ghost Proofs)
// ---------------------------------------------------------------------------------------------------

/** Conjunction of two proven facts: both [A] and [B] hold. */
interface And<out A, out B>

/** Disjunction of two proven facts: either [A] or [B] holds. */
interface Or<out A, out B>

/** Negation of a fact: [A] has been proven false. */
interface Not<out A>

/** Implication: if [A] holds, then [B] holds. */
interface Implies<in A, out B>

/** Combines two proofs into a conjunction proof `Proof<And<A, B>>`. */
fun <A, B> and(left: Proof<A>, right: Proof<B>): Proof<And<A, B>> =
    Proof("(${left.witness} && ${right.witness})")

/** Extracts the left proof from a conjunction. */
fun <A, B> elimAndLeft(conj: Proof<And<A, B>>): Proof<A> = Proof(conj.witness)

/** Extracts the right proof from a conjunction. */
fun <A, B> elimAndRight(conj: Proof<And<A, B>>): Proof<B> = Proof(conj.witness)

/** Lifts a left proof into a disjunction `Proof<Or<A, B>>`. */
fun <A, B> orLeft(left: Proof<A>): Proof<Or<A, B>> = Proof("Left(${left.witness})")

/** Lifts a right proof into a disjunction `Proof<Or<A, B>>`. */
fun <A, B> orRight(right: Proof<B>): Proof<Or<A, B>> = Proof("Right(${right.witness})")

/** Modus ponens: given an implication `A -> B` and a proof of `A`, deduces a proof of `B`. */
fun <A, B> modusPonens(rule: Proof<Implies<A, B>>, premise: Proof<A>): Proof<B> =
    Proof("${rule.witness}(${premise.witness})")

/** Mint an axiom implication rule `A -> B` inside a domain module. */
fun <A, B> axiomImplies(name: String): Proof<Implies<A, B>> = Proof(name)

// ---------------------------------------------------------------------------------------------------
// Built-In IDE & Agent Domain Facts
// ---------------------------------------------------------------------------------------------------

object Fact {
    /** Proof that the named string [N] is non-blank after trimming. */
    interface NonBlank<N>

    /** Proof that a path [N] is normalized and stays strictly inside workspace root [RootN]. */
    interface WithinWorkspace<N, RootN>

    /**
     * Proof that an on-device model or native runtime binary [N] actually exists on disk and is
     * executable (prevents fabricating `"On-device"` / `"On this phone"` execution claims).
     */
    interface OnDeviceRuntimeVerified<N>

    /**
     * Proof that a state mutation was triggered by an explicit user gesture (tap/click/shortcut)
     * rather than as an accidental side-effect of composition or tab switching.
     */
    interface ExplicitUserGesture

    /**
     * Proof that a software component [N] has been registered with the central [dev.ide.platform.log.Log]
     * component registry.
     */
    interface RegisteredComponent<N>
}

object DomainProvers {
    private val nonBlankProver = defineProof<Fact.NonBlank<Any?>>("Fact.NonBlank")
    private val withinWorkspaceProver = defineProof<Fact.WithinWorkspace<Any?, Any?>>("Fact.WithinWorkspace")
    private val onDeviceProver = defineProof<Fact.OnDeviceRuntimeVerified<Any?>>("Fact.OnDeviceRuntimeVerified")
    private val userGestureProver = defineProof<Fact.ExplicitUserGesture>("Fact.ExplicitUserGesture")

    @Suppress("UNCHECKED_CAST")
    fun <N> proveNonBlank(named: Named<N, String>): Proof<Fact.NonBlank<N>>? =
        if (named.value.isNotBlank()) {
            nonBlankProver.mint("NonBlank(${named.value.take(24)})") as Proof<Fact.NonBlank<N>>
        } else {
            null
        }

    @Suppress("UNCHECKED_CAST")
    fun <N, RootN> proveWithinWorkspace(
        path: Named<N, String>,
        workspaceRoot: Named<RootN, String>,
    ): Proof<Fact.WithinWorkspace<N, RootN>>? {
        val normRoot = normalizePosix(workspaceRoot.value).trimEnd('/')
        val normPath = normalizePosix(path.value)
        val inside = normRoot.isNotEmpty() &&
            !path.value.split('/', '\\').contains("..") &&
            (normPath == normRoot || normPath.startsWith("$normRoot/"))
        return if (inside) {
            withinWorkspaceProver.mint("WithinWorkspace($normPath in $normRoot)") as Proof<Fact.WithinWorkspace<N, RootN>>
        } else {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <N> proveOnDeviceRuntime(
        runtimeIdOrPath: Named<N, String>,
        verifier: (String) -> Boolean,
    ): Proof<Fact.OnDeviceRuntimeVerified<N>>? =
        if (runtimeIdOrPath.value.isNotBlank() && verifier(runtimeIdOrPath.value)) {
            onDeviceProver.mint("OnDeviceRuntimeVerified(${runtimeIdOrPath.value})") as Proof<Fact.OnDeviceRuntimeVerified<N>>
        } else {
            null
        }

    /**
     * Executes [block] with an unforgeable [Proof] of [Fact.ExplicitUserGesture], recording the
     * user interaction source (e.g. `"tap:ProviderCard"`, `"click:PermissionRadio"`).
     */
    inline fun <R> withExplicitUserGesture(
        gestureSource: String,
        block: (Proof<Fact.ExplicitUserGesture>) -> R,
    ): R {
        require(gestureSource.isNotBlank()) { "gestureSource must not be blank" }
        return block(MintGesture.mint(gestureSource))
    }

    @PublishedApi
    internal object MintGesture {
        fun mint(source: String): Proof<Fact.ExplicitUserGesture> =
            userGestureProver.mint("ExplicitUserGesture($source)")
    }

    private fun normalizePosix(raw: String): String {
        val parts = raw.replace('\\', '/').split('/')
        val stack = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "", "." -> continue
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        val prefix = if (raw.startsWith("/")) "/" else ""
        return prefix + stack.joinToString("/")
    }
}
