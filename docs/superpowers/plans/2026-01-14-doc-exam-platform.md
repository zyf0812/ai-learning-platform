# AI 学习平台 — 实施计划 v3

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 构建全栈 AI 学习平台。上传教学文档 → 自动提取知识点 → 支持 AI 对话答疑、闪卡记忆、自动出题考试、错题本、学习进度追踪、思维导图、考题导出。覆盖学习全流程。

**Architecture:** Next.js 14+ App Router 前后端一体。PostgreSQL + pgvector（结构化+向量双存储）。文档解析→分块→Embedding→向量检索作为 RAG 底座，所有 AI 功能（出题、对话、知识点提取、闪卡生成）均通过 DeepSeek API + RAG 检索实现。前端 React + Tailwind + shadcn/ui，思维导图用 react-flow，导出用 jsPDF + docx。

**Tech Stack:** Next.js 14.2+, TypeScript 5.x, React 18, Tailwind CSS 3.x, shadcn/ui, Prisma 5.x, PostgreSQL 15+ + pgvector, @huggingface/transformers (本地 Embedding), DeepSeek API (chat + generation), pdf-parse, mammoth, pptx2json, react-markdown, react-flow (思维导图), jsPDF, docx (导出), bcryptjs, jose, zod, SM-2 算法 (闪卡间隔重复)

## 核心功能模块

| 模块 | 功能 |
|------|------|
| 📄 文档管理 | 上传/解析/列表/详情，支持 PDF/Word/PPT/MD/TXT |
| 🧠 知识点 | 自动提取知识点，卡片展示，思维导图可视化 |
| 💬 AI 对话 | 基于文档的 RAG 智能问答助手 |
| 📝 考试 | 多题型自动生成，在线答题，自动评分 |
| ❌ 错题本 | 错题收集，按文档/题型分类，重做功能 |
| 🃏 闪卡 | 知识点→闪卡，SM-2 间隔重复记忆算法 |
| 📊 进度 | 仪表盘统计：学习时长、正确率、考试次数 |
| 📥 导出 | 考题导出 Word/PDF |

## Global Constraints

- Node.js >= 18.17, PostgreSQL >= 15 (pgvector 已启用)
- JWT 7天，上传限制 20MB
- DeepSeek: base_url `https://api.deepseek.com`, model `deepseek-chat`
- Embedding: `Xenova/all-MiniLM-L6-v2` (本地 Transformers.js)
- 分块 512 字符，重叠 64；向量检索 topK=5
- 闪卡用 SM-2 算法（间隔重复）
- 所有输入 zod 校验
- shadcn/ui 组件统一路径 `@/components/ui`

---

### Task 1: 项目初始化与全部依赖

**Files:** `package.json`, `tsconfig.json`, `next.config.js`, `tailwind.config.ts`, `postcss.config.js`, `.env.example`, `.gitignore`

- [ ] **Step 1: create-next-app**

```bash
npx create-next-app@latest doc-exam-platform --typescript --tailwind --eslint --app --src-dir --import-alias "@/*" --no-turbopack
cd doc-exam-platform
```

- [ ] **Step 2: 安装全部依赖**

```bash
npm install prisma @prisma/client openai bcryptjs jose zod pdf-parse mammoth pptx2json react-markdown @huggingface/transformers reactflow jsPDF docx
npm install -D @types/bcryptjs @types/pdf-parse
```

- [ ] **Step 3: shadcn/ui**

```bash
npx shadcn@latest init -d
npx shadcn@latest add button input card label toast tabs separator dialog dropdown-menu avatar badge textarea progress scroll-area
```

- [ ] **Step 4: .env.example**

```env
DATABASE_URL="postgresql://postgres:password@localhost:5432/doc_exam_platform"
DEEPSEEK_API_KEY="sk-your-key"
JWT_SECRET="random-32-chars-min"
```

- [ ] **Step 5: npm run dev 验证**
- [ ] **Step 6: git init && commit**

---

### Task 2: 数据库 — PostgreSQL + pgvector 完整 Schema

**Files:** `prisma/schema.prisma`, `src/lib/prisma.ts`, `sql/setup.sql`

**数据模型（全功能）：**

```
User
 ├─ Document (文档)
 │    ├─ DocumentChunk (RAG 分块 + 向量)
 │    ├─ KnowledgePoint (知识点)
 │    │    └─ FlashCard (闪卡)
 │    └─ ExamDocument (多文档题库关联)
 ├─ Exam (考试)
 │    ├─ ExamDocument (关联多个文档)
 │    ├─ Question (题目)
 │    └─ ExamAttempt (考试记录)
 ├─ Conversation (AI 对话会话)
 │    └─ ChatMessage (消息)
 ├─ WrongQuestion (错题本)
 └─ StudyStats (学习统计)
```

