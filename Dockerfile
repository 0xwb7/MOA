FROM eclipse-temurin:21-jre

WORKDIR /app
COPY app.jar /app/app.jar

USER 10001:10001

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
