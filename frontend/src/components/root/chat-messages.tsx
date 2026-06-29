"use client";

import { useState, useEffect } from "react";
import { api } from "@/lib/api";

export default function ChatMessages({ convId }: { convId: string }) {
  const [msgs, setMsgs] = useState<any[]>([]);

  useEffect(() => {
    api.conversations.get(convId).then(d => setMsgs(d.conversation?.messages || []));
  }, [convId]);

  return (
    <div className="space-y-1 max-h-40 overflow-y-auto">
      {msgs.map(m => (
        <div key={m.id} className={`p-1.5 rounded text-xs ${m.role === "user" ? "bg-blue-50" : "bg-gray-100"}`}>
          <span className="font-medium">{m.role === "user" ? "用户" : "AI"}: </span>
          {m.content}
        </div>
      ))}
    </div>
  );
}
