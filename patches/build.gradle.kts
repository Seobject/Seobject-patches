group = "app.morphe"

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

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))
}

tasks {
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
        kotlin.include(
            "app/morphe/patches/music/layout/pinplaylist/**",
            "app/seobject/patches/music/Compatibility.kt",
        )
    }

    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters")
    }
}
