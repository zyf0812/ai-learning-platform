# 项目上下文

## Git 远程地址
https://github.com/zyf0812/ai-learning-platform.git

## 技术栈
- 后端: Spring Boot 3.2 + Java 17, PostgreSQL+pgvector, Redis, MyBatis, DeepSeek+Zhipu AI, RAG
- 前端: Next.js 16 + React 19, TypeScript, Tailwind CSS v4, shadcn/ui, ECharts, React Flow

## 目录结构
- `backend/` — Spring Boot 后端
- `frontend/` — Next.js 前端
- 单 repo 格式

## 关键信息
- JWT 密钥、AI API 密钥存在 backend/.env（gitignored）
- Swagger: localhost:8080/swagger-ui/index.html
- 测试: 12 个（JwtUtil x4, AuthService x4, AuthController x4）
