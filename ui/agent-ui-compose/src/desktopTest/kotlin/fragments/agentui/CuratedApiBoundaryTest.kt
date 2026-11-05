package fragments.agentui

import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue

class CuratedApiBoundaryTest {
    @Test
    fun `kit public signatures do not expose source family models or palettes`() {
        val codeSource = Path.of(ConversationRuntime::class.java.protectionDomain.codeSource.location.toURI())
        val packagePath = "fragments/agentui/"
        val names = if (Files.isDirectory(codeSource)) {
            val folder = codeSource.resolve(packagePath)
            Files.walk(folder).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                    .map { folder.relativize(it).toString().replace('\\', '/').removeSuffix(".class").replace('/', '.') }
                    .map { "fragments.agentui.$it" }
                    .toList()
            }
        } else {
            JarFile(codeSource.toFile()).use { jar ->
                jar.entries().asSequence().filter { it.name.startsWith(packagePath) && it.name.endsWith(".class") }
                    .map { it.name.removeSuffix(".class").replace('/', '.') }.toList()
            }
        }
        assertTrue(names.isNotEmpty(), "No curated API classes found in $codeSource")
        val leaked = mutableListOf<String>()
        names.forEach { name ->
            val cls = Class.forName(name, false, ConversationRuntime::class.java.classLoader)
            if (!Modifier.isPublic(cls.modifiers)) return@forEach
            cls.declaredConstructors.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.forEach { constructor ->
                constructor.genericParameterTypes.forEach { type ->
                    if (type.typeName.contains("fragments.components.") || type.typeName.contains("fragments.chrome."))
                        leaked += "$name constructor: ${type.typeName}"
                }
            }
            cls.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.forEach { method ->
                (method.genericParameterTypes.toList() + method.genericReturnType).forEach { type ->
                    if (type.typeName.contains("fragments.components.") || type.typeName.contains("fragments.chrome."))
                        leaked += "$name.${method.name}: ${type.typeName}"
                }
            }
            cls.declaredFields.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }.forEach { field ->
                if (field.genericType.typeName.contains("fragments.components.") || field.genericType.typeName.contains("fragments.chrome."))
                    leaked += "$name.${field.name}: ${field.genericType.typeName}"
            }
        }
        assertTrue(leaked.isEmpty(), "Source-family types leaked through the curated API:\n${leaked.joinToString("\n")}")
    }
}
