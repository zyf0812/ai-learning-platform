# Java Spring Boot 后端 — 实施计划

> **For agentic workers:** 按 Task 顺序逐步实施，每个 Task 完成后提交。

**Goal:** 将 AI 学习平台的后端从 Next.js API Routes 迁移到 Java Spring Boot + MyBatis，前后端分离。前端（已有 Next.js 项目）只需改 API 地址。

**Architecture:** Spring Boot 3 + MyBatis + PostgreSQL + pgvector。REST API 返回 JSON，前端 Next.js 通过 HTTP 调用。认证用 Spring Security + JWT。AI 模块用 OkHttp 调用 DeepSeek API。文档解析用 Apache PDFBox + Apache POI。Embedding 用 DeepSeek API 或本地模型。

**Tech Stack:** Java 17+, Spring Boot 3.x, MyBatis 3.x, PostgreSQL 15+, pgvector, DeepSeek API, JWT (jjwt), Apache PDFBox, Apache POI, OkHttp, Jackson, Lombok

**端口：** Java 后端 8080，Next.js 前端 3000（配置 CORS 跨域）

---

## Task 1: 项目初始化

- [ ] 创建 Spring Boot 项目（Maven + Java 17）
- [ ] pom.xml 添加依赖：spring-boot-starter-web, mybatis-spring-boot-starter, postgresql, pgvector, jjwt, okhttp, pdfbox, poi, lombok
- [ ] application.yml：数据库连接、DeepSeek API Key、JWT Secret
- [ ] 启动类 ExamApplication.java
- [ ] CORS 配置（允许 localhost:3000）
- [ ] Commit

## Task 2: 数据库 + MyBatis

- [ ] SQL 脚本：建表（User/Document/DocumentChunk/KnowledgePoint/FlashCard/Exam/ExamDocument/Question/ExamAttempt/Conversation/ChatMessage/WrongQuestion/StudyStats）
- [ ] 实体类 model/
- [ ] Mapper 接口 + XML
- [ ] 配置 pgvector 类型处理器
- [ ] Commit

## Task 3: JWT 认证

- [ ] JwtUtil（生成/验证 Token）
- [ ] SecurityConfig（放行 /api/auth/**，其余拦截）
- [ ] AuthController（/api/auth/register, /api/auth/login, /api/auth/me）
- [ ] AuthService + UserMapper
- [ ] Commit

## Task 4: 文档解析器

- [ ] DocumentParser（PDFBox 解析 PDF，POI 解析 Word/PPT，纯文本 MD/TXT）
- [ ] 单元测试
- [ ] Commit

## Task 5: RAG 引擎

- [ ] EmbeddingService（调用 DeepSeek embedding API，或用 OkHttp 调本地模型）
- [ ] RAGService（分块、向量写入、向量检索）
- [ ] Commit

## Task 6: 文档管理 API

- [ ] DocumentController（POST /api/documents 上传+解析+RAG索引，GET/DELETE）
- [ ] DocumentService + DocumentMapper
- [ ] Commit

## Task 7: 知识点 API

- [ ] DeepSeekService（调用 chat API 的公共方法）
- [ ] KnowledgeService（调 DeepSeek 提取知识点）
- [ ] KnowledgeController（GET/POST /api/documents/{id}/knowledge）
- [ ] Commit

## Task 8: 考试系统 API

- [ ] ExamController（生成考题、列表、详情、删除、提交评分）
- [ ] ExamService + ExamMapper
- [ ] 出题时用 RAG 检索 + DeepSeek 生成
- [ ] 提交时自动入错题本
- [ ] Commit

## Task 9: 闪卡 API

- [ ] FlashcardController（生成、获取待复习、提交评分）
- [ ] FlashcardService（含 SM-2 算法实现）
- [ ] Commit

## Task 10: AI 对话 API

- [ ] ChatController（创建会话、发消息、获取历史）
- [ ] ChatService（RAG 检索 + DeepSeek 对话）
- [ ] Commit

## Task 11: 错题本 + 统计 + 导出 API

- [ ] WrongQuestionController
- [ ] StatsController
- [ ] ExportController（docx 导出，用 Apache POI）
- [ ] Commit

## Task 12: 前端对接

- [ ] 修改 Next.js 前端 api.ts 的 BASE 指向 http://localhost:8080
- [ ] 更新 CORS 配置
- [ ] 全流程测试
- [ ] Commit

---

## 后端 API 路由表

与原有 Next.js API 完全对应：

| Method | Path | 说明 |
|--------|------|------|
| POST | /api/auth/register | 注册 |
| POST | /api/auth/login | 登录 |
| GET | /api/auth/me | 当前用户 |
| GET | /api/documents | 文档列表 |
| POST | /api/documents | 上传文档 |
| GET | /api/documents/{id} | 文档详情 |
| DELETE | /api/documents/{id} | 删除文档 |
| GET | /api/documents/{id}/knowledge | 获取知识点 |
| POST | /api/documents/{id}/knowledge | AI 提取知识点 |
| POST | /api/exams/generate | 多文档生成考题 |
| GET | /api/exams | 考试列表 |
| GET | /api/exams/{id} | 考试详情 |
| DELETE | /api/exams/{id} | 删除考试 |
| POST | /api/exams/{id}/submit | 提交答卷 |
| GET | /api/exams/{id}/export | 导出 Word |
| GET | /api/attempts/{id} | 考试记录 |
| GET | /api/flashcards | 待复习闪卡 |
| POST | /api/flashcards/review | 提交评分 |
| GET | /api/conversations | 会话列表 |
| POST | /api/conversations | 创建会话 |
| GET | /api/conversations/{id} | 会话消息 |
| POST | /api/conversations/{id} | 发送消息 |
| GET | /api/wrong-questions | 错题列表 |
| PATCH | /api/wrong-questions | 标记掌握 |
| GET | /api/stats | 学习统计 |
