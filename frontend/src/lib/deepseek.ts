import OpenAI from "openai";
import { searchChunks } from "./rag";

const client = new OpenAI({
  apiKey: process.env.DEEPSEEK_API_KEY || "sk-placeholder",
  baseURL: "https://api.deepseek.com",
});

export interface GenerateConfig {
  documentIds: string[];
  documentContents: string[];
  types: string[];
  count: number;
}

export interface GeneratedQuestion {
  type: string;
  content: string;
  options: string[];
  answer: string;
  explanation: string;
}

export async function generateQuestions(
  config: GenerateConfig
): Promise<GeneratedQuestion[]> {
  const typeMap: Record<string, string> = {
    choice: "单选题（4个选项A/B/C/D）",
    fill: "填空题",
    truefalse: "判断题（正确/错误）",
    shortanswer: "简答题",
  };
  const typeDesc = config.types.map((t) => typeMap[t] || t).join("、");

  // 跨文档 RAG 检索
  const query = config.documentContents.join("\n").slice(0, 512);
  let knowledgeContext = "";
  try {
    const chunks = await searchChunks(query, 8);
    knowledgeContext = chunks.join("\n---\n");
  } catch {
    knowledgeContext = config.documentContents
      .map((c) => c.slice(0, 2000))
      .join("\n---\n");
  }

  const prompt = `作为专业出题老师，基于以下多份文档知识点生成${config.count}道考题。

题目类型：${typeDesc}

要求：
1. 覆盖各文档的重要知识点，比例均衡
2. 选择题4个选项 A/B/C/D，answer 填正确选项字母
3. 判断题 answer 为"正确"或"错误"
4. 填空/简答 answer 为正确答案/参考答案
5. explanation 给出解析，引用知识点原文
6. 中文出题

严格返回 JSON 数组（不含 markdown 代码块标记）：
[{"type":"choice","content":"题目","options":["A. xx","B. xx","C. xx","D. xx"],"answer":"B","explanation":"解析"}]

知识点：
${knowledgeContext.slice(0, 12000)}`;

  const resp = await client.chat.completions.create({
    model: "deepseek-chat",
    messages: [
      {
        role: "system",
        content: "你是专业出题老师。严格按 JSON 数组返回，不含其他文本。",
      },
      { role: "user", content: prompt },
    ],
    temperature: 0.7,
    max_tokens: 4096,
  });

  let raw = resp.choices[0]?.message?.content || "[]";
  raw = raw.trim().replace(/^```\w*\n?/, "").replace(/\n?```$/, "");
  const questions = JSON.parse(raw);
  if (!Array.isArray(questions)) throw new Error("AI 返回不是数组");
  return questions as GeneratedQuestion[];
}
