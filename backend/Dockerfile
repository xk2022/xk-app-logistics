FROM maven:3.9.16-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-jammy@sha256:f1ad2fff1437cbcf7be96408f264ad5d2128fad9cf262f88f1827a84e60d246e
WORKDIR /app
COPY --from=build --chown=10001:10001 /app/target/xk-logistics-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
