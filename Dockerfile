FROM docker.io/library/gradle:8.12.0-jdk21@sha256:74da5b70f4799823c01971487627fd9ad1d3f9146f0f9871e34af2bd86c76b43 AS build
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

FROM gcr.io/distroless/java21-debian12:nonroot@sha256:eecb8ed3f30dab0e7a7077bf8ee535bf0e51ecfde963d05ac3d66c8754578c91
WORKDIR /opt/kafka-fhir-to-server
COPY --from=build /home/gradle/project/dependencies/ ./
COPY --from=build /home/gradle/project/spring-boot-loader/ ./
COPY --from=build /home/gradle/project/snapshot-dependencies/ ./
COPY --from=build /home/gradle/project/application/ ./

USER 65532:65532
EXPOSE 8080/tcp
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
