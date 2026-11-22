pluginManagement { repositories { mavenCentral(); google(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { mavenCentral(); google() } }
rootProject.name = "koog-cockpit"
include(":contract", ":koog", ":cockpit-ui", ":koog-app")
