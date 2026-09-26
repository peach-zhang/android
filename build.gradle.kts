import java.io.File
import java.net.URI

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.openapi.generator") version "7.25.0"
}

fun download(url: String, filename: String) {
    URI(url).toURL().openConnection().let { conn ->
        File(filename).outputStream().use { out ->
            conn.inputStream.use { inp ->
                inp.copyTo(out)
            }
        }
    }
}

tasks.register("downloadSpec") {
    val gotifyVersion = "master"
    val url = "https://raw.githubusercontent.com/gotify/server/$gotifyVersion/docs/spec.json"
    val buildDir = project.layout.buildDirectory.get()
    val specLocation = buildDir.file("gotify.spec.json").asFile.absolutePath
    doFirst {
        buildDir.asFile.mkdirs()
        download(url, specLocation)
    }
}

openApiGenerate {
    generatorName.set("java")
    inputSpec.set("$projectDir/build/gotify.spec.json")
    outputDir.set("$projectDir/client")
    apiPackage.set("com.github.gotify.client.api")
    modelPackage.set("com.github.gotify.client.model")
    configOptions.set(mapOf(
        "library" to "retrofit2",
        "hideGenerationTimestamp" to "true",
        "dateLibrary" to "java8"
    ))
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
}

tasks.named("openApiGenerate").configure {
    dependsOn("downloadSpec")
}
