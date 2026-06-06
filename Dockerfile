FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/logslim-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
