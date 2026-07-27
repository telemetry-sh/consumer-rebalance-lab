FROM eclipse-temurin:21-jdk-alpine AS build

ARG KOTLIN_VERSION=2.4.10
ARG KOTLIN_SHA256=473dd66c7a3ef4b182065b3da670466c1bf2773a9dbb0ed8b33a39fe9d4f876d

RUN apk add --no-cache bash curl unzip
WORKDIR /src
RUN curl --fail --location --silent --show-error \
      "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip" \
      --output /tmp/kotlin.zip \
    && echo "${KOTLIN_SHA256}  /tmp/kotlin.zip" | sha256sum -c - \
    && unzip -q /tmp/kotlin.zip -d /opt/kotlin \
    && rm /tmp/kotlin.zip

COPY src ./src
RUN /opt/kotlin/kotlinc/bin/kotlinc src/App.kt src/Model.kt \
      -include-runtime -jvm-target 11 -d /consumer-rebalance-lab.jar

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -g 10001 lab \
    && adduser -D -H -u 10001 -G lab lab
WORKDIR /app
COPY --from=build --chown=10001:10001 /consumer-rebalance-lab.jar ./consumer-rebalance-lab.jar
COPY --chown=10001:10001 public ./public

ENV HOST=0.0.0.0
ENV PORT=8080
EXPOSE 8080
USER 10001:10001

HEALTHCHECK --interval=10s --timeout=2s --start-period=5s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/healthz || exit 1

ENTRYPOINT ["java", "-jar", "consumer-rebalance-lab.jar"]
