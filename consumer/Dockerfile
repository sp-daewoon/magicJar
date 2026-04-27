# syntax=docker/dockerfile:1.6
FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S magicjar && adduser -S -G magicjar magicjar
WORKDIR /app
COPY build/libs/consumer-*.jar /app/app.jar
USER 1000
EXPOSE 8082
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
HEALTHCHECK --interval=10s --timeout=3s --retries=10 \
  CMD wget -qO- http://localhost:8082/actuator/health | grep -q UP || exit 1
