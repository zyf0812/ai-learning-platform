# AI 学习平台

基于 Spring Boot 3 + Next.js 16 + AI 大模型构建的智能学习平台。支持文档自动解析、AI 智能问答、自动出题、在线考试、闪卡复习等核心功能。

## 技术栈

| 层级 | 技术选型 |
|------|----------|
| **前端** | Next.js 16, React 19, TypeScript, Shadcn UI, Tailwind CSS |
| **后端** | Spring Boot 3.2, Java 17, Spring Security, MyBatis, Redis |
| **数据库** | PostgreSQL + pgvector |
| **AI** | DeepSeek / 智谱 API, RAG 检索增强生成 |
| **文档解析** | Apache PDFBox, Apache POI (Word/Excel/PPT) |
| **可视化** | ECharts, React Flow |
| **构建** | Maven, npm, Docker |

## 核心功能

### AI 智能对话
基于 DeepSeek 大模型，支持对上传的文档进行智能问答。系统通过 RAG（检索增强生成）技术，先检索文档中的相关内容，再由 AI 生成回答。

### 文档管理
支持 PDF、Word、PPT 等格式文档上传与解析，自动提取文本内容，构建知识库。

### RAG 检索增强
文档上传后自动切片、向量化（基于 pgvector），支持语义级别的内容检索，为 AI 问答提供精准的知识支撑。

### 自动出题
基于文档内容，由 AI 自动生成各类考题（选择题、填空题、简答题等），支持手动指定题目类型和数量。

### 在线考试
完整的考试流程：生成试卷、在线作答、自动评分、成绩分析。支持考试记录和答题情况回溯。

### 错题本
自动收录错题，支持分类查看、重复练习、掌握状态追踪。

### 闪卡复习
基于间隔重复算法的闪卡系统，帮助用户利用遗忘曲线进行高效复习。

### 学习统计
学习时长、考试次数、平均分数等数据可视化展示，支持按时间维度分析学习趋势。

### 思维导图
文档上传后自动提取关键知识点，形成结构化知识网络，支持 React Flow 可视化交互。

### 知识图谱
基于文档内容生成关联知识图谱，直观展示知识点之间的语义关联。

### 分组学习
支持创建学习小组，成员间共享文档和笔记。

### 验证码与安全
注册环节集成图片验证码，防止恶意注册；用户名和密码前端实时校验，防 SQL 注入。

### 管理员系统
用户管理、文档审核、系统监控等后台管理功能。

### 监督模式
家长/教师监督模式，可查看学生学习进度和统计数据。

### 错题本
自动收录错题，支持分类查看、重复练习、掌握状态追踪。

### 导出功能
支持将考试题目和结果导出为文档。

## 系统架构

```
┌─────────────────────────────────────────────────────┐
│                   用户浏览器                           │
│              Next.js 16 + React 19                    │
└──────────────────┬──────────────────────────────────┘
                   │ HTTP / REST (Next.js Rewrites 代理)
                   ▼
┌─────────────────────────────────────────────────────┐
│              Docker Compose 部署                       │
│  ┌─────────────────┐  ┌─────────────────────────┐    │
│  │  Spring Boot 3   │  │  Next.js SSR (standalone)│   │
│  │  Java 17         │  │  TypeScript              │   │
│  │  REST API :8080  │  │  :3000                   │   │
│  ├─────────────────┤  └─────────────────────────┘    │
│  │ Service / Mapper │                                │
│  └────────┬────────┘                                 │
│      ┌────┴────┐                                     │
│      ▼         ▼                                     │
│  ┌───────┐ ┌───────┐                                 │
│  │PostgreSQL│ │ Redis │                                │
│  │pgvector │ │ 缓存  │                                │
│  └────────┘ └───────┘                                 │
│           │                                           │
│      ┌────┴────┐                                     │
│      ▼         ▼                                     │
│  ┌────────┐ ┌────────┐                               │
│  │DeepSeek│ │ 智谱   │                                │
│  │  AI    │ │Embedding│                               │
│  └────────┘ └────────┘                               │
└─────────────────────────────────────────────────────┘
```

## 项目结构

