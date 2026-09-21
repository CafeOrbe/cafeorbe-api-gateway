FROM eclipse-temurin:21-jre
WORKDIR /app
# Requiere haber empaquetado antes: mvn package (genera target/app.jar)
COPY target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
