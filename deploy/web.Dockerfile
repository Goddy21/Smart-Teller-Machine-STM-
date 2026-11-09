# Build context: stm-portal. Builds the portal, then serves it with nginx, which also proxies the APIs and LiveKit.
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# The config is mounted from deploy/nginx/default.conf.template; nginx fills in ${SERVER_IP} at start.
