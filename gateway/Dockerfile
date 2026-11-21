# syntax=docker/dockerfile:1
FROM gradle:8.14.3-jdk21 AS build
USER root
ENV GRADLE_USER_HOME=/gradle-cache
WORKDIR /workspace
# Estructura mínima del build multi-proyecto; solo se compila el módulo gateway
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle/libs.versions.toml gradle/
RUN mkdir bff gateway inventario orquestador tracking transportista
COPY gateway/build.gradle.kts gateway/
COPY gateway/src gateway/src
# Caché de dependencias y de compilación de Gradle entre builds
RUN --mount=type=cache,target=/gradle-cache \
    gradle :gateway:bootJar --no-daemon --console=plain

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/gateway/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
