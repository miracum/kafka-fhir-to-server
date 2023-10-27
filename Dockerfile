# syntax=docker/dockerfile:1.4
FROM docker.io/library/gradle:8.4.0-jdk17@sha256:9fde0212a97ab5c96e17797bba1f4f63c223a5ca04131d8dbafce038c53c4eb3 AS build
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

FROM gcr.io/distroless/java17-debian12:nonroot@sha256:74aa41e4cb8b6cc76391c0679370be6bd75ebf60917a7f9fb5dd1b4c7b1a1854
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
