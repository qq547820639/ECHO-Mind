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
include(":feature:presence")
include(":feature:intelligence")
include(":feature:journey")
include(":feature:qa")
include(":feature:wearable")
include(":core:security")
include(":core:model")
include(":core:ports")
