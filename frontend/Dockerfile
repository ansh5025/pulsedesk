# Build: docker build -t pulsedesk-web ./frontend
FROM node:20-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
# Node runs the wake-on-visit sidecar on Azure (same image, command: node /opt/waker/server.mjs).
RUN apk add --no-cache nodejs
COPY waker /opt/waker
# The official image renders /etc/nginx/templates/*.template with env vars at startup.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /src/dist /usr/share/nginx/html
ENV BACKEND_URL=http://backend:8080
EXPOSE 80
