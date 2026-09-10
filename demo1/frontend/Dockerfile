FROM node:20.20.1-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.0-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY deploy/nginx.conf /etc/nginx/conf.d/default.conf
COPY deploy/config.template.js /opt/skyfleet/config.template.js
COPY --chmod=0755 deploy/40-runtime-config.sh /docker-entrypoint.d/40-runtime-config.sh
EXPOSE 8080

