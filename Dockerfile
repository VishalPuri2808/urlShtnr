# ── Stage 1: build fat jar ────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Cache the dependency layer separately from source so rebuilds are fast
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: minimal runtime image ───────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# finalName in pom.xml guarantees a stable, version-free jar name
COPY --from=build /app/target/url-shortener.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
