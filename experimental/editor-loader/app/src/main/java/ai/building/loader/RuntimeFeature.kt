package ai.building.loader

import android.content.Context

/** Base owns the contract, with no dependency on the optional implementation. */
interface RuntimeFeature {
    fun run(context: Context, source: String): String
}
