import java.util.Properties

plugins { java }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
sourceSets.main { java.srcDir(rootProject.file("shared")) }

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val sdkRoot = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(provider { localProperties.getProperty("sdk.dir") ?: error("Set ANDROID_HOME or sdk.dir in local.properties") })
val androidJar = provider { file("${sdkRoot.get()}/platforms/android-36/android.jar") }
dependencies { compileOnly(files(androidJar)) }

val dexDir = layout.buildDirectory.dir("dex")
val dexHelper by tasks.registering(Exec::class) {
    dependsOn(tasks.jar)
    inputs.file(tasks.jar.flatMap { it.archiveFile })
    outputs.dir(dexDir)
    doFirst {
        dexDir.get().asFile.mkdirs()
        val windows = System.getProperty("os.name").startsWith("Windows")
        val d8 = file("${sdkRoot.get()}/build-tools/36.0.0/" + if (windows) "d8.bat" else "d8")
        val command = mutableListOf<String>()
        if (windows) command.addAll(listOf("cmd", "/c"))
        command.addAll(listOf(d8.absolutePath, "--lib", androidJar.get().absolutePath,
            "--min-api", "36", "--output", dexDir.get().asFile.absolutePath,
            tasks.jar.get().archiveFile.get().asFile.absolutePath))
        commandLine(command)
    }
}
val helperJar by tasks.registering(Zip::class) {
    dependsOn(dexHelper)
    archiveFileName.set("helper.jar")
    destinationDirectory.set(layout.buildDirectory.dir("outputs"))
    from(dexDir) { include("classes*.dex") }
}
tasks.assemble { dependsOn(helperJar) }
