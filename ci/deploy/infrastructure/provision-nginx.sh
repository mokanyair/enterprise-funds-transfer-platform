#!/usr/bin/env bash
set -Eeuo pipefail

# ============================================================
# NovaBank TEST frontend infrastructure provisioning.
#
# Phase 1: Validate platform and execution privileges.
# Phase 2: Install NGINX and inspect existing configuration.
# Phase 3: Prepare versioned frontend directories.
# Phase 4: Configure dedicated loopback-only NGINX hosting.
# Phase 5: Configure SELinux access.
# Phase 6: Validate configuration and start NGINX.
# Phase 7: Verify listener isolation and HTTP health.
#
# This script does not deploy frontend application artifacts.
# ============================================================

echo "PHASE 1: Validate prerequisites"

[[ "$EUID" -eq 0 ]] || {
    echo "ERROR: Root privileges required" >&2
    exit 1
}

source /etc/os-release

[[ "$ID" == amzn && "$VERSION_ID" == 2023 ]] || {
    echo "ERROR: Amazon Linux 2023 required" >&2
    exit 1
}

echo "PHASE 2: Install NGINX"

dnf install -y nginx

# Do not replace an unrelated active NGINX installation.
if systemctl is-active --quiet nginx &&
    ! grep -q 'NOVABANK_TEST_MANAGED' /etc/nginx/nginx.conf
then
    echo "ERROR: Existing unmanaged NGINX service" >&2
    exit 1
fi

echo "PHASE 3: Prepare frontend directories"

install -d -o root -g root -m 0755 \
    /var/www/novabank \
    /var/www/novabank/releases

echo "PHASE 4: Configure dedicated NGINX service"

CONFIG=/etc/nginx/nginx.conf

BACKUP="/etc/nginx/nginx.conf.pre-novabank.$(date -u +%Y%m%dT%H%M%SZ)"

cp -p "$CONFIG" "$BACKUP"

TEMP_CONFIG="$(mktemp /etc/nginx/nginx.conf.XXXXXXXX)"

trap 'rm -f -- "$TEMP_CONFIG"' EXIT

cat > "$TEMP_CONFIG" <<'NGINX'
# NOVABANK_TEST_MANAGED

user nginx;
worker_processes auto;

error_log /var/log/nginx/error.log;
pid /run/nginx.pid;

events {
    worker_connections 1024;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    access_log /var/log/nginx/access.log;

    sendfile on;
    keepalive_timeout 65;
    server_tokens off;

    server {
        listen 127.0.0.1:80 default_server;
        server_name localhost;

        root /var/www/novabank/current;
        index index.html;

        add_header X-Content-Type-Options "nosniff" always;
        add_header X-Frame-Options "DENY" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;

        location = /healthz {
            access_log off;
            default_type text/plain;
            return 200 "nginx-up\n";
        }

        location / {
            if (-f /var/www/novabank/maintenance) {
                return 503;
            }

            if (!-f /var/www/novabank/current/index.html) {
                return 503;
            }

            try_files $uri $uri/ /index.html;
        }

        location = /index.html {
            if (-f /var/www/novabank/maintenance) {
                return 503;
            }

            add_header Cache-Control "no-store" always;
            add_header X-Content-Type-Options "nosniff" always;
            add_header X-Frame-Options "DENY" always;
            add_header Referrer-Policy "strict-origin-when-cross-origin" always;

            try_files $uri =503;
        }

        location /assets/ {
            if (-f /var/www/novabank/maintenance) {
                return 503;
            }

            add_header Cache-Control "public, max-age=31536000, immutable";
            add_header X-Content-Type-Options "nosniff" always;
            add_header X-Frame-Options "DENY" always;
            add_header Referrer-Policy "strict-origin-when-cross-origin" always;

            try_files $uri =404;
        }

        location = /mockServiceWorker.js {
            return 404;
        }

        location = /api/v1 {
            return 404;
        }

        location /api/v1/ {
            if (-f /var/www/novabank/maintenance) {
                return 503;
            }

            proxy_pass http://127.0.0.1:8080;

            proxy_http_version 1.1;
            proxy_set_header Host $http_host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $remote_addr;
            proxy_set_header X-Forwarded-Proto $scheme;

            proxy_connect_timeout 5s;
            proxy_send_timeout 30s;
            proxy_read_timeout 30s;
        }

        location ~ /\. {
            deny all;
        }
    }
}
NGINX

chmod 0644 "$TEMP_CONFIG"

# Validate the candidate before replacing the active configuration.
nginx -t -c "$TEMP_CONFIG"

mv -fT "$TEMP_CONFIG" "$CONFIG"

echo "PHASE 5: Configure SELinux"

if command -v selinuxenabled >/dev/null &&
    selinuxenabled
then
    restorecon -F "$CONFIG"
    restorecon -RF /var/www/novabank

    setsebool -P httpd_can_network_connect on
fi

echo "PHASE 6: Start NGINX"

if ! nginx -t || ! systemctl restart nginx; then
    echo "ERROR: Restoring previous NGINX configuration" >&2

    cp -p "$BACKUP" "$CONFIG"

    nginx -t

    echo "Manual service recovery may be required" >&2
    exit 1
fi

systemctl enable nginx

systemctl is-active --quiet nginx

echo "PHASE 7: Validate listener and health"

LISTENERS="$(ss -H -lnt 'sport = :80' | awk '{print $4}')"

[[ "$LISTENERS" == "127.0.0.1:80" ]] || {
    echo "ERROR: Unexpected HTTP listeners: $LISTENERS" >&2
    systemctl stop nginx
    exit 1
}

curl -fsS --max-time 5 \
    http://127.0.0.1/healthz

echo "NGINX_PROVISIONING_SUCCESS"
