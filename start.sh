#!/bin/bash
set -e

echo "========================================="
echo "Starting Combined NGINX + Spring Boot Container"
echo "========================================="

# Fonction de nettoyage
cleanup() {
    echo ""
    echo "Received shutdown signal, stopping services..."

    if [ ! -z "$RELOAD_PID" ]; then
        echo "Stopping auto-reload watcher (PID: $RELOAD_PID)..."
        kill -TERM $RELOAD_PID 2>/dev/null || true
        wait $RELOAD_PID 2>/dev/null || true
    fi

    if [ ! -z "$SPRING_PID" ]; then
        echo "Stopping Spring Boot (PID: $SPRING_PID)..."
        kill -TERM $SPRING_PID 2>/dev/null || true
        wait $SPRING_PID 2>/dev/null || true
    fi

    if [ ! -z "$NGINX_PID" ]; then
        echo "Stopping NGINX (PID: $NGINX_PID)..."
        nginx -s quit 2>/dev/null || kill -QUIT $NGINX_PID 2>/dev/null || true
        wait $NGINX_PID 2>/dev/null || true
    fi

    echo "Services stopped gracefully"
    exit 0
}

trap cleanup SIGTERM SIGINT SIGQUIT

mkdir -p /nginx-config /var/log/nginx

# Copy initial config
echo "Copying initial configuration..."
cp /etc/nginx/conf.d/upstream.conf /nginx-config/upstream.conf
chown nginx:appgroup /nginx-config/upstream.conf
chmod 664 /nginx-config/upstream.conf

echo "Starting Spring Boot application..."
cd /app
java $JAVA_OPTS -jar /app/app.jar &
SPRING_PID=$!
echo "Spring Boot started with PID: $SPRING_PID"

echo "Waiting for Spring Boot to initialize..."
sleep 10

if ! ps -p $SPRING_PID > /dev/null 2>&1; then
    echo "ERROR: Spring Boot failed to start!"
    exit 1
fi

echo "Starting NGINX..."
nginx -g 'daemon off;' &
NGINX_PID=$!
echo "NGINX started with PID: $NGINX_PID"

sleep 3

if ! ps -p $NGINX_PID > /dev/null 2>&1; then
    echo "ERROR: NGINX failed to start!"
    nginx -t
    exit 1
fi

echo "Starting auto-reload watcher..."
/usr/local/bin/auto-reload.sh &
RELOAD_PID=$!

echo ""
echo "========================================="
echo "✓ All services started successfully"
echo "  - Spring Boot (PID: $SPRING_PID) on port 8080"
echo "  - NGINX (PID: $NGINX_PID) on port 80"
echo "  - Auto-reload (PID: $RELOAD_PID)"
echo "========================================="

# Monitor services
while true; do
    sleep 10

    if ! ps -p $NGINX_PID > /dev/null 2>&1; then
        echo "ERROR: NGINX process died!"
        cleanup
        exit 1
    fi

    if ! ps -p $SPRING_PID > /dev/null 2>&1; then
        echo "ERROR: Spring Boot process died!"
        cleanup
        exit 1
    fi
done