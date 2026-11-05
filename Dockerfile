FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home-dir /app --shell /usr/sbin/nologin makao
WORKDIR /app
COPY --from=build --chown=10001:10001 /app/target/makao-hub-backend-0.0.1-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 10000
ENV SERVER_PORT=10000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
