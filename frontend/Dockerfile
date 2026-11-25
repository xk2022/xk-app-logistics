FROM node:24.22.3-bookworm-slim@sha256:873f586f3794f52281bd039bf02c048a0be08fde619338f9941bad1eb6b34530 AS development
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
EXPOSE 5173
CMD ["npm", "run", "dev"]

FROM development AS build
RUN npm run build

# Static artifacts only; production hosting and TLS need a separate reviewed setup.
FROM scratch AS artifacts
COPY --from=build /app/dist /dist
