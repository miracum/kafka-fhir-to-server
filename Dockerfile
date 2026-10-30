FROM docker.io/library/gradle:9.6.1-jdk25@sha256:e8aeffb8197b17151ce24607811f60e91125f0018f7a3b08dc504ba9168a9c4f AS build
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

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:fc6f4aec634ee433ff16a5d18814181e7928f4be9f434b44b48231e2eb7becee
WORKDIR /opt/kafka-fhir-to-server
COPY --from=build /home/gradle/project/layers/dependencies/ ./
COPY --from=build /home/gradle/project/layers/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/layers/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/layers/application/ ./

USER 65532:65532
EXPOSE 8080/tcp
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
