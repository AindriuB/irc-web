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

# The database, the profiles and the encryption key live here. Created before
# dropping privileges so the app can write to it whether or not a volume is
# mounted over the top.
RUN mkdir -p /data && chown app:app /data
VOLUME ["/data"]
ENV IRC_WEB_DATA_DIR=/data

USER app
EXPOSE 8081

# wget is in busybox, so this costs nothing extra. /health rather than / because
# it proves the configuration loaded rather than merely that Tomcat is
# answering, and rather than /api/servers because that needs credentials a
# probe has no business holding.
HEALTHCHECK --interval=15s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8081/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
