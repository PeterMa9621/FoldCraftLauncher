import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
}

android {
    namespace = "com.tungsten.fcl"
    compileSdk = libs.versions.compileSdk.get().toInt()

    var localProperty: Properties? = null
    if (file("${rootDir}/local.properties").exists()) {
        localProperty = Properties()
        file("${rootDir}/local.properties").inputStream().use { localProperty.load(it) }
    }
    val pwd = System.getenv("FCL_KEYSTORE_PASSWORD") ?: localProperty?.getProperty("pwd")
    val curseApiKey = System.getenv("CURSE_API_KEY") ?: localProperty?.getProperty("curse.api.key")
    val oauthApiKey = System.getenv("OAUTH_API_KEY") ?: localProperty?.getProperty("oauth.api.key")
    if (localProperty != null && localProperty.getProperty("arch", "all") == "arm64")
        System.setProperty("arch", "arm64")

    signingConfigs {
        create("FCLKey") {
            storeFile = file("../key-store.jks")
            storePassword = pwd
            keyAlias = "FCL-Key"
            keyPassword = pwd
        }
        create("FCLDebugKey") {
            storeFile = file("../debug-key.jks")
            storePassword = "FCL-Debug"
            keyAlias = "FCL-Debug"
            keyPassword = "FCL-Debug"
        }
    }

    // 正式服 / 测试服的整合包源，由 buildType 覆盖（见下面的 testserver）
    val modpackRootProd = "https://plan-x-modpack-1301840151.cos.ap-shanghai.myqcloud.com/"
    val modpackRootTest = "https://plan-x-modpack-test-1301840151.cos.ap-shanghai.myqcloud.com/"

    defaultConfig {
        // 独立包名：与官方 FoldCraftLauncher (com.tungsten.fcl) 区分开，
        // 否则装过官方版的手机因签名不同会直接 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
        // 改这里要同步改 FCLLibrary 的 file_browser_provider 字符串（FileProvider authority）。
        applicationId = "com.ppstudio.planx"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1324
        versionName = "1.3.2.4"

        buildConfigField("String", "MODPACK_ROOT", "\"$modpackRootProd\"")
        buildConfigField("boolean", "TEST_MODE", "false")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("FCLKey")
        }
        // debug 与 fortest 是同一个 applicationId（都没有 applicationIdSuffix），
        // 所以必须用同一把签名钥匙，否则两者互相覆盖安装会被系统按「签名不同」拒掉。
        // 用仓库里的 debug-key.jks 而不是 AGP 自动生成的 ~/.android/debug.keystore：
        // 后者每台机器一份，换台电脑构建出来的包又装不上去。
        getByName("debug") {
            signingConfig = signingConfigs.getByName("FCLDebugKey")
        }
        create("fordebug") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("FCLDebugKey")
        }
        // 测试服启动器：整合包源指向测试桶，并给游戏 JVM 传 -DtestMode=true。
        // 名字不能以 "test" 开头（AGP 保留），所以沿用 fordebug 的命名习惯叫 fortest。
        // 不加 applicationIdSuffix —— 游戏目录 FCLPath.SHARED_COMMON_DIR 是固定的
        // /sdcard/PX/.minecraft，两个包共存也是同一份游戏文件，反而会互相覆盖。
        create("fortest") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("FCLDebugKey")
            matchingFallbacks += listOf("debug")

            buildConfigField("String", "MODPACK_ROOT", "\"$modpackRootTest\"")
            buildConfigField("boolean", "TEST_MODE", "true")
        }
        configureEach {
            resValue("string", "app_version", defaultConfig.versionName.toString())
            resValue("string", "curse_api_key", curseApiKey.toString())
            resValue("string", "oauth_api_key", oauthApiKey.toString())
        }
    }



    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf("**/libbytehook.so")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    splits {
        val arch = System.getProperty("arch", "all")
        if (arch != "all") {
            abi {
                isEnable = true
                reset()
                when (arch) {
                    "arm" -> include("armeabi-v7a")
                    "arm64" -> include("arm64-v8a")
                    "x86" -> include("x86")
                    "x86_64" -> include("x86_64")
                }
            }
        }
    }
}

/**
 * 按 -Darch 过滤 JRE 压缩包，生成当前架构需要的资产目录。
 * 非 all 构建只保留 version、universal 和 bin-<arch>.tar.xz（运行时两者都需要，见 RuntimeUtils#installJava）。
 */
abstract class FilterJreAssets : Sync() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val filterJreAssets = tasks.register<FilterJreAssets>("filterJreAssets") {
    val arch = System.getProperty("arch", "all")
    // Copy/Sync 的 up-to-date 检查不包含 copy spec 的过滤规则，必须显式声明 arch 输入，
    // 否则切换架构时任务不会重跑，产物会残留上一架构的 JRE 包
    inputs.property("arch", arch)
    from(layout.projectDirectory.dir("src/main/jreAssets"))
    into(outputDir)
    if (arch != "all") {
        exclude { it.name.startsWith("bin-") && it.name != "bin-$arch.tar.xz" }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            if (output is com.android.build.api.variant.impl.VariantOutputImpl) {
                (output.getFilter(ABI)?.identifier ?: "all").let { abi ->
                    output.outputFileName =
                        "PX-${variant.buildType}-${project.android.defaultConfig.versionName}-${abi}.apk"
                }
            }
        }

        // JRE 资产不放在 src/main/assets 里（AGP 的 mergeAssets 不应用 source set 的 exclude 过滤），
        // 而是按架构注册为生成源；arch 变化会让 Sync 任务重新执行，mergeAssets 随之重跑，避免产物残留旧架构文件。
        if (System.getProperty("arch", "all") != "all") {
            variant.sources.assets?.addGeneratedSourceDirectory(filterJreAssets) { it.outputDir }
        } else {
            variant.sources.assets?.addStaticSourceDirectory("src/main/jreAssets")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
    implementation(project(":FCLCore"))
    implementation(project(":FCLLibrary"))
    implementation(project(":FCLauncher"))
    implementation(project(":Terracotta"))
    implementation(libs.taptargetview)
    implementation(libs.nanohttpd)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.opennbt)
    implementation(libs.gson)
    implementation(libs.appcompat)
    implementation(libs.core.splashscreen)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.glide)
    implementation(libs.touchcontroller)
    implementation(libs.palette.ktx)
    implementation(libs.gamepad.remapper)
    implementation(libs.segmented.button)
    implementation(libs.datastore)
    implementation(libs.kotlinx.serialization.json)
}

tasks.register("updateMap") {
    doLast {
        val list = mutableListOf<String>()
        val mapFile = file("${rootDir}/version_map.json")
        mapFile.forEachLine {
            list.add(
                when {
                    it.contains("versionCode") -> it.replace(
                        Regex("[0-9]+"),
                        android.defaultConfig.versionCode.toString()
                    )

                    it.contains("versionName") -> it.replace(
                        Regex("\\d+(\\.\\d+)+"),
                        android.defaultConfig.versionName.toString()
                    )

                    it.contains("date") -> it.replace(
                        Regex("\\d{4}\\.\\d{2}\\.\\d{2}"),
                        SimpleDateFormat("yyyy.MM.dd").format(Date())
                    )

                    it.contains("url") -> it.replace(
                        Regex("\\d+(\\.\\d+)+"),
                        android.defaultConfig.versionName.toString()
                    )

                    else -> it
                }
            )
        }
        mapFile.writeText(list.joinToString("\n"), Charsets.UTF_8)
    }
}