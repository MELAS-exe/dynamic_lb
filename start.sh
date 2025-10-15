#!/bin/bash
set -e

echo "========================================="
echo "Starting Combined NGINX + Spring Boot Container"
echo "========================================="

# Fonction de nettoyage
cleanup() {
    echo ""
    echo "Received shutdown signal, stopping services..."

    # Arrêter auto-reload
    if [ ! -z "$RELOAD_PID" ]; then
        echo "Stopping auto-reload watcher (PID: $RELOAD_PID)..."
        kill -TERM $RELOAD_PID 2>/dev/null || true
        wait $RELOAD_PID 2>/dev/null || true
    fi

    # Arrêter Spring Boot
    if [ ! -z "$SPRING_PID" ]; then
        echo "Stopping Spring Boot (PID: $SPRING_PID)..."
        kill -TERM $SPRING_PID 2>/dev/null || true
        wait $SPRING_PID 2>/dev/null || true
    fi

    # Arrêter NGINX
    if [ ! -z "$NGINX_PID" ]; then
        echo "Stopping NGINX (PID: $NGINX_PID)..."
        nginx -s quit 2>/dev/null || kill -QUIT $NGINX_PID 2>/dev/null || true
        wait $NGINX_PID 2>/dev/null || true
    fi

    echo "Services stopped gracefully"
    exit 0
}

# Intercepter les signaux
trap cleanup SIGTERM SIGINT SIGQUIT

# Vérifier que les répertoires existent
mkdir -p /nginx-config
mkdir -p /var/log/nginx

# Copier la config initiale vers le volume partagé
cp /etc/nginx/conf.d/upstream.conf /nginx-config/upstream.conf
chown nginx:appgroup /nginx-config/upstream.conf
chmod 664 /nginx-config/upstream.conf

echo "Starting Spring Boot application..."
cd /app
java $JAVA_OPTS -jar /app/app.jar &
SPRING_PID=$!
echo "Spring Boot started with PID: $SPRING_PID"

# Attendre que Spring Boot démarre
echo "Waiting for Spring Boot to initialize..."
sleep 10

# Vérifier que Spring Boot est bien démarré
if ! ps -p $SPRING_PID > /dev/null 2>&1; then
    echo "ERROR: Spring Boot failed to start!"
    exit 1
fi

echo "Spring Boot is running"

echo "Starting NGINX..."
# Démarrer NGINX en arrière-plan
nginx -g 'daemon off;' &
NGINX_PID=$!
echo "NGINX started with PID: $NGINX_PID"

# Attendre que NGINX démarre
sleep 3

# Vérifier que NGINX est bien démarré
if ! ps -p $NGINX_PID > /dev/null 2>&1; then
    echo "ERROR: NGINX failed to start!"
    nginx -t
    exit 1
fi

echo "NGINX is running"

echo "Starting auto-reload watcher..."
# Démarrer le watcher de configuration
/usr/local/bin/auto-reload.sh &
RELOAD_PID=$!
echo "Auto-reload watcher started with PID: $RELOAD_PID"

echo ""
echo "========================================="
echo "✓ All services started successfully"
echo "  - Spring Boot (PID: $SPRING_PID) on port 8090"
echo "  - NGINX (PID: $NGINX_PID) on port 80"
echo "  - Auto-reload (PID: $RELOAD_PID)"
echo "========================================="
echo ""

# Fonction de monitoring
monitor_services() {
    while true; do
        sleep 10

        # Vérifier NGINX
        if ! ps -p $NGINX_PID > /dev/null 2>&1; then
            echo "ERROR: NGINX process died!"
            cleanup
            exit 1
        fi

        # Vérifier Spring Boot
        if ! ps -p $SPRING_PID > /dev/null 2>&1; then
            echo "ERROR: Spring Boot process died!"
            cleanup
            exit 1
        fi

        # Vérifier Auto-reload
        if ! ps -p $RELOAD_PID > /dev/null 2>&1; then
            echo "WARNING: Auto-reload watcher died! Restarting..."
            /usr/local/bin/auto-reload.sh &
            RELOAD_PID=$!
            echo "Auto-reload restarted with PID: $RELOAD_PID"
        fi
    done
}

# Lancer le monitoring en arrière-plan
monitor_services &
MONITOR_PID=$!

# Attendre l'un des processus principaux
wait -n $NGINX_PID $SPRING_PID

# Si on arrive ici, un processus est mort
EXIT_STATUS=$?
echo "ERROR: One of the main processes died with status $EXIT_STATUS"
cleanup
exit $EXIT_STATUS