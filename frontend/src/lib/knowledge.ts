import OpenAI from "openai";
import { prisma } from "./prisma";

const client = new OpenAI({
  apiKey: process.env.DEEPSEEK_API_KEY || "sk-placeholder",
  baseURL: "https://api.deepseek.com",
});

export async function extractKnowledgePoints(
  documentId: string,
  content: string
): Promise<{ title: string; content: string }[]> {
  const prompt = `请从以下文档中提取关键知识点。严格返回 JSON 数组：
[{"title":"知识点标题","content":"详细说明内容（100-300字）"}]

要求：
1. 每个知识点独立完整，便于学习记忆
2. 覆盖文档中的核心概念、原理、方法
3. 提取 8-15 个关键知识点
4. title 简洁明了（5-20字）
5. content 包含要点解释，可以包含分点说明

文档内容：
${content.slice(0, 15000)}`;

  const resp = await client.chat.completions.create({
    model: "deepseek-chat",
    messages: [
      {
        role: "system",
        content: "你是知识管理专家。严格按 JSON 数组返回，不要任何 JSON 之外的文本。",
      },
      { role: "user", content: prompt },
    ],
    temperature: 0.3,
    max_tokens: 4096,
  });

  const raw = resp.choices[0]?.message?.content || "[]";
  const json = raw.trim().replace(/^```\w*\n?/, "").replace(/\n?```$/, "");

  const points = JSON.parse(json);
  if (!Array.isArray(points)) throw new Error("AI 返回格式不是数组");
  return points;
}

export async function saveKnowledgePoints(
  documentId: string,
  points: { title: string; content: string }[]
) {
  await prisma.knowledgePoint.deleteMany({ where: { documentId } });

  return prisma.knowledgePoint.createMany({
    data: points.map((p, i) => ({
      documentId,
      title: p.title,
      content: p.content,
      order: i,
    })),
  });
}
