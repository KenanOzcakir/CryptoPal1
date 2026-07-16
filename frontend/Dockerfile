# Node builds the SPA into static files, nginx serves them. The runtime image has no
# Node, no npm and no source in it: just HTML, CSS, JS and a web server.

# ---------------------------------------------------------------------------
# Build stage
# ---------------------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /build

# The manifest and the lockfile first, for the same layer-caching reason as the backend's
# pom: editing a component should not reinstall node_modules.
COPY package.json package-lock.json ./

# ci rather than install: it installs exactly what the lockfile pins and fails outright if
# the two disagree, where install would quietly resolve something newer and build an image
# that does not match what I tested.
RUN npm ci

COPY . .

# No VITE_ variables are needed to build this. The API client calls /api relative to
# wherever the page is served from, so no backend URL is compiled into the bundle and the
# same image works on any host. That is the same property that keeps this project free of
# CORS: the browser only ever talks to the origin it loaded the page from.
RUN npm run build

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
FROM nginx:1.29-alpine

# My server block replaces the default one. It serves the SPA and reverse-proxies /api/ to
# the backend, mirroring what the Vite dev server does in development.
COPY nginx.conf /etc/nginx/conf.d/default.conf

COPY --from=build /build/dist /usr/share/nginx/html

EXPOSE 80
