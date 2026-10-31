package com.pureswift.swiftui

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/// One entry in a node's ordered modifier chain. Order is significant: the
/// interpreter folds the list into a Compose `Modifier` in list order
/// (outermost SwiftUI modifier first).
@Immutable
@Serializable
data class ModifierNode(
    val kind: String,
    val args: JsonObject = JsonObject(emptyMap()),
)

/// A node of the Swift-evaluated view tree.
///
/// `id` is the view's structural identity path — stable across re-evaluation,
/// so Compose identity (`key`, `remember`) is keyed to it and UI state
/// (scroll, cursor, animation) survives updates.
///
/// Immutable with structural equality: assigning a new tree to the store
/// recomposes only subtrees that actually changed.
@Immutable
@Serializable
data class ViewNode(
    val type: String,
    val id: String,
    val props: JsonObject = JsonObject(emptyMap()),
    val modifiers: List<ModifierNode> = emptyList(),
    val children: List<ViewNode> = emptyList(),
    // lazy containers only; present in the schema from day one
    val count: Int? = null,
    val itemProviderId: Long? = null,
) {

    /// Bridge constructor: the Swift materializer builds nodes through this,
    /// crossing JNI with flat arrays (one call per node, arrays as single
    /// arguments). Every value crosses typed — a kind tag selects the string
    /// slot or the bits slot (doubles bit-cast into the long, so both 64-bit
    /// callback ids and doubles cross exactly). Homogeneous arrays ride the
    /// same slot: the kind marks the element type and the bits slot packs
    /// (offset << 32 | count) into the node's shared string or long pool. Only
    /// a heterogeneous/nested array (alert `buttons`, `searches`) still crosses
    /// as a JSON literal. Modifier args flatten into one run of slots,
    /// `modifierArgCounts` giving each modifier's share. Negative count/provider
    /// mean "absent".
    constructor(
        type: String,
        id: String,
        propKeys: Array<String>,
        propKinds: IntArray,
        propStrings: Array<String>,
        propBits: LongArray,
        modifierKinds: Array<String>,
        modifierArgCounts: IntArray,
        argKeys: Array<String>,
        argKinds: IntArray,
        argStrings: Array<String>,
        argBits: LongArray,
        stringPool: Array<String>,
        longPool: LongArray,
        children: Array<ViewNode>,
        count: Int,
        itemProviderId: Long,
    ) : this(
        type = type,
        id = id,
        props = JsonObject(propKeys.indices.associate {
            propKeys[it] to jsonValue(propKinds[it], propStrings[it], propBits[it], stringPool, longPool)
        }),
        modifiers = buildList {
            var base = 0
            for (m in modifierKinds.indices) {
                val argCount = modifierArgCounts[m]
                add(ModifierNode(modifierKinds[m], JsonObject((0 until argCount).associate {
                    val i = base + it
                    argKeys[i] to jsonValue(argKinds[i], argStrings[i], argBits[i], stringPool, longPool)
                })))
                base += argCount
            }
        },
        children = children.toList(),
        count = if (count >= 0) count else null,
        itemProviderId = if (itemProviderId >= 0) itemProviderId else null,
    )

    companion object {
        // scalar value kinds
        private const val KIND_STRING = 0
        private const val KIND_DOUBLE = 1
        private const val KIND_BOOL = 2
        private const val KIND_INT = 3
        private const val KIND_JSON = 4
        // homogeneous-array kinds: bits packs (offset << 32 | count) into a pool
        private const val KIND_STRING_ARRAY = 5
        private const val KIND_DOUBLE_ARRAY = 6
        private const val KIND_BOOL_ARRAY = 7
        private const val KIND_INT_ARRAY = 8

        private fun jsonValue(
            kind: Int,
            string: String,
            bits: Long,
            stringPool: Array<String>,
            longPool: LongArray,
        ): kotlinx.serialization.json.JsonElement =
            when (kind) {
                KIND_STRING -> JsonPrimitive(string)
                KIND_DOUBLE -> JsonPrimitive(Double.fromBits(bits))
                KIND_BOOL -> JsonPrimitive(bits != 0L)
                KIND_INT -> JsonPrimitive(bits)
                KIND_JSON -> Json.parseToJsonElement(string) // nested/mixed arrays only
                else -> {
                    val offset = (bits ushr 32).toInt()
                    val count = (bits and 0xFFFFFFFFL).toInt()
                    JsonArray((0 until count).map { i ->
                        when (kind) {
                            KIND_STRING_ARRAY -> JsonPrimitive(stringPool[offset + i])
                            KIND_DOUBLE_ARRAY -> JsonPrimitive(Double.fromBits(longPool[offset + i]))
                            KIND_BOOL_ARRAY -> JsonPrimitive(longPool[offset + i] != 0L)
                            else -> JsonPrimitive(longPool[offset + i]) // KIND_INT_ARRAY
                        }
                    })
                }
            }
    }

    // Typed prop accessors used by the interpreter.

    fun string(key: String): String? = (props[key] as? JsonPrimitive)?.content

    fun double(key: String): Double? = (props[key] as? JsonPrimitive)?.doubleOrNull

    fun bool(key: String): Boolean? = (props[key] as? JsonPrimitive)?.booleanOrNull

    fun long(key: String): Long? = (props[key] as? JsonPrimitive)?.longOrNull

    /// Returns a copy of this tree with the node whose id is `targetId`
    /// replaced by `replacement`, or `null` when the id isn't in this branch.
    /// Only the spine down to the target is copied; every untouched sibling
    /// subtree is shared, so recomposition stays proportional to the change.
    fun replacingSubtree(targetId: String, replacement: ViewNode): ViewNode? {
        if (id == targetId) return replacement
        // ids are identity paths: a descendant's id extends this node's id
        if (!targetId.startsWith("$id/")) return null
        for ((index, child) in children.withIndex()) {
            val patched = child.replacingSubtree(targetId, replacement) ?: continue
            return copy(children = children.toMutableList().also { it[index] = patched })
        }
        return null
    }
}

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
