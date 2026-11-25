plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.chaoseye.recognitionkit"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig { minSdk = 26; ndk { abiFilters += "arm64-v8a" }; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/songrec/jniLibs"))
}
val crate = layout.projectDirectory.dir("src/main/native/songrecfp")
val target = layout.buildDirectory.dir("cargo")
val output = layout.buildDirectory.dir("generated/songrec/jniLibs")
val ndk = androidComponents.sdkComponents.ndkDirectory
val buildSongRec = tasks.register("buildSongRec") {
    inputs.dir(crate)
    inputs.property("ndkVersion", "28.2.13676358")
    outputs.dir(output)
    doLast {
        val host = System.getProperty("os.name").lowercase()
        val tag = when { host.contains("win") -> "windows-x86_64"; host.contains("mac") -> "darwin-x86_64"; else -> "linux-x86_64" }
        val bin = ndk.get().asFile.resolve("toolchains/llvm/prebuilt/$tag/bin")
        val linker = bin.resolve("aarch64-linux-android26-clang" + if(host.contains("win")) ".cmd" else "")
        check(linker.isFile) { "Android NDK ARM64 linker missing: $linker" }
        project.exec {
            workingDir(crate.asFile)
            environment("CARGO_TARGET_DIR", target.get().asFile.absolutePath)
            environment("CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER", linker.absolutePath)
            environment("RUSTFLAGS", "-C link-arg=-Wl,-z,max-page-size=16384")
            commandLine("cargo","build","--locked","--release","--target","aarch64-linux-android")
        }
        project.copy {
            from(target.get().file("aarch64-linux-android/release/libsongrecfp.so"))
            into(output.get().dir("arm64-v8a"))
        }
        check(output.get().file("arm64-v8a/libsongrecfp.so").asFile.isFile)
    }
}
tasks.named("preBuild").configure { dependsOn(buildSongRec) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
