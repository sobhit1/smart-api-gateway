# --- Build the Application ---
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Copy project files
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .

# Grant execution rights to the wrapper
RUN chmod +x mvnw

# Download dependencies
RUN ./mvnw dependency:go-offline

# Copy source code
COPY src ./src

# Build using the wrapper
RUN ./mvnw clean package -DskipTests=true

# --- Run the Application ---
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Copy the JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Expose the port your app runs on
EXPOSE 8080

# The command to start your app
ENTRYPOINT ["java", "-jar", "app.jar"]