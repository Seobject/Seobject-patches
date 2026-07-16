group = "app.morphe"

val pinPlaylistClasses = project(":extensions:music").layout.buildDirectory.dir(
    "intermediates/javac/release/compileReleaseJavaWithJavac/classes",
)
val pinPlaylistJarDirectory = layout.buildDirectory.dir("tmp/pinplaylist")
val pinPlaylistJar = pinPlaylistJarDirectory.map { it.file("pinplaylist.jar") }
val pinPlaylistDexDirectory = layout.buildDirectory.dir("tmp/pinplaylist/dex")
val pinPlaylistResources = layout.buildDirectory.dir(
    "generated/pinplaylist-extension",
)

val buildPinPlaylistJar = tasks.register<Jar>("buildPinPlaylistJar") {
    dependsOn(":extensions:music:compileReleaseJavaWithJavac")
    archiveFileName.set("pinplaylist.jar")
    destinationDirectory.set(pinPlaylistJarDirectory)
    from(pinPlaylistClasses) {
        include("app/morphe/extension/music/patches/pinplaylist924/**")
    }
}

val buildPinPlaylistExtension = tasks.register<Exec>("buildPinPlaylistExtension") {
    dependsOn(buildPinPlaylistJar)
    inputs.file(pinPlaylistJar)
    outputs.file(
        pinPlaylistResources.map { it.file("extensions/pinplaylist.mpe") },
    )

    doFirst {
        val sdk = requireNotNull(System.getenv("LOCALAPPDATA")) {
            "LOCALAPPDATA is required to locate the Android SDK"
        }
        val d8 = file("$sdk/Android/Sdk/build-tools/37.0.0/d8.bat")
        check(d8.isFile) { "D8 not found: $d8" }

        delete(pinPlaylistDexDirectory)
        pinPlaylistDexDirectory.get().asFile.mkdirs()
        commandLine(
            d8,
            "--release",
            "--min-api",
            "26",
            "--output",
            pinPlaylistDexDirectory.get().asFile,
            pinPlaylistJar.get().asFile,
        )
    }

    doLast {
        val output = pinPlaylistResources.get()
            .file("extensions/pinplaylist.mpe").asFile
        output.parentFile.mkdirs()
        pinPlaylistDexDirectory.get().file("classes.dex").asFile
            .copyTo(output, overwrite = true)
    }
}

patches {
    about {
        name = "Seobjects Random Patches (Dev)"
        description = "Random QoL Patches — dev channel for experimental YouTube Music versions"
        source = "git@github.com:Seobject/Seobject-patches.git"
        author = "Seobject"
        contact = "na"
        website = "na"
        license = "GPLv3"
    }
}

dependencies {
    // Used by JsonGenerator.
    implementation(libs.gson)

    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    implementation(libs.morphe.patches.library)

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(files("C:/Users/Evan/AppData/Local/Temp/opencode/morphe-core-1.34.0.mpp"))

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

tasks {
    processResources {
        dependsOn(buildPinPlaylistExtension)
    }

    register<JavaExec>("runLocalApkPatchTest") {
        dependsOn(jar)
        classpath =
            files("C:/Users/Evan/AppData/Local/Temp/opencode/morphe-core-1.34.0.mpp") +
                files(layout.buildDirectory.file("libs/patches-${project.version}.mpp")) +
                (sourceSets["test"].runtimeClasspath - sourceSets["main"].output)
        mainClass.set("LocalApkPatchTestKt")
        doFirst {
            systemProperty(
                "localTestApk",
                providers.gradleProperty("localTestApk").get(),
            )
            systemProperty(
                "localTestOutput",
                providers.gradleProperty("localTestOutput").get(),
            )
            systemProperty(
                "localTestVersion",
                providers.gradleProperty("localTestVersion")
                    .getOrElse("9.24.51"),
            )
        }
    }

    register<JavaExec>("checkStringResources") {
        description = "Checks resource strings for invalid formatting"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.patches.util.resource.CheckStringResourcesKt")
    }

    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
    }
    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}

kotlin {
    sourceSets.named("main") {
        kotlin.setSrcDirs(listOf("src/main/kotlin"))
        resources.srcDir(pinPlaylistResources)
        kotlin.include(
            "app/morphe/patches/music/layout/pinplaylist/**",
            "app/seobject/patches/music/Compatibility.kt",
            "app/seobject/patches/music/settings/**",
        )
    }

    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters")
    }
}

tasks.named("sourcesJar") {
    dependsOn("buildPinPlaylistExtension")
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests.set(false)
}
