# Stage 1: Build the Spring Boot application
# Use a minimal OpenJDK Alpine image for the build stage.
FROM openjdk:17-jdk-alpine AS build

# Install curl and tar, which are needed to download and extract Maven
RUN apk add --no-cache curl tar bash

# Install Maven explicitly.
ARG MAVEN_VERSION=3.9.10
ARG BASE_URL=https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries
ENV MAVEN_HOME /opt/maven
ENV PATH $MAVEN_HOME/bin:$PATH

RUN mkdir -p ${MAVEN_HOME} \
    && curl -fsSL ${BASE_URL}/apache-maven-${MAVEN_VERSION}-bin.tar.gz \
    | tar -xzC ${MAVEN_HOME} --strip-components=1 \
    && mvn -version

# Set the working directory inside the build container
WORKDIR /app

# Copy the Maven project files (pom.xml first to leverage Docker cache for dependencies)
COPY pom.xml .

# Download dependencies (this layer will be cached if pom.xml doesn't change)
RUN mvn dependency:go-offline

# Copy the rest of the source code
COPY src ./src

# Build the application, skipping tests for faster build in CI/CD
RUN mvn clean install -DskipTests

# Stage 2: Create the final lightweight runtime image
FROM openjdk:17-jdk-slim

# Set the working directory for the runtime container
WORKDIR /app

# Copy the built JAR file from the build stage
# *** IMPORTANT CHANGE HERE: Corrected JAR file name ***
COPY --from=build /app/target/drive-spring-0.0.1-SNAPSHOT.jar app.jar

# Expose the port your Spring Boot application listens on (default is 8080)
EXPOSE 8080

# Define the command to run your application
ENTRYPOINT ["java", "-jar", "app.jar"]