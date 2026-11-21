// 设了 MAVEN_MIRROR=aliyun（Flow 构建机在国内）时先走阿里云镜像；pluginManagement 必须是第一个语句
pluginManagement {
    repositories {
        if (System.getenv("MAVEN_MIRROR") == "aliyun") {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("MAVEN_MIRROR") == "aliyun") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "buxingtu"
include(":app")
