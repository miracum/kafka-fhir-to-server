FROM docker.io/library/gradle:9.5.1-jdk25@sha256:8de3543f1772bb66be3b275893e5977b6d8bd2b0d25551faa5846a821d1f0600 AS build
SHELL ["/bin/bash", "-eo", "pipefail", "-c"]
WORKDIR /home/gradle/project

COPY --chown=gradle:gradle . .

RUN --mount=type=cache,target=/home/gradle/.gradle/caches <<EOF
set -e
gradle clean build
gradle jacocoTestReport
PROJECT_VERSION="$(gradle --no-configuration-cache -q printVersion)"
java -Djarmode=tools -jar "build/libs/fhir-to-server-${PROJECT_VERSION}.jar" extract \
    --layers --launcher --destination ./layers
EOF

FROM scratch AS test
WORKDIR /test
COPY --from=build /home/gradle/project/build/reports/ .
ENTRYPOINT [ "true" ]

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:e1eeec12952b877762d2a0a94a216600b34056942f6d82635a1d4a5dc8ee9770
WORKDIR /opt/kafka-fhir-to-server
COPY --from=build /home/gradle/project/layers/dependencies/ ./
COPY --from=build /home/gradle/project/layers/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/layers/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/layers/application/ ./

USER 65532:65532
EXPOSE 8080/tcp
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
