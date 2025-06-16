# Stage 1: Build the Spring Boot application using a Maven image
FROM openjdk:17-jdk AS build

# Install Maven on top of the OpenJDK image
# This is a basic way; for production, consider downloading a specific Maven version
# and setting up its environment variables properly. For a simple build, this might suffice.
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

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
COPY --from=build /app/target/drive-spring-0.0.1-SNAPSHOT.jar app.jar

# Expose the port your Spring Boot application listens on (default is 8080)
EXPOSE 8080

# Define the command to run your application
ENTRYPOINT ["java", "-jar", "app.jar"]
# CMD ["java", "-jar", "target/GoogleDrive-0.0.1-SNAPSHOT.jar"]
