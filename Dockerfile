# syntax=docker/dockerfile:1.7@sha256:dbbd5e059e8a07ff7ea6233b213b36aa516b4c53c645f1817a4dd18b83cbea56
FROM docker.io/library/gradle:8.7.0-jdk17@sha256:77a48f339b2bbc261d4448bc5ce7c5aa7c46e1e07b6d8480955aedf6891b7107 AS build
WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /gradle

COPY build.gradle settings.gradle gradle.properties ./
RUN gradle --no-daemon build || true

COPY . .

RUN <<EOF
gradle --no-daemon build  --info
gradle --no-daemon jacocoTestReport
awk -F"," '{ instructions += $4 + $5; covered += $5 } END { print covered, "/", instructions, " instructions covered"; print 100*covered/instructions, "% covered" }' build/reports/jacoco/test/jacocoTestReport.csv
java -Djarmode=layertools -jar build/libs/*.jar extract
EOF

FROM gcr.io/distroless/java17-debian12:nonroot@sha256:1083557050d06156736184777ebafbe403bea3e511356f3662d8351550b57c11
WORKDIR /opt/kafka-fhir-to-server
COPY --from=build /home/gradle/src/dependencies/ ./
COPY --from=build /home/gradle/src/spring-boot-loader/ ./
COPY --from=build /home/gradle/src/snapshot-dependencies/ ./
COPY --from=build /home/gradle/src/application/ ./

USER 65532:65532
EXPOSE 8080/tcp
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
