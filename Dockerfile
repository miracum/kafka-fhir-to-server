FROM docker.io/library/gradle:7.6.0-jdk17@sha256:9073fad2045e28b86d2d1669bc219739a84771635f033aed0fa293835dd5fad0 AS build
WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /gradle

COPY build.gradle settings.gradle gradle.properties ./
RUN gradle --no-daemon build || true

COPY --chown=gradle:gradle . .

RUN gradle --no-daemon --info build && \
    gradle --no-daemon jacocoTestReport && \
    awk -F"," '{ instructions += $4 + $5; covered += $5 } END { print covered, "/", instructions, " instructions covered"; print 100*covered/instructions, "% covered" }' build/reports/jacoco/test/jacocoTestReport.csv && \
    java -Djarmode=layertools -jar build/libs/*.jar extract

FROM gcr.io/distroless/java17-debian11@sha256:7ae1cfe1fbc8b4894db4b179d8af10a0ad80cae98f99a4b9a73019233e0793d7
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
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-XX:G1PeriodicGCInterval=1000", "-XX:G1PeriodicGCSystemLoadThreshold=1000", "org.springframework.boot.loader.JarLauncher"]