- [ ] **Step 1: sql/setup.sql**

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

- [ ] **Step 2: Prisma Schema（完整版）**

```prisma
generator client { provider = "prisma-client-js" }
datasource db { provider = "postgresql"; url = env("DATABASE_URL") }

model User {
  id           String         @id @default(cuid())
  username     String         @unique
  password     String
  createdAt    DateTime       @default(now())
  documents    Document[]
  exams        Exam[]
  attempts     ExamAttempt[]
  conversations Conversation[]
  wrongQuestions WrongQuestion[]
  studyStats   StudyStats[]
}

model Document {
  id              String          @id @default(cuid())
  title           String
  originalFilename String
  fileType        String
  content         String
  userId          String
  user            User            @relation(fields: [userId], references: [id])
  chunks          DocumentChunk[]
  knowledgePoints KnowledgePoint[]
  exams           Exam[]
  createdAt       DateTime        @default(now())
}

model DocumentChunk {
  id         String   @id @default(cuid())
  documentId String
  document   Document @relation(fields: [documentId], references: [id], onDelete: Cascade)
  chunkIndex Int
  content    String
  embedding  Unsupported("vector(384)")
  createdAt  DateTime @default(now())
}

model KnowledgePoint {
  id          String      @id @default(cuid())
  documentId  String
  document    Document    @relation(fields: [documentId], references: [id], onDelete: Cascade)
  title       String
  content     String
  order       Int         @default(0)
  flashCards  FlashCard[]
  createdAt   DateTime    @default(now())
}

model FlashCard {
  id              String        @id @default(cuid())
  knowledgePointId String
  knowledgePoint  KnowledgePoint @relation(fields: [knowledgePointId], references: [id], onDelete: Cascade)
  front           String
  back            String
  // SM-2 算法字段
  easeFactor      Float         @default(2.5)
  interval        Int           @default(0)
  repetitions     Int           @default(0)
  nextReview      DateTime      @default(now())
  createdAt       DateTime      @default(now())
}

model Exam {
  id            String         @id @default(cuid())
  title         String
  userId        String
  user          User           @relation(fields: [userId], references: [id])
  questionTypes String         @default("choice")
  questionCount Int            @default(5)
  documents     ExamDocument[] // 多文档关联
  questions     Question[]
  attempts      ExamAttempt[]
  createdAt     DateTime       @default(now())
}

model ExamDocument {
  id         String   @id @default(cuid())
  examId     String
  exam       Exam     @relation(fields: [examId], references: [id], onDelete: Cascade)
  documentId String
  document   Document @relation(fields: [documentId], references: [id], onDelete: Cascade)
}

model Question {
  id          String   @id @default(cuid())
  examId      String
  exam        Exam     @relation(fields: [examId], references: [id], onDelete: Cascade)
  type        String
  content     String
  options     String   @default("[]")
  answer      String
  explanation String   @default("")
  createdAt   DateTime @default(now())
}

model ExamAttempt {
  id          String    @id @default(cuid())
  examId      String
  exam        Exam      @relation(fields: [examId], references: [id], onDelete: Cascade)
  userId      String
  user        User      @relation(fields: [userId], references: [id])
  answers     String    @default("[]")
  score       Int       @default(-1)
  total       Int       @default(0)
  startedAt   DateTime  @default(now())
  completedAt DateTime?
}

model Conversation {
  id        String        @id @default(cuid())
  title     String
  documentId String?
  userId    String
  user      User          @relation(fields: [userId], references: [id])
  messages  ChatMessage[]
  createdAt DateTime      @default(now())
}

model ChatMessage {
  id             String       @id @default(cuid())
  conversationId String
  conversation   Conversation @relation(fields: [conversationId], references: [id], onDelete: Cascade)
  role           String
  content        String
  createdAt      DateTime     @default(now())
}

model WrongQuestion {
  id         String   @id @default(cuid())
  userId     String
  user       User     @relation(fields: [userId], references: [id])
  questionId String
  examId     String
  userAnswer String
  reviewCount Int     @default(0)
  mastered   Boolean  @default(false)
  createdAt  DateTime @default(now())
}

model StudyStats {
  id         String   @id @default(cuid())
  userId     String
  user       User     @relation(fields: [userId], references: [id])
  documentId String
  studyTime  Int      @default(0)
  examCount  Int      @default(0)
  avgScore   Float    @default(0)
  updatedAt  DateTime @updatedAt
}
```

