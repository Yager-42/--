ARG HOTKEY_VERSION=2.0.0

FROM maven:3.9-eclipse-temurin-8 AS builder
ARG HOTKEY_VERSION

WORKDIR /build
COPY --from=hotkeysrc / /build/source

WORKDIR /build/source/l2cache-jd-hotkey
RUN perl -0pi -e 's/PATTERN_MINUS=\"yyMMddHHmm\"/PATTERN_MINUS=\"MMddHHmm\"/' \
  /build/source/l2cache-jd-hotkey/jd-hotkey-dashboard/src/main/java/com/jd/platform/hotkey/dashboard/util/DateUtil.java
RUN mvn -pl jd-hotkey-worker,jd-hotkey-dashboard -am -DskipTests package

FROM eclipse-temurin:8-jre AS worker
WORKDIR /app
COPY --from=builder /build/source/l2cache-jd-hotkey/jd-hotkey-worker/target/jd-hotkey-worker.jar /app/jd-hotkey-worker.jar
ENTRYPOINT ["java", "-jar", "/app/jd-hotkey-worker.jar"]

FROM eclipse-temurin:8-jre AS dashboard
WORKDIR /app
COPY --from=builder /build/source/l2cache-jd-hotkey/jd-hotkey-dashboard/target/jd-hotkey-dashboard.jar /app/jd-hotkey-dashboard.jar
ENTRYPOINT ["java", "-jar", "/app/jd-hotkey-dashboard.jar"]
