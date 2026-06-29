import { pipeline } from "@huggingface/transformers";

let extractor: any = null;

async function getExtractor() {
  if (!extractor) {
    extractor = await pipeline(
      "feature-extraction",
      "Xenova/all-MiniLM-L6-v2"
    );
  }
  return extractor;
}

export async function generateEmbedding(text: string): Promise<number[]> {
  const model = await getExtractor();
  const result = await model(text, { pooling: "mean", normalize: true });
  return Array.from(result.data as Float32Array);
}
