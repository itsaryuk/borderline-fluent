pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "borderline-fluent"
include(":app")
include(":packs:lang_ar", ":packs:lang_de", ":packs:lang_en", ":packs:lang_es", ":packs:lang_fr", ":packs:lang_it", ":packs:lang_ja", ":packs:lang_pl", ":packs:lang_pt", ":packs:lang_ru", ":packs:lang_tr", ":packs:lang_uk", ":packs:lang_zh", ":packs:core_whisper")
