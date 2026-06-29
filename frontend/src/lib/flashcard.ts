import { prisma } from "./prisma";

/** SM-2 间隔重复算法
 *  quality: 0-5 用户自评（0=完全忘记, 5=完全正确）
 */
export function sm2(
  quality: number,
  prevEase: number,
  prevInterval: number,
  prevReps: number
) {
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

/** 获取今日待复习闪卡 */
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

/** 获取所有闪卡统计 */
export async function getFlashcardStats(userId: string) {
  const total = await prisma.flashCard.count({
    where: { knowledgePoint: { document: { userId } } },
  });
  const due = await prisma.flashCard.count({
    where: {
      knowledgePoint: { document: { userId } },
      nextReview: { lte: new Date() },
    },
  });
  return { total, due };
}

/** 从知识点批量生成闪卡 */
export async function generateFlashcards(
  knowledgePointId: string,
  title: string,
  content: string
): Promise<{ front: string; back: string }[]> {
  const OpenAI = (await import("openai")).default;
  const client = new OpenAI({
    apiKey: process.env.DEEPSEEK_API_KEY || "sk-placeholder",
    baseURL: "https://api.deepseek.com",
  });

  const prompt = `根据以下知识点生成 2-4 张闪卡，用于间隔重复记忆。严格返回 JSON 数组：
[{"front":"问题/提示（正面）","back":"答案/解释（背面）"}]

要求：
- front 是简洁的问题或关键词提示
- back 是详细的答案或解释
- 适合闪卡翻转记忆

知识点：${title}
内容：${content}`;

  const resp = await client.chat.completions.create({
    model: "deepseek-chat",
    messages: [
      {
        role: "system",
        content: "你是闪卡设计师。严格按 JSON 数组返回。",
      },
      { role: "user", content: prompt },
    ],
    temperature: 0.5,
    max_tokens: 2048,
  });

  const raw = resp.choices[0]?.message?.content || "[]";
  const json = raw.trim().replace(/^```\w*\n?/, "").replace(/\n?```$/, "");
  return JSON.parse(json);
}
