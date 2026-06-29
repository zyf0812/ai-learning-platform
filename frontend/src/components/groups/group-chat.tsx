"use client";

import { useState, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { api } from "@/lib/api";

interface GroupChatProps {
  groupId: string;
  adminUserId: string;
  messages: any[];
  onSend: () => void;
  onUpload: (file: File) => void;
  onSaveFile: (fileName: string, fileUrl: string) => void;
}

export default function GroupChat({ groupId, adminUserId, messages, onSend, onUpload, onSaveFile }: GroupChatProps) {
  const [chatMsg, setChatMsg] = useState("");
  const fileRef = useRef<HTMLInputElement>(null);
  const currentUserId = typeof window !== "undefined"
    ? JSON.parse(localStorage.getItem("user") || "{}").id
    : null;

  const send = async () => {
    if (!chatMsg.trim()) return;
    await api.groups.chat.send(groupId, chatMsg);
    setChatMsg("");
    onSend();
  };

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    onUpload(file);
  };

  return (
    <div className="space-y-3">
      <Card className="p-4 h-[50vh] overflow-y-auto space-y-3">
        {messages.length === 0 ? (
          <p className="text-center text-muted-foreground py-8">暂无消息</p>
        ) : (
          messages.slice().reverse().map((m: any) => (
            <div key={m.id} className="flex gap-2">
              <div className="w-7 h-7 rounded-full bg-primary/10 flex items-center justify-center text-xs font-bold text-primary shrink-0 mt-0.5">
                {m.username?.charAt(0).toUpperCase()}
              </div>
              <div>
                <p className="text-xs text-muted-foreground">
                  {m.username} <span className="text-[10px]">{new Date(m.createdAt).toLocaleTimeString()}</span>
                </p>
                <div className="bg-muted rounded-xl rounded-tl-sm px-3 py-2 text-sm">
                  {m.fileName ? (
                    <div>
                      <a href={m.fileUrl} download className="text-primary hover:underline">📎 {m.fileName}</a>
                      <Button variant="link" size="sm" className="text-xs ml-2" onClick={() => onSaveFile(m.fileName, m.fileUrl)}>
                        存到我的文档
                      </Button>
                    </div>
                  ) : m.content}
                </div>
              </div>
            </div>
          ))
        )}
      </Card>
      <div className="flex gap-2">
        {currentUserId === adminUserId && (
          <>
            <input type="file" ref={fileRef} className="hidden" onChange={handleUpload} />
            <Button variant="outline" size="sm" onClick={() => fileRef.current?.click()}>📎</Button>
          </>
        )}
        <Input placeholder="输入消息..." value={chatMsg} onChange={e => setChatMsg(e.target.value)} onKeyDown={e => e.key === "Enter" && send()} />
        <Button size="sm" onClick={send}>发送</Button>
      </div>
    </div>
  );
}
