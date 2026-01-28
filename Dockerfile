FROM docker.io/library/gradle:9.3.0-jdk25@sha256:2e614dffcc0266a28f70b323a3044472dfa75a7f6873d94a759a4fb785dc78da AS build
SHELL ["/bin/bash", "-eo", "pipefail", "-c"]
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

FROM gcr.io/distroless/java25-debian13:nonroot@sha256:85df69eb02f91fc64a7b5635a26e6d08323a9fa1adc35eb1c2ffba79960e8c87
WORKDIR /opt/kafka-fhir-to-server
COPY --from=build /home/gradle/project/dependencies/ ./
COPY --from=build /home/gradle/project/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/application/ ./

USER 65532:65532
EXPOSE 8080/tcp
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
