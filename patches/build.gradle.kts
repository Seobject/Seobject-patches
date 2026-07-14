group = "app.morphe"

patches {
    about {
        name = "Pin Playlists"
        description = "A standalone YouTube Music Pin playlists patch bundle"
        source = "local"
        author = "Evan"
        contact = "na"
        website = "https://morphe.software"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
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
            "app/morphe/patches/music/misc/extension/**",
            "app/morphe/patches/music/misc/gms/Constants.kt",
            "app/morphe/patches/music/misc/settings/SettingsPatch.kt",
            "app/morphe/patches/music/shared/**",
            "app/morphe/patches/all/misc/resources/AddResourcesPatch.kt",
            "app/morphe/patches/shared/GoogleApiActivityFingerprint.kt",
            "app/morphe/patches/shared/layout/branding/AddBrandLicensePatch.kt",
            "app/morphe/patches/shared/misc/initialization/**",
            "app/morphe/patches/shared/misc/settings/**",
            "app/morphe/patches/util/**",
            "app/morphe/patches/youtube/misc/settings/ModifyActivityForSettingsInjection.kt",
            "app/morphe/util/**",
        )
    }

    compilerOptions {
        freeCompilerArgs = listOf("-Xcontext-parameters")
    }
}
