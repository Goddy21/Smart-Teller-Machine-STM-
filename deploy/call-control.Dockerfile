# Build context: "Video Conferencing/stm-video" (the pom copies ../teller-console, ../kiosk-client and ../vendor into the jar).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY call-control/pom.xml call-control/pom.xml
RUN cd call-control && mvn -q -B dependency:go-offline
COPY call-control call-control
COPY teller-console teller-console
COPY kiosk-client kiosk-client
COPY vendor vendor
RUN cd call-control && mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/call-control/target/call-control-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
