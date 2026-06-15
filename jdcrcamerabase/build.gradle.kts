plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.jdcr.jdcrcamerabase"
    compileSdk = 33

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    val kotVersion = "1.6.0"
    api("androidx.core:core-ktx:$kotVersion")
    // 2. 引入协程 (建议使用 1.6.4)
    val coroutinesVersion = "1.6.4"
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    val cameraxVersion = "1.2.3"
    // 1. 核心实现（必须，CameraX 的底层是基于 Camera2 的）
    api("androidx.camera:camera-camera2:$cameraxVersion")
    // 2. 生命周期绑定（必须，用于将相机生命周期与 Activity/Fragment 绑定）
    api("androidx.camera:camera-lifecycle:$cameraxVersion")
    // 3. 视图组件（包含 PreviewView 和 VideoCapture 的基础支持）
    api("androidx.camera:camera-view:$cameraxVersion")
    // 4. 注意：不需要声明 camera-core，因为它会被上述库自动引入
    // 5. 注意：不需要声明 camera-analysis，功能已集成在 core 中

    //扩展库,如果之后想用厂商提供的 HDR、夜景、美颜等功能
//    implementation("androidx.camera:camera-extensions:$camerax_version")

    api("com.github.ljwx.jdcrlog:jdcrlog-android:1.3.5-SNAPSHOT")

}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"]) //release debug
                // JitPack 会自动填充 groupId 和 version，
                // 但为了本地测试，你可以保留这些：
                groupId = "com.github.jdcr"
                artifactId = "camerabase"
                version = "1.0.0-SNAPSHOT"
            }
        }
    }
}