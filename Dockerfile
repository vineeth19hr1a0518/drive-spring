# Use Maven image with JDK 17 pre-installed
FROM maven:3.9.4-eclipse-temurin-17

# Set the working directory inside the container
WORKDIR /app

# Copy the entire project into the container
COPY . .

# Grant execute permissions to mvnw
RUN chmod +x mvnw

# Build the Spring Boot application
RUN ./mvnw clean install -DskipTests

# Replace with your actual JAR filename after first build
CMD ["java", "-jar", "target/GoogleDrive-0.0.1-SNAPSHOT.jar"]
