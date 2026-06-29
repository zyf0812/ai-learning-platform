import OpenAI from "openai";
import { searchChunks } from "./rag";
import { prisma } from "./prisma";

const client = new OpenAI({
  apiKey: process.env.DEEPSEEK_API_KEY || "sk-placeholder",
  baseURL: "https://api.deepseek.com",
});

export async function chatReply(
  userId: string,
  conversationId: string,
  question: string
): Promise<string> {
  const conversation = await prisma.conversation.findUnique({
    where: { id: conversationId },
    include: {
      messages: { orderBy: { createdAt: "asc" }, take: 10 },
    },
  });
  if (!conversation || conversation.userId !== userId)
    throw new Error("会话不存在");

  // RAG 检索
  const relevant = await searchChunks(question, 4);

  const systemPrompt = `你是 AI 学习助手。基于以下知识内容回答用户问题。
如果知识内容不足以回答，请诚实告知，不要编造。

知识内容：
${relevant.join("\n---\n")}

历史对话：
${conversation.messages
  .map((m) => `${m.role}: ${m.content}`)
  .join("\n")}`;

  const resp = await client.chat.completions.create({
    model: "deepseek-chat",
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: question },
    ],
    temperature: 0.7,
    max_tokens: 2048,
  });

  const answer =
    resp.choices[0]?.message?.content || "抱歉，我无法回答这个问题。";

  // 保存消息
  await prisma.chatMessage.createMany({
    data: [
      { conversationId, role: "user", content: question },
      { conversationId, role: "assistant", content: answer },
    ],
  });

  return answer;
}