```
ai-learning-platform/
├── backend/                      # 后端：Spring Boot 3 + Java 17
│   ├── src/main/java/com/exam/
│   │   ├── config/               # 6 个配置（Security、JWT、CORS、Redis、Swagger）
│   │   ├── controller/           # 17 个 REST 控制器
│   │   ├── dto/                  # 8 个数据传输对象
│   │   ├── mapper/               # 14 个 MyBatis 映射接口
│   │   ├── model/                # 12 个数据模型
│   │   ├── service/              # 12 个业务服务
│   │   └── util/                 # 工具类（JWT、文档解析）
│   ├── sql/                      # 数据库初始化脚本
│   ├── Dockerfile
│   ├── .dockerignore
│   └── pom.xml
│
├── frontend/                     # 前端：Next.js 16 + React 19
│   ├── src/
│   │   ├── app/                  # 路由页面（登录、注册、dashboard 等）
│   │   ├── components/           # UI 组件（shadcn/ui）
│   │   ├── lib/                  # 工具库（API 请求等）
│   │   └── types/                # TypeScript 类型定义
│   ├── Dockerfile
│   ├── .dockerignore
│   ├── next.config.ts
│   └── package.json
│
├── docs/                         # 文档
├── screenshots/                  # 项目截图
├── .env.example                  # 环境变量示例
├── docker-compose.yml            # Docker Compose 编排
├── README.md
└── DEPLOY_ONLINE.md              # 免费云部署指南
```

## 快速开始

### 方式一：Docker Compose（推荐）

```bash
# 1. 复制环境变量并填写你的 API Key
cp .env.example backend/.env

# 2. 一键启动所有服务
docker compose up -d
```

访问 `http://localhost:3000`

### 方式二：本地开发

#### 前置条件

- Java 17+
- Node.js 20+
- PostgreSQL (含 pgvector 扩展)
- Redis

#### 数据库初始化

```bash
psql -U postgres -c "CREATE DATABASE doc_exam_platform;"
psql -U postgres -d doc_exam_platform -f backend/sql/init.sql
```

#### 启动后端

```bash
cd backend
mvn spring-boot:run
```

服务启动在 `http://localhost:8080`

#### 启动前端

```bash
cd frontend
npm install
npm run dev
```

访问 `http://localhost:3000`

### 免费云部署

参照 `DEPLOY_ONLINE.md`，使用 Railway 免费部署上线（无需信用卡）。

## 环境变量

参考 `.env.example` 创建 `.env` 文件：

```bash
cp .env.example backend/.env
```

### 后端 (`backend/.env`)

| 变量 | 说明 |
|------|------|
| `DB_PASSWORD` | PostgreSQL 密码 |
| `REDIS_PASSWORD` | Redis 密码 |
| `JWT_SECRET` | JWT 签名密钥（至少 32 字符） |
| `DEEPSEEK_API_KEY` | DeepSeek API 密钥 |
| `ZHIPU_API_KEY` | 智谱 API 密钥 |

### 前端构建 (`NEXT_PUBLIC_API_URL`)

在 `frontend/.env` 中指定：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | 后端 API 地址 |

## 截图

| 登录 | 注册 | 仪表盘 |
|:----:|:----:|:------:|
| ![登录](screenshots/01-login.png) | ![注册](screenshots/02-register.png) | ![仪表盘](screenshots/03-dashboard.png) |

| 文档列表 | 文档详情 | 知识点 | 思维导图 |
|:--------:|:--------:|:------:|:--------:|
| ![文档列表](screenshots/04-documents-list.png) | ![文档详情](screenshots/05-document-detail.png) | ![知识点](screenshots/06-knowledge-points.png) | ![思维导图](screenshots/07-mindmap.png) |

| 考试列表 | 生成试卷 | 考试详情 | 在线作答 |
|:--------:|:--------:|:--------:|:--------:|
| ![考试列表](screenshots/08-exams-list.png) | ![生成试卷](screenshots/09-exam-generate.png) | ![考试详情](screenshots/10-exam-detail.png) | ![在线作答](screenshots/11-exam-take.png) |

| 考试结果 | 错题本 | 闪卡复习 | AI 对话 |
|:--------:|:------:|:--------:|:-------:|
| ![考试结果](screenshots/12-exam-result.png) | ![错题本](screenshots/13-wrong-questions.png) | ![闪卡](screenshots/14-flashcards.png) | ![AI 助手](screenshots/15-ai-chat.png) |

| 学习小组 | 学习统计 | 管理后台 |
|:--------:|:--------:|:--------:|
| ![学习小组](screenshots/16-groups.png) | ![学习统计](screenshots/17-stats.png) | ![管理后台](screenshots/18-admin.png) |

## 路线图

- [x] 用户认证与权限管理
- [x] 文档上传与解析
- [x] AI 智能对话（RAG 检索增强）
- [x] 自动出题与在线考试
- [x] 思维导图与知识图谱
- [x] 错题本与闪卡复习
- [x] 学习统计与可视化
- [x] Docker Compose 一键部署
- [x] 验证码与安全防护
- [ ] 移动端适配
- [ ] 更多 AI 模型接入
- [ ] 国际化支持

## License

MIT
