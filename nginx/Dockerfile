FROM nginx:alpine

RUN addgroup -g 1000 -S appgroup && adduser nginx appgroup

RUN rm /etc/nginx/conf.d/default.conf

COPY nginx.conf /etc/nginx/nginx.conf

RUN mkdir -p /etc/nginx/conf.d

COPY upstream.conf /etc/nginx/conf.d/upstream.conf

COPY auto-reload.sh /usr/local/bin/auto-reload.sh
RUN chmod +x /usr/local/bin/auto-reload.sh

RUN chown -R nginx:appgroup /etc/nginx/conf.d && chmod -R 775 /etc/nginx/conf.d

EXPOSE 80 443

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost/nginx-health || exit 1

CMD sh -c "nginx -g 'daemon off;' & /usr/local/bin/auto-reload.sh"