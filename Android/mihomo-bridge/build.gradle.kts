import org.gradle.api.tasks.Exec

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val mihomoSource = rootProject.file("Core/mihomo-android/core/src/foss/golang/clash")
val bridgeSource = rootProject.file("Core/mihomo-bridge/native")
val configuredNdkVersion = "28.0.13004108"
val ndkRoot = rootProject.file(".build/android-sdk/ndk/$configuredNdkVersion")
val hostTag = "windows-x86_64"
val abiDefinitions = mapOf(
    "arm64-v8a" to "aarch64-linux-android21-clang.cmd",
    "armeabi-v7a" to "armv7a-linux-androideabi21-clang.cmd",
    "x86" to "i686-linux-android21-clang.cmd",
    "x86_64" to "x86_64-linux-android21-clang.cmd",
)

android {
    namespace = "io.nekohasekai.sfa.mihomo"
    compileSdk = 37
    ndkVersion = configuredNdkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // The app still ships an Android 5 flavor. The native listener itself
        // is built against API 21, so the bridge must not raise that flavor's
        // install floor.
        minSdk = 21
        externalNativeBuild {
            cmake {
                arguments += "-DGO_OUTPUT_DIR=${layout.buildDirectory.get().asFile.absolutePath}/mihomo"
                arguments += "-DBRIDGE_SOURCE_DIR=${bridgeSource.absolutePath}"
            }
        }
    }

    sourceSets {
        getByName("main") {
            // CMake links this library but AGP does not infer that an imported
            // shared object must also be packaged in the consuming APK.
            jniLibs.srcDir(layout.buildDirectory.dir("mihomo").get().asFile)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

val buildMihomoLibraries = tasks.register("buildMihomoLibraries") {
    group = "build"
    description = "Builds the pinned Mihomo core as Android static libraries."
}

abiDefinitions.forEach { (abi, compiler) ->
    val taskName = "buildMihomo" + abi.replace("-", "").replaceFirstChar { it.uppercase() }
    val output = layout.buildDirectory.file("mihomo/$abi/libclash.so")
    val task = tasks.register<Exec>(taskName) {
        group = "build"
        inputs.dir(bridgeSource)
        inputs.dir(mihomoSource)
        outputs.file(output)
        workingDir = bridgeSource
        environment("CGO_ENABLED", "1")
        environment("GOOS", "android")
        environment("GOARCH", when (abi) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "arm"
            "x86" -> "386"
            "x86_64" -> "amd64"
            else -> error("Unsupported ABI: $abi")
        })
        environment("CC", ndkRoot.resolve("toolchains/llvm/prebuilt/$hostTag/bin/$compiler").absolutePath)
        environment("CGO_CFLAGS", "--target=${when (abi) {
            "arm64-v8a" -> "aarch64-linux-android21"
            "armeabi-v7a" -> "armv7a-linux-androideabi21"
            "x86" -> "i686-linux-android21"
            "x86_64" -> "x86_64-linux-android21"
            else -> error("Unsupported ABI: $abi")
        }}")
        commandLine(
            "go",
            "build",
            "-trimpath",
            "-tags",
            "foss,with_gvisor,cmfa",
            "-buildmode=c-shared",
            "-o",
            output.get().asFile.absolutePath,
            ".",
        )
    }
    buildMihomoLibraries.configure { dependsOn(task) }
}

tasks.matching { it.name.startsWith("pre") && it.name.endsWith("Build") }.configureEach {
    dependsOn(buildMihomoLibraries)
}
