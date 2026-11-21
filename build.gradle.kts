plugins {
    alias(libs.plugins.spring.boot) apply false
}

subprojects {
    group = "cl.edu.integracion"
    version = "0.0.1-SNAPSHOT"

    plugins.withType<JavaPlugin> {
        tasks.withType<JavaCompile>().configureEach {
            options.release.set(21)
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
        dependencies {
            add("implementation", platform(libs.spring.boot.dependencies))
        }
    }

    plugins.withId("org.springframework.boot") {
        // Evita el jar "-plain": build/libs contiene un único jar ejecutable
        tasks.named<Jar>("jar") {
            enabled = false
        }
    }
}
