# Build stage.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# The wrapper and the POM are copied first so the dependency download layer is cached
# and only re-runs when the POM actually changes. Copying the source first would
# invalidate it on every edit and re-download the world each build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# Spring Boot layered jars split dependencies from application code, so a code-only
# change re-pushes a few hundred kilobytes instead of the whole fat jar. --launcher
# explodes the archive so the loader classes sit on the filesystem, which starts
# marginally faster than unpacking a fat jar on every boot.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination extracted

# Runtime stage: JRE only, no compiler, no build tooling.
FROM eclipse-temurin:25-jre

WORKDIR /app

# curl is needed by the container healthcheck and is not in the JRE image. Without it
# the healthcheck fails with "curl: not found" on every probe, so the container is
# reported unhealthy forever while serving traffic perfectly well — and an orchestrator
# using that signal would never route to it.
#
# Installed in the runtime stage rather than assumed: the alternative is a healthcheck
# that cannot actually check anything.
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Runs as an unprivileged user. The base image does not provide one, so it is created
# here rather than defaulting to root.
RUN groupadd --system --gid 1001 spring \
    && useradd --system --uid 1001 --gid spring spring

COPY --from=build --chown=spring:spring /src/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /src/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring:spring /src/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring:spring /src/extracted/application/ ./

USER spring:spring

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the container's memory limit is not known
# at build time, and a hardcoded heap either wastes the limit or gets OOM-killed by it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseZGC"

# The exploded layout is launched through JarLauncher rather than `java -jar`: there is
# no jar left to point at once the layers are extracted.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
