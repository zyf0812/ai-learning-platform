"use client";

import { Button } from "@/components/ui/button";

export default function ChatWelcomePage() {
  return (
    <div className="flex items-center justify-center h-full">
      <div className="text-center">
        <p className="text-4xl mb-4">💬</p>
        <h2 className="text-lg font-semibold mb-2">AI 学习助手</h2>
        <p className="text-sm text-muted-foreground mb-4">基于文档内容的智能问答</p>
        <p className="text-xs text-muted-foreground">点击左侧 + 新建对话开始</p>
      </div>
    </div>
  );
}
