# The one deployable: the Spring Boot jar with the PWA inside it (D-003), on a Java 25 JRE.
#
#   docker build -t confuzion .
#
# Base images are pinned by digest. To move one, take the new digest from
# `docker buildx imagetools inspect <image>:<tag>`.

# Node is only copied into the build stage, because bootJar runs `npm ci` and `npm run build` in frontend/.
FROM node:24-trixie-slim@sha256:4d00acb4bcc42c454c4eb801526f7ad7f772ea3bd4fd168c5cc2e384abcbb9d4 AS node

FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS build
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm

WORKDIR /src/backend
# Build and lock files first, so a source change reuses the `npm ci` layer.
COPY backend/gradlew backend/settings.gradle backend/build.gradle ./
COPY backend/gradle gradle
COPY frontend/package.json frontend/package-lock.json ../frontend/
# A Windows checkout can give the wrapper script CRLF line endings, which break its shebang.
RUN sed -i 's/\r$//' gradlew
RUN --mount=type=cache,target=/root/.gradle ./gradlew frontendInstall --no-daemon --console=plain

COPY backend/config config
COPY backend/src src
COPY frontend ../frontend
RUN --mount=type=cache,target=/root/.gradle ./gradlew bootJar --no-daemon --console=plain

FROM eclipse-temurin:25-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
RUN groupadd --system --gid 10001 app \
	&& useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
# logback.xml also writes rolling files under ./logs. That is the only directory here the app can write to.
RUN install -d -o app -g app logs
COPY --from=build /src/backend/build/libs/*.jar app.jar
USER 10001:10001
EXPOSE 8080
# The image has no curl or wget, so bash makes the request itself. ECS ignores this instruction: the task definition
# declares its own health check.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
	CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /api/actuator/health HTTP/1.0\\r\\n\\r\\n' >&3 && read -r status <&3 && [[ $status == *' 200 '* ]]"]
# 75% of the task's memory for the heap (the default is 25%), leaving the rest for metaspace, threads and buffers.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
