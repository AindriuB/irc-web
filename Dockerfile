# Two stages: the build tools are large and have no business in the image that
# gets deployed.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Dependencies resolve from their own layer, so editing a source file does not
# re-download the internet on every build.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Skipping tests here is deliberate: the integration tests need a running IRC
# server, and CI runs them against one before it ever builds this image. A
# docker build that quietly skips them without saying why is worse.
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime

# Not root. The app opens outbound sockets and serves HTTP; neither needs it,
# and a test harness with no authentication is the last thing to run privileged.
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app

COPY --from=build /build/target/irc-web-*.jar app.jar
COPY LICENSE NOTICE ./

USER app
EXPOSE 8081

# wget is in busybox, so this costs nothing extra. /api/servers is a better
# probe than / because it proves the configuration loaded, not merely that
# Tomcat is answering.
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8081/api/servers || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
