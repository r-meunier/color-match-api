# Build stage: compiles the application, so no JDK or Maven is needed on the host
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
# Tests run in CI; they need Docker themselves (Testcontainers)
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:25-jre
EXPOSE 8080
# Resource directory (CSV files to import), mounted by docker-compose
ENV DOCKER_FILE_RES_DIR=/usr/api-service/res
COPY --from=build /build/target/*.war service.war
ENTRYPOINT ["java", "-jar", "service.war"]
