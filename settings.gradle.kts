pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Overtake"
include(":overtake")
// The heavy map/routing/search library (osmdroid + maplibre + okhttp). Kept a SEPARATE sibling of
// :overtake so the reader module stays permission-light; consumers pull only what they need.
include(":overtake-maps")
// Vendored BRouter offline routing engine (MIT, pure Java). See brouter/LICENSE + README.
include(":brouter")
