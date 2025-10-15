FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -g 1000 -S appgroup && adduser -u 1000 -S spring -G appgroup

# Copy the JAR file
COPY target/*.jar app.jar

RUN chown spring:appgroup app.jar

USER spring:appgroup

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8090/api/loadbalancer/health || exit 1

# Run JAR file
ENTRYPOINT ["java", "-jar", "app.jar"]