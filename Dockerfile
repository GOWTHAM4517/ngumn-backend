# Builds and runs the NGUMN backend as a single container, for hosts
# (Render, Railway, Fly.io, a VPS, ...) that deploy from a Dockerfile
# rather than running Maven themselves.
#
# ---- Build stage ----------------------------------------------------
# Uses the official Maven image (not the project's mvnw wrapper) so the
# build never depends on downloading the wrapper's own jar over the
# network inside the build environment.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Copy just the POM first so dependency downloads are cached in their
# own Docker layer and are skipped on later builds that only change
# source code.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- Run stage --------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Tuned for a small (~512 MB) free-tier instance so the JVM never tries
# to claim more heap than the container actually has and gets killed for
# it. On a host with more memory, override JAVA_OPTS in its dashboard,
# e.g. "-Xmx1g -Xms256m".
ENV JAVA_OPTS="-Xmx384m -Xms128m"

# The platform tells the app which port to listen on via $PORT
# (application.properties: server.port=${PORT:${SERVER_PORT:8080}}); 8080
# is just the documented/local-dev default.
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
