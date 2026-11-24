FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace

COPY gradlew build.gradle settings.gradle ./
COPY gradle ./gradle

RUN sed -i 's/\r$//' gradlew \
    && chmod +x gradlew \
    && ./gradlew --no-daemon dependencies

COPY src ./src

RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build --chown=10001:10001 /workspace/build/libs/*.jar app.jar

USER 10001:10001

EXPOSE 8080 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
