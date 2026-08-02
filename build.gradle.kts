import java.io.ByteArrayOutputStream
import java.security.MessageDigest

plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}

tasks.register("checkApiContract") {
    group = "verification"
    description = "Valida Retrofit contra el snapshot OpenAPI canónico del servidor."

    val apiFile = file("core/src/main/java/net/micapum/neopen/core/data/Api.kt")
    val specFile = file("docs/openapi.yaml")
    val manifestFile = file("docs/openapi.snapshot.json")
    val appBuildFile = file("neopen/build.gradle.kts")
    inputs.files(apiFile, specFile, manifestFile, appBuildFile)

    doLast {
        val retrofitOperations = Regex("""@(GET|POST|PUT|PATCH|DELETE)\(\"([^\"]+)\"\)""")
            .findAll(apiFile.readText())
            .map { match ->
                val path = "/" + match.groupValues[2].trimStart('/')
                "${match.groupValues[1]} $path"
            }
            .toSet()

        val openApiOperations = mutableSetOf<String>()
        var currentPath: String? = null
        val pathPattern = Regex("""^  (/[^:]+):\s*$""")
        val methodPattern = Regex("""^    (get|post|put|patch|delete):\s*$""")
        specFile.forEachLine { line ->
            pathPattern.matchEntire(line)?.let { currentPath = it.groupValues[1] }
            methodPattern.matchEntire(line)?.let { match ->
                currentPath?.let { path -> openApiOperations += "${match.groupValues[1].uppercase()} $path" }
            }
        }
        val missing = retrofitOperations - openApiOperations
        check(missing.isEmpty()) {
            "Operaciones Retrofit ausentes del OpenAPI:\n${missing.sorted().joinToString("\n")}"
        }

        val expectedSha = manifestFile.readLines()
            .first { it.contains("\"sha256\"") }
            .substringAfter(':')
            .trim()
            .trim(',', '"')
        val actualSha = MessageDigest.getInstance("SHA-256")
            .digest(specFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actualSha == expectedSha) {
            "El snapshot OpenAPI no coincide con su SHA-256: $actualSha != $expectedSha"
        }

        val appVersion = Regex("""versionName\s*=\s*\"([^\"]+)\"""")
            .find(appBuildFile.readText())?.groupValues?.get(1)
            ?: error("No se encontró versionName")
        val serverTag = manifestFile.readLines()
            .first { it.contains("\"serverTag\"") }
            .substringAfter(':')
            .trim()
            .trim(',', '"')
        val contractVersion = Regex("""(?m)^  version: \"([^\"]+)\"$""")
            .find(specFile.readText())?.groupValues?.get(1)
            ?: error("No se encontró info.version en OpenAPI")
        check(serverTag == "v$appVersion" && contractVersion == appVersion) {
            "Versiones desalineadas: app=$appVersion, tag=$serverTag, OpenAPI=$contractVersion"
        }

        val schemaDir = file("neopen/schemas/net.micapum.neopen.app.db.NeopenDb")
        val missingSchemas = (2..9).filterNot { schemaDir.resolve("$it.json").isFile }
        check(missingSchemas.isEmpty()) { "Faltan esquemas Room: $missingSchemas" }
        logger.lifecycle(
            "Contrato válido: ${retrofitOperations.size} operaciones Retrofit, snapshot $actualSha, Room 2..9.",
        )
    }
}

tasks.register("checkFileModes") {
    group = "verification"
    description = "Impide cambios accidentales de permisos en ficheros versionados."

    doLast {
        val output = ByteArrayOutputStream()
        exec {
            commandLine("git", "ls-files", "--stage", "-z")
            standardOutput = output
        }
        val invalid = output.toString(Charsets.UTF_8.name())
            .split('\u0000')
            .filter { it.isNotEmpty() }
            .mapNotNull { record ->
                val (metadata, path) = record.split('\t', limit = 2)
                val mode = metadata.substringBefore(' ')
                val expected = if (path == "gradlew") "100755" else "100644"
                if (mode == expected) null else "$mode $path (esperado $expected)"
            }
        check(invalid.isEmpty()) {
            "Modos Git inesperados:\n${invalid.joinToString("\n")}"
        }
        logger.lifecycle("Modos Git válidos: gradlew ejecutable; resto 100644.")
    }
}
