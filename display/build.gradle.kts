plugins { id("com.android.application") }
android {
    namespace = "dev.display"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.display"
        minSdk = 31
        targetSdk = 36
        versionCode = 7
        versionName = "0.4.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation(project(":display-api"))
    implementation(project(":display-core-api"))
    compileOnly("io.github.libxposed:api:102.0.0")
    // Service API 101 is forward-compatible with Vector's 102 and builds with SDK 36.
    implementation("io.github.libxposed:service:101.0.0")
}
