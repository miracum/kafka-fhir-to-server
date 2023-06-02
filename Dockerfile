# syntax=docker/dockerfile:1.4
FROM docker.io/library/gradle:8.1.1-jdk17@sha256:75a87780bd3f335e215e7f100fd3fec0e02e5b188129033112154a4ec2343924 AS build
WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /gradle

COPY build.gradle settings.gradle gradle.properties ./
RUN gradle --no-daemon build || true

COPY --chown=gradle:gradle . .

RUN <<EOF
gradle --no-daemon build  --info
gradle --no-daemon jacocoTestReport
awk -F"," '{ instructions += $4 + $5; covered += $5 } END { print covered, "/", instructions, " instructions covered"; print 100*covered/instructions, "% covered" }' build/reports/jacoco/test/jacocoTestReport.csv
java -Djarmode=layertools -jar build/libs/*.jar extract
EOF

FROM gcr.io/distroless/java17-debian11@sha256:c737fc29fc2556d3377d6a719a9842a500777fce35a7f1299acd569c73f65247
WORKDIR /opt/kafka-fhir-to-server
COPY --from=build /home/gradle/src/dependencies/ ./
COPY --from=build /home/gradle/src/snapshot-dependencies/ ./
COPY --from=build /home/gradle/src/spring-boot-loader/ ./
COPY --from=build /home/gradle/src/application/ .

USER 65532:65532
EXPOSE 8080/tcp
ARG VERSION=0.0.0
ENV APP_VERSION=${VERSION} \
    SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "org.springframework.boot.loader.JarLauncher"]
