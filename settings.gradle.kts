rootProject.name = "despacho-logistico"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include("bff", "gateway", "inventario", "orquestador", "tracking", "transportista")
