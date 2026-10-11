// build-logic —— 约定插件（convention plugins）独立构建：不进根模块图、不入 §6 的 18 模块计数。
// 版本目录必须显式引入（included build 不继承根 settings 的 versionCatalogs）。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
