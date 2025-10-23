#!/bin/sh
echo "Starting NGINX auto-reload watcher..."

LAST_MODIFIED=0
CONFIG_FILE="/nginx-config/upstream.conf"

while true; do
    sleep 60

    if [ -f "$CONFIG_FILE" ]; then
        CURRENT_MODIFIED=$(stat -c %Y "$CONFIG_FILE" 2>/dev/null || stat -f %m "$CONFIG_FILE" 2>/dev/null || echo "0")

        if [ "$CURRENT_MODIFIED" != "$LAST_MODIFIED" ] && [ "$CURRENT_MODIFIED" != "0" ]; then
            echo "[$(date)] Configuration changed, testing..."

            if nginx -t 2>&1 | tee /tmp/nginx-test.log; then
                echo "[$(date)] Configuration valid, reloading..."
                nginx -s reload

                if [ $? -eq 0 ]; then
                    echo "[$(date)] ✓ NGINX reloaded successfully"
                    LAST_MODIFIED=$CURRENT_MODIFIED
                else
                    echo "[$(date)] ✗ NGINX reload failed"
                fi
            else
                echo "[$(date)] ✗ Configuration test failed"
                cat /tmp/nginx-test.log
            fi
        fi
    fi
done