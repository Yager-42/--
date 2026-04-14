FROM maven:3.9.9-eclipse-temurin-17 AS builder

WORKDIR /workspace

COPY pom.xml ./
COPY nexus-types/pom.xml nexus-types/pom.xml
COPY nexus-api/pom.xml nexus-api/pom.xml
COPY nexus-domain/pom.xml nexus-domain/pom.xml
COPY nexus-infrastructure/pom.xml nexus-infrastructure/pom.xml
COPY nexus-trigger/pom.xml nexus-trigger/pom.xml
COPY nexus-app/pom.xml nexus-app/pom.xml

RUN mvn -q -pl nexus-app -am dependency:go-offline

COPY nexus-types/src nexus-types/src
COPY nexus-api/src nexus-api/src
COPY nexus-domain/src nexus-domain/src
COPY nexus-infrastructure/src nexus-infrastructure/src
COPY nexus-trigger/src nexus-trigger/src
COPY nexus-app/src nexus-app/src

RUN mvn -q -pl nexus-app -am -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=builder /workspace/nexus-app/target/*.jar /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
