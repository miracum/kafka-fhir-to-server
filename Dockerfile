FROM docker.io/library/gradle:8.11.0-jdk17@sha256:2903c3e041b429cbaeabcf39f00b763acd7f5bf15fb0e5f60055fc8f50a90c7f AS build
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

FROM gcr.io/distroless/java17-debian12:nonroot@sha256:10709c27d0a8fe1e206f6efbd9c036caf13fcf34bc972d46d453a51a5fc394ec
WORKDIR /opt/kafka-fhir-to-server
COPY --from=build /home/gradle/src/dependencies/ ./
COPY --from=build /home/gradle/src/spring-boot-loader/ ./
COPY --from=build /home/gradle/src/snapshot-dependencies/ ./
COPY --from=build /home/gradle/src/application/ ./

USER 65532:65532
EXPOSE 8080/tcp
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
