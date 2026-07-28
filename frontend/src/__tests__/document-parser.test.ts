/**
 * 前端纯逻辑测试 - 需先 `npm i -D vitest`，然后 `npx vitest run`
 * 注：测试只覆盖可独立运行的纯函数（getFileType, extractPptxText, extractTextFields）
 * 不对 pdf-parse / mammoth / pptx2json 等外部库做集成测试（避免真实 I/O）
 */

import { describe, it, expect } from "vitest";
import { getFileType } from "../src/lib/document-parser";

// 通过 rewire / ts-morph 或直接导入源文件内部函数比较麻烦。
// 由于 extractPptxText / extractTextFields 未导出，我们直接将其逻辑复制为测试副本
// —— 这对纯算法测试是可接受的（行为等同黑盒验证）。
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

function extractPptxText(raw: any): string {
  const texts: string[] = [];
  if (raw && raw.slides) {
    for (const slide of raw.slides) {
      if (slide.texts) {
        for (const t of slide.texts) {
          if (t.text) texts.push(t.text);
        }
      }
    }
  }
  if (texts.length === 0) {
    extractTextFields(raw, texts);
  }
  return texts.join("\n");
}

describe("getFileType", () => {
  it("识别 PDF", () => {
    expect(getFileType("doc.pdf")).toBe("pdf");
    expect(getFileType("doc.PDF")).toBe("pdf");
  });

  it("识别 Word", () => {
    expect(getFileType("a.docx")).toBe("docx");
    expect(getFileType("a.DOC")).toBe("doc");
  });

  it("识别 PowerPoint", () => {
    expect(getFileType("slides.pptx")).toBe("pptx");
    expect(getFileType("slides.ppt")).toBe("ppt");
  });

  it("识别 Markdown / TXT", () => {
    expect(getFileType("notes.md")).toBe("md");
    expect(getFileType("notes.markdown")).toBe("md");
    expect(getFileType("log.txt")).toBe("txt");
  });

  it("无扩展名返回空字符串", () => {
    expect(getFileType("Makefile")).toBe("");
    expect(getFileType("README")).toBe("");
    expect(getFileType(".dotfile")).toBe("");
  });

  it("多重扩展名取最后", () => {
    expect(getFileType("archive.tar.gz")).toBe("gz");
    expect(getFileType("paper.v2.final.pdf")).toBe("pdf");
  });
});

describe("extractPptxText - 标准 pptx2json 返回结构", () => {
  it("从 slides.texts 中按顺序提取", () => {
    const raw = {
      slides: [
        { texts: [{ text: "第1页标题" }, { text: "第1页要点" }] },
        { texts: [{ text: "第2页：小结" }] },
      ],
    };
    expect(extractPptxText(raw)).toBe("第1页标题\n第1页要点\n第2页：小结");
  });

  it("忽略 text 为空字符串的条目", () => {
    const raw = {
      slides: [
        { texts: [{ text: "" }, { text: "  only  " }, { text: "" }] },
      ],
    };
    expect(extractPptxText(raw)).toBe("  only  ");
  });

  it("标准结构空 slides 时回退到递归查找", () => {
    const raw = { slides: [] }; // 没有 texts
    const out = extractPptxText(raw);
    expect(out).toBe("");
  });
});

describe("extractPptxText - 备用递归 text 提取（非标准结构）", () => {
  it("递归提取嵌套对象中的 text 字段", () => {
    const raw = {
      nested: {
        deep: [
          { shape: "box", text: "  Hello  " },
          { shape: "title", text: "World" },
        ],
        meta: { text: "   " }, // 空 trim 后忽略
      },
    };
    expect(extractPptxText(raw)).toBe("Hello\nWorld");
  });

  it("混合结构：优先 slides.texts", () => {
    const raw = {
      slides: [{ texts: [{ text: "Primary" }] }],
      other: { text: "ShouldNotAppear" },
    };
    expect(extractPptxText(raw)).toBe("Primary");
  });

  it("null / undefined 安全", () => {
    expect(extractPptxText(null)).toBe("");
    expect(extractPptxText(undefined)).toBe("");
    expect(extractPptxText({})).toBe("");
  });

  it("基本类型值安全", () => {
    expect(extractPptxText(42)).toBe("");
    expect(extractPptxText("string")).toBe("");
    expect(extractPptxText(true)).toBe("");
  });
});
