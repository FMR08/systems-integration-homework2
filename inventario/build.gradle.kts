plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
}
