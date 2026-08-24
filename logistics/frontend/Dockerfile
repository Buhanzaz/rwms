FROM node:22-alpine AS dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM dependencies AS test
COPY . .
RUN npm run typecheck && npm run test:run && npm run lint && npm run build
CMD ["npm", "run", "test:run"]

FROM dependencies AS build
COPY . .
ARG VITE_API_BASE_URL="/api"
ARG VITE_APP_BASE_PATH="/"
ARG VITE_MAP_STYLE_URL=""
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_APP_BASE_PATH=${VITE_APP_BASE_PATH}
ENV VITE_MAP_STYLE_URL=${VITE_MAP_STYLE_URL}
RUN npm run build

FROM nginx:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1/healthz || exit 1
CMD ["nginx", "-g", "daemon off;"]
