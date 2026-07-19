-- PostgreSQL 初始化脚本
-- psql -U postgres -c "CREATE DATABASE doc_exam_platform;"
-- psql -U postgres -d doc_exam_platform -f sql/init.sql

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS "User" (
    id VARCHAR(20) PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL,
    password VARCHAR(200) NOT NULL,
    role VARCHAR(20) DEFAULT 'user',
    status VARCHAR(20) DEFAULT 'active',
    "superviseCode" VARCHAR(50),
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "Document" (
    id VARCHAR(20) PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    "originalFilename" VARCHAR(500),
    "fileType" VARCHAR(20),
    content TEXT,
    "userId" VARCHAR(20) REFERENCES "User"(id),
    "isQuestionBank" BOOLEAN DEFAULT FALSE,
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "DocumentChunk" (
    id VARCHAR(50) PRIMARY KEY,
    "documentId" VARCHAR(20) REFERENCES "Document"(id) ON DELETE CASCADE,
    "chunkIndex" INTEGER,
    content TEXT,
    embedding vector(1024),
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_chunks_embedding ON "DocumentChunk" USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE TABLE IF NOT EXISTS "KnowledgePoint" (
    id VARCHAR(20) PRIMARY KEY,
    "documentId" VARCHAR(20) REFERENCES "Document"(id) ON DELETE CASCADE,
    title VARCHAR(200),
    content TEXT,
    "order" INTEGER DEFAULT 0,
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "FlashCard" (
    id VARCHAR(20) PRIMARY KEY,
    "knowledgePointId" VARCHAR(20) REFERENCES "KnowledgePoint"(id) ON DELETE CASCADE,
    front TEXT,
    back TEXT,
    "easeFactor" DOUBLE PRECISION DEFAULT 2.5,
    "interval" INTEGER DEFAULT 0,
    "repetitions" INTEGER DEFAULT 0,
    "nextReview" TIMESTAMP DEFAULT NOW(),
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "Exam" (
    id VARCHAR(20) PRIMARY KEY,
    title VARCHAR(200),
    "userId" VARCHAR(20) REFERENCES "User"(id),
    "questionTypes" VARCHAR(100) DEFAULT 'choice',
    "questionCount" INTEGER DEFAULT 5,
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "ExamDocument" (
    id VARCHAR(20) PRIMARY KEY,
    "examId" VARCHAR(20) REFERENCES "Exam"(id) ON DELETE CASCADE,
    "documentId" VARCHAR(20) REFERENCES "Document"(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS "Question" (
    id VARCHAR(20) PRIMARY KEY,
    "examId" VARCHAR(20) REFERENCES "Exam"(id) ON DELETE CASCADE,
    type VARCHAR(20),
    content TEXT,
    options TEXT DEFAULT '[]',
    answer VARCHAR(500),
    explanation TEXT DEFAULT '',
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "ExamAttempt" (
    id VARCHAR(20) PRIMARY KEY,
    "examId" VARCHAR(20) REFERENCES "Exam"(id) ON DELETE CASCADE,
    "userId" VARCHAR(20) REFERENCES "User"(id),
    answers TEXT DEFAULT '[]',
    score INTEGER DEFAULT -1,
    total INTEGER DEFAULT 0,
    "startedAt" TIMESTAMP DEFAULT NOW(),
    "completedAt" TIMESTAMP
);

CREATE TABLE IF NOT EXISTS "Conversation" (
    id VARCHAR(20) PRIMARY KEY,
    title VARCHAR(200),
    "documentId" VARCHAR(20),
    "userId" VARCHAR(20) REFERENCES "User"(id),
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "ChatMessage" (
    id VARCHAR(20) PRIMARY KEY,
    "conversationId" VARCHAR(20) REFERENCES "Conversation"(id) ON DELETE CASCADE,
    role VARCHAR(20),
    content TEXT,
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "WrongQuestion" (
    id VARCHAR(50) PRIMARY KEY,
    "userId" VARCHAR(20) REFERENCES "User"(id),
    "questionId" VARCHAR(20),
    "examId" VARCHAR(20),
    "userAnswer" TEXT,
    "reviewCount" INTEGER DEFAULT 0,
    mastered BOOLEAN DEFAULT FALSE,
    "createdAt" TIMESTAMP DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS "StudyStats" (
    id VARCHAR(20) PRIMARY KEY,
    "userId" VARCHAR(20) REFERENCES "User"(id),
    "documentId" VARCHAR(20),
    "studyTime" INTEGER DEFAULT 0,
    "examCount" INTEGER DEFAULT 0,
    "avgScore" DOUBLE PRECISION DEFAULT 0,
    "updatedAt" TIMESTAMP DEFAULT NOW()
);