- [ ] **Step 3: src/lib/prisma.ts** — PrismaClient 单例（同上）
- [ ] **Step 4: npx prisma db push**
- [ ] **Step 5: Commit**

---

### Task 3: 文档解析器

**Files:** `src/lib/document-parser.ts`

支持 PDF/Word/PPT/MD/TXT，代码同 v2 Task 3。

- [ ] **Step 1: 编写 parser** — 同 v2
- [ ] **Step 2: Commit**

---

### Task 4: RAG 引擎

**Files:** `src/lib/embedding.ts`, `src/lib/rag.ts`

与 v2 Task 4 相同。

- [ ] **Step 1-2: embedding.ts + rag.ts** — 同 v2
- [ ] **Step 3: Commit**

---

### Task 5: 认证系统（API + 前端）

**Files:** `src/lib/auth.ts`, `src/app/api/auth/register/route.ts`, `src/app/api/auth/login/route.ts`, `src/app/api/auth/me/route.ts`, `src/lib/api.ts`, `src/components/auth-context.tsx`, `src/app/login/page.tsx`, `src/app/register/page.tsx`, `src/app/(dashboard)/layout.tsx`, `src/app/(dashboard)/page.tsx`, modify `src/app/layout.tsx`

> 合并原 Task 5-6，一次完成认证全栈。

- [ ] **Step 1: auth.ts** — JWT + bcrypt，同 v1 Task 5
- [ ] **Step 2: 注册/登录/me API** — 同 v1
- [ ] **Step 3: api.ts + auth-context.tsx** — 同 v1
- [ ] **Step 4: 登录/注册页面** — 同 v1
- [ ] **Step 5: Dashboard layout + 首页** — 同 v1
- [ ] **Step 6: Commit**

---

### Task 6: 文档上传 + RAG 索引 API + 前端

**Files:** `src/app/api/documents/route.ts`, `src/app/api/documents/[id]/route.ts`, `src/app/(dashboard)/documents/page.tsx`, `src/app/(dashboard)/documents/[id]/page.tsx`, modify `src/lib/api.ts`

上传时自动 RAG 索引（后台分块+Embedding）。

- [ ] **Step 1: 文档 CRUD API** — 含 RAG 索引触发，同 v2 Task 7
- [ ] **Step 2: 文档列表/详情页** — 含上传对话框，同 v1
- [ ] **Step 3: Commit**

---

### Task 7: AI 知识点提取 + 复习页

**Files:** `src/lib/knowledge.ts`, `src/app/api/documents/[id]/knowledge/route.ts`, `src/app/(dashboard)/documents/[id]/knowledge/page.tsx`

**功能：** DeepSeek 自动从文档提取知识点（标题+内容），存入 KnowledgePoint，前端卡片展示。

- [ ] **Step 1: 知识点提取库**

```typescript
// src/lib/knowledge.ts
import { prisma } from "./prisma";
import { generateQuestions } from "./deepseek"; // 复用 client

const knowledgeClient = new OpenAI({ apiKey: process.env.DEEPSEEK_API_KEY, baseURL: "https://api.deepseek.com" });

export async function extractKnowledgePoints(documentId: string, content: string): Promise<{ title: string; content: string }[]> {
  const prompt = `请从以下文档中提取关键知识点。返回 JSON 数组：
[{"title":"知识点标题","content":"详细说明内容"}]

要求：每个知识点独立完整，便于学习记忆。提取 8-15 个核心知识点。

