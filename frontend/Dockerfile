FROM node:20-alpine AS build
WORKDIR /src
# 按锁文件安装，保证线上构建和本地、CI 用的是同一套依赖版本
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.inc /etc/nginx/conf.d/security-headers.inc
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80 443
