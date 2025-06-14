FROM docker.io/library/gradle:8.14.2-jdk21@sha256:482cfc026939e7e7bf70d12640f8e10b3527268d4167e9e3a908c56c2987dc39 AS build
WORKDIR /home/gradle/project

COPY --chown=gradle:gradle . .

RUN --mount=type=cache,target=/home/gradle/.gradle/caches <<EOF
set -e
gradle clean build --info
gradle jacocoTestReport
java -Djarmode=layertools -jar build/libs/*.jar extract
EOF

FROM scratch AS test
WORKDIR /test
COPY --from=build /home/gradle/project/build/reports/ .
ENTRYPOINT [ "true" ]

FROM gcr.io/distroless/java21-debian12:nonroot@sha256:074fdde87f7fc85040d161270f5530109e173e997024fc1e58170eed51b90101
WORKDIR /opt/kafka-fhir-to-server
COPY --from=build /home/gradle/project/dependencies/ ./
COPY --from=build /home/gradle/project/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/application/ ./

USER 65532:65532
EXPOSE 8080/tcp
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
