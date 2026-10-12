# syntax=docker/dockerfile:1
# RelaySMS relay. Build context: the server/ directory.
#   docker build -t relaysms-relay server/

ARG NODE_IMAGE=node:22-bookworm-slim

# --- compile TypeScript -------------------------------------------------------------------
FROM ${NODE_IMAGE} AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build

# --- production dependencies only ---------------------------------------------------------
FROM ${NODE_IMAGE} AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund

# --- runtime ------------------------------------------------------------------------------
FROM ${NODE_IMAGE}
ENV NODE_ENV=production \
    HOST=0.0.0.0 \
    PORT=8080
WORKDIR /app
# Files stay owned by root and are only readable by the runtime user, which cannot modify them.
COPY --from=deps /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY package.json ./
COPY migrations ./migrations
USER node
EXPOSE 8080
# Healthy means: process up and PostgreSQL reachable (GET /readyz).
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["node", "dist/healthcheck.js"]
# Exec form: node is the main process and receives SIGTERM, which starts a graceful shutdown
# (stop accepting, close WebSockets, drain requests, close the pool; forced after 10 seconds).
CMD ["node", "dist/server.js"]
