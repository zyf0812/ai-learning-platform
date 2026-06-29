import pdfParse from "pdf-parse";
import mammoth from "mammoth";
import MarkdownIt from "markdown-it";

const md = new MarkdownIt();

export async function parseDocument(
  buffer: Buffer,
  filename: string
): Promise<string> {
  const ext = filename.split(".").pop()?.toLowerCase();

  switch (ext) {
    case "pdf": {
      const data = await pdfParse(buffer);
      return data.text;
    }

    case "docx":
    case "doc": {
      const result = await mammoth.extractRawText({ buffer });
      return result.value;
    }

    case "pptx":
    case "ppt": {
      // pptx2json 的 parseBuffer 返回幻灯片数组
      const pptx2json = await import("pptx2json");
      const raw = await pptx2json.parseBuffer(buffer);
      return extractPptxText(raw as any);
    }

    case "md":
    case "markdown":
      return buffer.toString("utf-8");

    case "txt":
      return buffer.toString("utf-8");

    default:
      throw new Error(`不支持的文件格式: .${ext}`);
  }
}

function extractPptxText(raw: any): string {
  const texts: string[] = [];

  // pptx2json 返回格式: { slides: [{ texts: [{ text: "..." }] }] }
  if (raw && raw.slides) {
    for (const slide of raw.slides) {
      if (slide.texts) {
        for (const t of slide.texts) {
          if (t.text) texts.push(t.text);
        }
      }
    }
  }

  // 备用：如果是不同格式，尝试递归提取所有 text 字段
  if (texts.length === 0) {
    extractTextFields(raw, texts);
  }

  return texts.join("\n");
}

function extractTextFields(obj: any, out: string[]) {
  if (!obj || typeof obj !== "object") return;
  if (Array.isArray(obj)) {
    for (const item of obj) extractTextFields(item, out);
    return;
  }
  for (const [key, val] of Object.entries(obj)) {
    if (key === "text" && typeof val === "string" && val.trim()) {
      out.push(val.trim());
    } else if (typeof val === "object") {
      extractTextFields(val, out);
    }
  }
}

export function getFileType(filename: string): string {
  const ext = filename.split(".").pop()?.toLowerCase() || "";
  const map: Record<string, string> = {
    pdf: "pdf",
    docx: "docx",
    doc: "doc",
    pptx: "pptx",
    ppt: "ppt",
    md: "md",
    markdown: "md",
    txt: "txt",
  };
  return map[ext] || ext;
}
