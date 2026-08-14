pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "EchoMindAndroid"
include(":app")
include(":feature:actions")
include(":feature:memory")
include(":feature:observation")
include(":core:security")
include(":core:model")
