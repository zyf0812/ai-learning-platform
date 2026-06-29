import { prisma } from "./prisma";
import { generateEmbedding } from "./embedding";

/** 分块：每 512 字符一块，重叠 64 字符，尽量在句号/换行处断 */
export function chunkDocument(
  content: string,
  chunkSize = 512,
  overlap = 64
): string[] {
  const chunks: string[] = [];
  let start = 0;
  while (start < content.length) {
    let end = start + chunkSize;
    if (end < content.length) {
      const breakChars = ["\n\n", "\n", "。", "；", ".", ";"];
      for (const ch of breakChars) {
        const pos = content.lastIndexOf(ch, end);
        if (pos > start + chunkSize / 2) {
          end = pos + ch.length;
          break;
        }
      }
    }
    const chunk = content.slice(start, Math.min(end, content.length)).trim();
    if (chunk.length > 10) chunks.push(chunk);
    start += chunkSize - overlap;
  }
  return chunks;
}

/** 索引文档到向量库 */
export async function indexDocument(
  documentId: string,
  content: string
): Promise<number> {
  const chunks = chunkDocument(content);

  // 删除旧 chunks
  await prisma.$executeRawUnsafe(
    `DELETE FROM "DocumentChunk" WHERE "documentId" = $1`,
    [documentId]
  );

  let indexed = 0;
  for (let i = 0; i < chunks.length; i++) {
    try {
      const embedding = await generateEmbedding(chunks[i]);
      const id = `${documentId}_chunk_${i}`;
      await prisma.$executeRawUnsafe(
        `INSERT INTO "DocumentChunk" (id, "documentId", "chunkIndex", content, embedding)
         VALUES ($1, $2, $3, $4, $5::vector)`,
        [id, documentId, i, chunks[i], `[${embedding.join(",")}]`]
      );
      indexed++;
    } catch (err) {
      console.error(`Failed to index chunk ${i} for doc ${documentId}:`, err);
    }
  }

  return indexed;
}

/** 全局向量搜索（跨文档） */
export async function searchChunks(
  query: string,
  topK = 5
): Promise<string[]> {
  const embedding = await generateEmbedding(query);
  const vecStr = `[${embedding.join(",")}]`;

  const rows = await prisma.$queryRawUnsafe<
    { content: string }[]
  >(
    `SELECT content
     FROM "DocumentChunk"
     ORDER BY embedding <=> $1::vector
     LIMIT $2`,
    [vecStr, topK]
  );

  return rows.map((r) => r.content);
}

/** 在指定文档内向量搜索 */
export async function searchByDocumentId(
  documentId: string,
  query: string,
  topK = 5
): Promise<string[]> {
  const embedding = await generateEmbedding(query);
  const vecStr = `[${embedding.join(",")}]`;

  const rows = await prisma.$queryRawUnsafe<
    { content: string }[]
  >(
    `SELECT content
     FROM "DocumentChunk"
     WHERE "documentId" = $1
     ORDER BY embedding <=> $2::vector
     LIMIT $3`,
    [documentId, vecStr, topK]
  );

  return rows.map((r) => r.content);
}
