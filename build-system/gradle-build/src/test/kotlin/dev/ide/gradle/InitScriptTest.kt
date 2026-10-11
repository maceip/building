package dev.ide.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Timeout(value = 4, unit = TimeUnit.MINUTES)
class InitScriptTest {

    @Test
    fun fourAbiFiltersCollapseToArm64AndJavaExecUsesJavaHome() {
        val root = Files.createTempDirectory("abi-fixture")
        val report = root.resolve("abi-report.txt")
        val javaHome = System.getProperty("java.home")
        root.resolve("settings.gradle").writeText("rootProject.name = 'abi'\n")
        root.resolve("build.gradle").writeText(abiBuild())
        val result = runner(root, report, "dump").build()
        val output = result.output
        assertTrue(output.contains("NDK [arm64-v8a]"), output)
        assertTrue(output.contains("CMAKE [arm64-v8a]"), output)
        assertTrue(output.contains("NDKBUILD [arm64-v8a]"), output)
        assertTrue(output.contains("SPLIT [arm64-v8a]"), output)
        assertTrue(output.contains("UNIVERSAL false"), output)
        assertTrue(output.contains("JAVA $javaHome/bin/java"), output)
        val removed = report.readText().trim().lines().filter { it.isNotBlank() }
        assertEquals(listOf("armeabi-v7a", "x86", "x86_64"), removed)
    }

    @Test
    fun anExeClassifierResolvesTheJarAndAJarlessModuleFailsByName() {
        val root = Files.createTempDirectory("classifier-fixture")
        val repo = root.resolve("repo")
        publish(repo, "com.example", "native-tool", "1.0", jar = "jar-bytes".toByteArray(), exe = "elf".toByteArray())
        publish(repo, "com.example", "only-exe", "1.0", jar = null, exe = "elf".toByteArray())
        root.resolve("settings.gradle").writeText("rootProject.name = 'classifiers'\n")
        root.resolve("build.gradle").writeText(classifierBuild(repo))
        val report = root.resolve("unused-abi.txt")
        val jar = runner(root, report, "resolveJar").build()
        assertTrue(jar.output.contains("RESOLVED=native-tool-1.0.jar"), jar.output)
        val failed = runner(root, report, "resolveExe").buildAndFail()
        assertTrue(failed.output.contains("only-exe"), failed.output)
    }

    private fun runner(root: java.nio.file.Path, report: java.nio.file.Path, task: String): GradleRunner {
        val init = System.getProperty("dev.ide.init.script")
            ?: error("dev.ide.init.script is not set")
        val env = HashMap<String, String>()
        System.getenv().forEach { (key, value) -> env[key] = value }
        env["JAVA_HOME"] = System.getProperty("java.home")
        env["DEV_IDE_ABI_REPORT"] = report.toAbsolutePath().toString()
        return GradleRunner.create()
            .withProjectDir(root.toFile())
            .withArguments("--offline", "--no-daemon", "--init-script", init, task)
            .withEnvironment(env)
    }

    private fun publish(repo: java.nio.file.Path, group: String, name: String, version: String, jar: ByteArray?, exe: ByteArray?) {
        val dir = repo.resolve(group.replace('.', '/')).resolve(name).resolve(version)
        Files.createDirectories(dir)
        dir.resolve("$name-$version.pom").writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$name</artifactId>
              <version>$version</version>
            </project>
            """.trimIndent() + "\n",
        )
        if (jar != null) Files.write(dir.resolve("$name-$version.jar"), jar)
        if (exe != null) Files.write(dir.resolve("$name-$version-exe.exe"), exe)
    }

    private fun abiBuild(): String = """
        class NdkBlock {
            def abiFilters = ['armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'] as LinkedHashSet
        }
        class Enb {
            def cmake = new NdkBlock()
            def ndkBuild = new NdkBlock()
        }
        class DefaultConfig {
            def ndk = new NdkBlock()
            def externalNativeBuild = new Enb()
        }
        class AbiSplit {
            def include = ['armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'] as LinkedHashSet
            boolean universalApk = true
            def getInclude() { return include }
            def reset() { include.clear() }
            def include(String name) { include.add(name) }
        }
        class Splits {
            def abi = new AbiSplit()
        }
        class AndroidStub {
            def defaultConfig = new DefaultConfig()
            def splits = new Splits()
        }
        extensions.add('android', new AndroidStub())
        tasks.register('tool', JavaExec) {
            classpath = files()
            mainClass = 'dev.example.Tool'
        }
        tasks.register('dump') {
            doLast {
                def android = extensions.getByName('android')
                println 'NDK ' + android.defaultConfig.ndk.abiFilters
                println 'CMAKE ' + android.defaultConfig.externalNativeBuild.cmake.abiFilters
                println 'NDKBUILD ' + android.defaultConfig.externalNativeBuild.ndkBuild.abiFilters
                println 'SPLIT ' + android.splits.abi.include
                println 'UNIVERSAL ' + android.splits.abi.universalApk
                println 'JAVA ' + tasks.getByName('tool').executable
            }
        }
    """.trimIndent()

    private fun classifierBuild(repo: java.nio.file.Path): String {
        val path = repo.toAbsolutePath().toString().replace("\\", "/")
        return """
            repositories {
                maven { url = uri('$path') }
            }
            configurations { jarTool; exeOnly }
            dependencies {
                jarTool('com.example:native-tool:1.0') {
                    artifact {
                        name = 'native-tool'
                        type = 'exe'
                        extension = 'exe'
                        classifier = 'exe'
                    }
                }
                exeOnly('com.example:only-exe:1.0') {
                    artifact {
                        name = 'only-exe'
                        type = 'exe'
                        extension = 'exe'
                        classifier = 'exe'
                    }
                }
            }
            tasks.register('resolveJar') {
                doLast {
                    println 'RESOLVED=' + configurations.jarTool.files.collect { it.name }.join(',')
                }
            }
            tasks.register('resolveExe') {
                doLast {
                    configurations.exeOnly.files.each { println it }
                }
            }
        """.trimIndent()
    }
}
