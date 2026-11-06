pluginManagement {
    repositories {
        google { content { includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // OndolSaem client SDK (see docs/development.md)
        maven("https://jitpack.io") { content { includeGroup("com.github.sonanjip") } }
    }
}
rootProject.name = "Jogakbo"
include(":app", ":sdk")

// Develop against a sibling checkout: `ondolsaem.dir=../ondolsaem` in local.properties builds the SDK from source
// instead of the JitPack artifact (com.github.sonanjip:ondolsaem:<tag>).
val local = java.util.Properties().apply { file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
local.getProperty("ondolsaem.dir")?.let { dir ->
    includeBuild(dir) { dependencySubstitution { substitute(module("com.github.sonanjip:ondolsaem")).using(project(":sdk")) } }
}