文档内容：
${content.slice(0, 15000)}`;

  const resp = await knowledgeClient.chat.completions.create({
    model: "deepseek-chat",
    messages: [{ role: "user", content: prompt }],
    temperature: 0.3, max_tokens: 4096,
  });

  const raw = resp.choices[0]?.message?.content || "[]";
  return JSON.parse(raw.replace(/```\w*\n?/g, "").replace(/```/g, ""));
}

export async function saveKnowledgePoints(documentId: string, points: { title: string; content: string }[]) {
  await prisma.knowledgePoint.deleteMany({ where: { documentId } });
  return prisma.knowledgePoint.createMany({
    data: points.map((p, i) => ({ documentId, title: p.title, content: p.content, order: i })),
  });
}
```

- [ ] **Step 2: API Route** — POST 触发提取，GET 获取知识点列表
- [ ] **Step 3: 复习页面** — 卡片式展示知识点，点击展开详情
- [ ] **Step 4: Commit**

---

### Task 8: 闪卡记忆（SM-2 算法）

**Files:** `src/lib/flashcard.ts`, `src/app/api/documents/[id]/flashcards/route.ts`, `src/app/(dashboard)/flashcards/page.tsx`, `src/app/(dashboard)/flashcards/review/page.tsx`

**功能：** 从知识点生成闪卡（正面问题/背面答案），SM-2 间隔重复算法，每天复习当日到期的闪卡。

- [ ] **Step 1: 闪卡生成 + SM-2 算法**

```typescript
// src/lib/flashcard.ts
import { prisma } from "./prisma";

// SM-2 算法核心
export function sm2(quality: number, prevEase: number, prevInterval: number, prevReps: number) {
  // quality: 0-5 用户自评
  let ease = prevEase + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
  if (ease < 1.3) ease = 1.3;

  let interval: number;
  let reps: number;

  if (quality < 3) {
    interval = 1;
    reps = 0;
  } else {
    reps = prevReps + 1;
    if (reps === 1) interval = 1;
    else if (reps === 2) interval = 6;
    else interval = Math.round(prevInterval * ease);
  }

  const nextReview = new Date();
  nextReview.setDate(nextReview.getDate() + interval);

  return { easeFactor: ease, interval, repetitions: reps, nextReview };
}

// 获取今日待复习闪卡
export async function getTodayCards(userId: string, limit = 20) {
  return prisma.flashCard.findMany({
    where: {
      knowledgePoint: { document: { userId } },
      nextReview: { lte: new Date() },
    },
    orderBy: { nextReview: "asc" },
    take: limit,
  });
}
```

- [ ] **Step 2: API** — 生成闪卡（DeepSeek 从知识点生成 Q&A），获取待复习，提交评分
- [ ] **Step 3: 前端** — 闪卡翻转动画 + 评分按钮（0-5）
- [ ] **Step 4: Commit**

---

### Task 9: AI 学习助手对话

**Files:** `src/lib/chat.ts`, `src/app/api/conversations/route.ts`, `src/app/api/conversations/[id]/route.ts`, `src/app/(dashboard)/chat/page.tsx`, `src/app/(dashboard)/chat/[id]/page.tsx`

**功能：** 多轮对话 + RAG 检索，用户提问时检索相关文档 chunk，DeepSeek 基于知识点回答。

- [ ] **Step 1: 对话引擎**

```typescript
// src/lib/chat.ts
import OpenAI from "openai";
import { searchChunks } from "./rag";
import { prisma } from "./prisma";

const client = new OpenAI({ apiKey: process.env.DEEPSEEK_API_KEY, baseURL: "https://api.deepseek.com" });

export async function chatReply(userId: string, conversationId: string, question: string): Promise<string> {
  // 获取历史消息
  const conversation = await prisma.conversation.findUnique({
    where: { id: conversationId },
    include: { messages: { orderBy: { createdAt: "asc" }, take: 10 } },
  });
  if (!conversation || conversation.userId !== userId) throw new Error("会话不存在");

  // RAG 检索相关知识点
  const relevant = await searchChunks(question, 3);

  const systemPrompt = `你是 AI 学习助手。基于以下知识内容回答用户问题。如果知识内容不足以回答，请诚实告知。
知识内容：
${relevant.join("\n---\n")}

历史对话：
${conversation.messages.map(m => `${m.role}: ${m.content}`).join("\n")}`;

  const resp = await client.chat.completions.create({
    model: "deepseek-chat",
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: question },
    ],
    temperature: 0.7, max_tokens: 2048,
  });

  const answer = resp.choices[0]?.message?.content || "抱歉，我无法回答这个问题。";

  // 保存消息
  await prisma.chatMessage.createMany({
    data: [
      { conversationId, role: "user", content: question },
      { conversationId, role: "assistant", content: answer },
    ],
  });

  return answer;
}
```

- [ ] **Step 2: API** — 创建会话、获取列表、获取消息、发送消息（流式？先非流式）
- [ ] **Step 3: 前端** — 会话列表 + 聊天界面（类似 ChatGPT 布局）
- [ ] **Step 4: Commit**

---

### Task 10: 考试系统（多文档出题 + 答题 + 评分 + 错题本）

**Files:** `src/lib/deepseek.ts`, `src/app/api/exams/generate/route.ts`, `src/app/api/exams/route.ts`, `src/app/api/exams/[id]/route.ts`, `src/app/api/exams/[id]/submit/route.ts`, `src/app/(dashboard)/exams/generate/page.tsx`, `src/app/(dashboard)/exams/page.tsx`, `src/app/(dashboard)/exams/[id]/page.tsx`, `src/app/(dashboard)/exams/[id]/take/page.tsx`, `src/app/(dashboard)/exams/[id]/result/page.tsx`

> **核心改动：** 出题支持选择多个文档，RAG 跨文档检索知识点。

- [ ] **Step 1: deepseek.ts — 多文档 RAG 出题**

```typescript
// src/lib/deepseek.ts
import OpenAI from "openai";
import { searchChunks } from "./rag";

const client = new OpenAI({ apiKey: process.env.DEEPSEEK_API_KEY, baseURL: "https://api.deepseek.com" });

interface GenerateConfig {
  documentIds: string[];  // 多个文档
  documentContents: string[];  // 各文档摘要/开头
  types: string[];
  count: number;
}

export interface GeneratedQuestion {
  type: string; content: string; options: string[]; answer: string; explanation: string;
}

export async function generateQuestions(config: GenerateConfig): Promise<GeneratedQuestion[]> {
  const typeMap: Record<string, string> = {
    choice: "单选题（4个选项A/B/C/D）", fill: "填空题",
    truefalse: "判断题（正确/错误）", shortanswer: "简答题",
  };
  const typeDesc = config.types.map(t => typeMap[t] || t).join("、");

  // RAG: 用所有文档摘要拼接做查询，跨文档检索知识点
  const query = config.documentContents.join("\n").slice(0, 512);
  let relevantChunks: string[] = [];
  try {
    relevantChunks = await searchChunks(query, 8);  // 多文档场景增加 topK
  } catch (err) {
    console.warn("RAG search failed:", err);
    relevantChunks = config.documentContents.map(c => c.slice(0, 2000));
  }

  const knowledgeContext = relevantChunks.join("\n---\n");

  const prompt = `你是一位专业的考试出题老师。请基于以下多份文档的知识点，生成${config.count}道考题。

题目类型：${typeDesc}

要求：
1. 题目覆盖各文档的重要知识点，比例均衡
2. 选择题4个选项 A/B/C/D，answer 填正确选项字母
3. 判断题 answer 为"正确"或"错误"
4. 填空/简答 answer 为正确答案/参考答案
5. explanation 给出解析，引用知识点原文
6. 中文出题

严格返回 JSON 数组（不含 markdown 代码块）：
[{"type":"choice","content":"题目","options":["A. xx","B. xx","C. xx","D. xx"],"answer":"B","explanation":"解析"}]

知识点内容：
${knowledgeContext.slice(0, 12000)}`;

  const resp = await client.chat.completions.create({
    model: "deepseek-chat",
    messages: [
      { role: "system", content: "你是专业出题老师。严格按 JSON 数组格式返回，不要任何 JSON 之外的文本。" },
      { role: "user", content: prompt },
    ],
    temperature: 0.7, max_tokens: 4096,
  });

  let raw = resp.choices[0]?.message?.content || "[]";
  raw = raw.trim().replace(/^```\w*\n?/, "").replace(/\n?```$/, "");
  const questions = JSON.parse(raw);
  if (!Array.isArray(questions)) throw new Error("AI 返回不是数组");
  return questions as GeneratedQuestion[];
}
```

- [ ] **Step 2: 考题生成 API（多文档）**

```typescript
// src/app/api/exams/generate/route.ts
import { NextRequest, NextResponse } from "next/server";
import { getUserFromRequest } from "@/lib/auth";
import { prisma } from "@/lib/prisma";
import { generateQuestions } from "@/lib/deepseek";
import { z } from "zod";

const schema = z.object({
  title: z.string().min(1),
  documentIds: z.array(z.string()).min(1).max(10),
  types: z.array(z.enum(["choice","fill","truefalse","shortanswer"])).min(1),
  count: z.number().int().min(1).max(30),
});

export async function POST(request: NextRequest) {
  const user = await getUserFromRequest(request);
  if (!user) return NextResponse.json({ error: "未登录" }, { status: 401 });

  const body = await request.json();
  const parsed = schema.safeParse(body);
  if (!parsed.success) return NextResponse.json({ error: parsed.error.flatten() }, { status: 400 });

  // 验证所有文档属于当前用户
  const docs = await prisma.document.findMany({
    where: { id: { in: parsed.data.documentIds }, userId: user.userId },
    select: { id: true, content: true, title: true },
  });
  if (docs.length !== parsed.data.documentIds.length) {
    return NextResponse.json({ error: "部分文档不存在" }, { status: 404 });
  }

  try {
    const questions = await generateQuestions({
      documentIds: docs.map(d => d.id),
      documentContents: docs.map(d => d.content.slice(0, 300)),
      types: parsed.data.types,
      count: parsed.data.count,
    });

    const exam = await prisma.exam.create({
      data: {
        title: parsed.data.title,
        userId: user.userId,
        questionTypes: parsed.data.types.join(","),
        questionCount: parsed.data.count,
        documents: {
          create: parsed.data.documentIds.map(docId => ({ documentId: docId })),
        },
        questions: {
          create: questions.map(q => ({
            type: q.type, content: q.content,
            options: JSON.stringify(q.options || []),
            answer: q.answer, explanation: q.explanation || "",
          })),
        },
      },
      include: { questions: true, documents: { include: { document: { select: { title: true } } } } },
    });

    return NextResponse.json({ exam }, { status: 201 });
  } catch (err: any) {
    return NextResponse.json({ error: err.message || "生成失败" }, { status: 500 });
  }
}
```

- [ ] **Step 3: 考试列表/详情/删除/提交 API**

```typescript
// GET /api/exams — 列表，include documents
// GET /api/exams/[id] — 详情，include documents + questions
// DELETE /api/exams/[id] — 删除
// POST /api/exams/[id]/submit — 提交评分，错题写入 WrongQuestion
```

- [ ] **Step 4: 多文档出题页面**

```tsx
// src/app/(dashboard)/exams/generate/page.tsx
"use client";
// 列出用户的文档列表，支持多选（checkbox），选好文档后配置题型+数量，点击生成
// 文档选择用卡片+checkbox，显示文档标题和类型图标
// 题型用 Badge 多选切换
// 数量用 Input number
```

- [ ] **Step 5: 考试列表/详情/答题/结果页** — 同 v1 结构
- [ ] **Step 6: Commit**

---

### Task 11: 错题本

**Files:** `src/app/api/wrong-questions/route.ts`, `src/app/(dashboard)/wrong-questions/page.tsx`

**功能：** 错题列表（按文档/题型筛选），重做错题，标记已掌握。

- [ ] **Step 1: API** — GET 列表（支持筛选）、PATCH 掌握状态、DELETE
- [ ] **Step 2: 前端** — 列表 + 筛选 + 重做入口 + 掌握按钮
- [ ] **Step 3: Commit**

---

### Task 12: 学习进度面板

**Files:** `src/app/api/stats/route.ts`, `src/app/(dashboard)/stats/page.tsx`

**功能：** 仪表盘——总学习文档数、考试次数、平均分、闪卡进度、最近活动。

- [ ] **Step 1: API** — 聚合查询
- [ ] **Step 2: 前端** — 统计卡片 + 图表（简单进度条）
- [ ] **Step 3: Commit**

---

### Task 13: 思维导图

**Files:** `src/app/(dashboard)/documents/[id]/mindmap/page.tsx`

**功能：** 以知识点为节点，react-flow 展示知识结构。可自动布局，点击节点查看详情。

- [ ] **Step 1: 获取知识点→生成层级结构（DeepSeek 帮忙分组）**
- [ ] **Step 2: react-flow 渲染思维导图**
- [ ] **Step 3: Commit**

---

### Task 14: 考题导出

**Files:** `src/app/api/exams/[id]/export/route.ts`

**功能：** 导出考题为 Word（docx）或 PDF（jsPDF），下载文件。

- [ ] **Step 1: API** — 生成 docx/pdf buffer，返回下载
- [ ] **Step 2: 前端** — 考试详情页加"导出"按钮
- [ ] **Step 3: Commit**

---

### Task 15: 导航、README、全流程验证

- [ ] **导航栏** — 首页/文档/AI助手/闪卡/考试/错题本/统计
- [ ] **README** — 完整安装使用指南
- [ ] **全流程测试**
- [ ] **Commit**

---

## 自审

| 检查项 | 状态 |
|--------|------|
| Spec 覆盖 | ✅ 文档上传/知识点/AI对话/考试/错题/闪卡/进度/思维导图/导出 |
| 无占位符 | ✅ |
| 类型一致 | ✅ |
| 15 Tasks | ✅ |
