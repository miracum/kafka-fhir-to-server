FROM gradle:7.3.3-jdk17 AS build
WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /gradle

COPY build.gradle settings.gradle gradle.properties ./
RUN gradle --no-daemon build || true

COPY --chown=gradle:gradle . .

RUN gradle --no-daemon --info build && \
    gradle --no-daemon jacocoTestReport && \
    awk -F"," '{ instructions += $4 + $5; covered += $5 } END { print covered, "/", instructions, " instructions covered"; print 100*covered/instructions, "% covered" }' build/reports/jacoco/test/jacocoTestReport.csv && \
    java -Djarmode=layertools -jar build/libs/*.jar extract

FROM gcr.io/distroless/java17-debian11@sha256:2f80f7a0b18d2b54fbe806d89740239812bc061c61726403f5142f04c576790a
WORKDIR /opt/kafka-fhir-to-server
COPY --from=build /home/gradle/src/dependencies/ ./
COPY --from=build /home/gradle/src/snapshot-dependencies/ ./
COPY --from=build /home/gradle/src/spring-boot-loader/ ./
COPY --from=build /home/gradle/src/application/ .

USER 65532
ARG VERSION=0.0.0
ENV APP_VERSION=${VERSION} \
    SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=80", "org.springframework.boot.loader.JarLauncher"]

ARG GIT_REF=""
ARG BUILD_TIME=""
LABEL maintainer="miracum.org"
