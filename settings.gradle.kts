pluginManagement {
    repositories {
        google()
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

rootProject.name = "neopen-companion"
// App unificada: :neopen (completa, con modo "solo puente" integrado). :companion
// quedó retirado del build tras fusionarse en :neopen.
// :live = app slim aparte (boli + teclado de escritura en vivo, con ML Kit).
include(":core", ":neopen", ":live")
