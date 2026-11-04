// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ide.workbench.appengine

import java.nio.file.Path
import java.nio.file.Paths

/** Host/SDK automation entry for the same generator used by the app, never a separate fake fixture. */
object AppEnginePackagerCli {
    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size == 9) {
            "engine sourceRoot entry destination applicationId label payloadRoot runtimeClassesJar acknowledge-gpl"
        }
        val engine = when (arguments[0]) {
            "python" -> AppEngine.Python
            "node-unqualified" -> AppEngine.Node
            else -> error("Select python or explicitly node-unqualified")
        }
        require(arguments[8] == "acknowledge-gpl") { "GPL linking/distribution acknowledgement required" }
        val result = AppEnginePackager().create(AppEnginePackageRequest(
            sourceRoot = Paths.get(arguments[1]), entry = arguments[2], destination = Paths.get(arguments[3]),
            applicationId = arguments[4], label = arguments[5], engine = engine, payloadRoot = Paths.get(arguments[6]),
            runtimeClassesJar = Paths.get(arguments[7]), allowUnqualifiedNode = engine == AppEngine.Node,
            acknowledgeGplDistribution = true,
        ))
        println("GENERATED_PROJECT=${result.projectRoot}")
        println("BUNDLED_BYTES=${result.bundledBytes}")
        println("CORRESPONDING_SOURCE=${result.sourceBundle}")
        result.warnings.forEach { println("LIMITATION=$it") }
    }
}
