#!/bin/sh
echo "Starting NGINX auto-reload watcher..."

LAST_MODIFIED=0
CONFIG_FILE="/etc/nginx/conf.d/upstream.conf"

while true; do
    sleep 5

    if [ -f "$CONFIG_FILE" ]; then
        # Get last modification time (compatible avec Alpine)
        CURRENT_MODIFIED=$(stat -c %Y "$CONFIG_FILE" 2>/dev/null || stat -f %m "$CONFIG_FILE" 2>/dev/null || echo "0")

        if [ "$CURRENT_MODIFIED" != "$LAST_MODIFIED" ] && [ "$CURRENT_MODIFIED" != "0" ]; then
            echo "[$(date)] Configuration file changed, testing..."

            # Test configuration
            if nginx -t 2>&1 | tee /tmp/nginx-test.log; then
                echo "[$(date)] Configuration valid, reloading NGINX..."
                nginx -s reload

                if [ $? -eq 0 ]; then
                    echo "[$(date)] ✓ NGINX reloaded successfully"
                    LAST_MODIFIED=$CURRENT_MODIFIED
                else
                    echo "[$(date)] ✗ NGINX reload failed"
                fi
            else
                echo "[$(date)] ✗ Configuration test failed, skipping reload"
                cat /tmp/nginx-test.log
            fi
        fi
    else
        echo "[$(date)] Warning: Config file not found: $CONFIG_FILE"
    fi
done