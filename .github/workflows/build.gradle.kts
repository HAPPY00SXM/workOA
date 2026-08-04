plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.20" apply false
}
task<Delete>("clean") {任务<删除>("clean") {
    delete(rootProject.buildDir)    删除(rootProject.buildDir)
}
