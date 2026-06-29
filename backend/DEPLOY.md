# 部署指南

## 方式一：直接跑（开发/小团队）

### 后端
```bash
cd ai-learning-backend
mvn package -DskipTests -q
java -jar target/doc-exam-backend-1.0.0.jar
```

### 前端
```bash
cd ai-learning-frontend
npm run build
npm start   # 生产模式，端口 3000
```

访问 `http://localhost:3000`。

---

## 方式二：Nginx 反向代理（推荐）

### 1. 构建

```bash
# 后端
cd ai-learning-backend && mvn package -DskipTests -q
# jar 包在 target/doc-exam-backend-1.0.0.jar

# 前端
cd ai-learning-frontend && npm run build
# 静态文件在 .next/ 和 public/
```

### 2. 启动后端
```bash
java -jar ai-learning-backend/target/doc-exam-backend-1.0.0.jar &
# 监听 8080
```

### 3. Nginx 配置
```nginx
server {
    listen 80;
    server_name your-domain.com;

    # API 转发到 Java 后端
    location /api/ {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # 前端静态资源
    location /_next/ {
        proxy_pass http://localhost:3000;
        proxy_set_header Host $host;
    }

    # 前端页面
    location / {
        proxy_pass http://localhost:3000;
        proxy_set_header Host $host;
    }
}
```

### 4. 启动前端
```bash
cd ai-learning-frontend && npm start &
```

---

## 方式三：Docker

```dockerfile
# 前端 Dockerfile
FROM node:20-alpine
WORKDIR /app
COPY ai-learning-frontend/package*.json ./
RUN npm ci --production
COPY ai-learning-frontend/.next ./.next
COPY ai-learning-frontend/public ./public
EXPOSE 3000
CMD ["npm", "start"]

# 后端 Dockerfile
FROM eclipse-temurin:17-jre-alpine
COPY ai-learning-backend/target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
```

```bash
docker-compose up -d
```

---

## 快速

如果你就想现在能用，最简单：

```bash
# 终端1: 后端
cd ai-learning-backend && mvn spring-boot:run

# 终端2: 前端
cd ai-learning-frontend && npm run dev
```

访问 `http://localhost:3000`，就用。（Web 的话需要域名+备案+云服务器，那是另外一个话题了）
