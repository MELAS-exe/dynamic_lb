# Stage 1: Build Spring Boot application
FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime avec NGINX + Java
FROM nginx:alpine

# Installer Java JRE et bash
RUN apk add --no-cache openjdk17-jre bash procps

# Créer utilisateur pour l'application AVEC un shell valide
RUN addgroup -g 1000 -S appgroup && \
    adduser -u 1000 -S spring -G appgroup -s /bin/bash && \
    adduser nginx appgroup

# Supprimer config NGINX par défaut
RUN rm -f /etc/nginx/conf.d/default.conf

# Copier configurations NGINX
COPY nginx/nginx.conf /etc/nginx/nginx.conf
COPY nginx/upstream.conf /etc/nginx/conf.d/upstream.conf

# Créer répertoires nécessaires avec bonnes permissions
RUN mkdir -p /app /nginx-config /var/log/nginx && \
    chown -R spring:appgroup /app && \
    chown -R nginx:appgroup /etc/nginx/conf.d /nginx-config && \
    chmod -R 775 /etc/nginx/conf.d /nginx-config

# Copier l'application Spring Boot
COPY --from=build /app/target/*.jar /app/app.jar
RUN chown spring:appgroup /app/app.jar

# Copier les scripts
COPY start.sh /start.sh
COPY auto-reload.sh /usr/local/bin/auto-reload.sh
RUN chmod +x /start.sh /usr/local/bin/auto-reload.sh

# Variables d'environnement
ENV SPRING_PROFILES_ACTIVE=docker
ENV JAVA_OPTS="-Xmx512m -Xms256m"

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost/nginx-health || exit 1

CMD ["/start.sh", "/auto-reload.sh"]