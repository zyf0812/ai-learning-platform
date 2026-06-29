"use client";

import { useState, useEffect, useRef, useCallback } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Skeleton } from "@/components/ui/skeleton";
import { api, request } from "@/lib/api";

interface Message {
  id: string;
  role: string;
  content: string;
}

interface Conv {
  id: string;
  title: string;
}

interface Doc {
  id: string;
  title: string;
  fileType: string;
}

const PAGE_SIZE = 20;

export default function ChatDetailPage() {
  const params = useParams();
  const router = useRouter();
  const [conv, setConv] = useState<Conv | null>(null);
  const [messages, setMessages] = useState<Message[]>([]);
  const [question, setQuestion] = useState("");
  const [sending, setSending] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [docs, setDocs] = useState<Doc[]>([]);
  const [attachDoc, setAttachDoc] = useState<Doc | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  const loadMessages = async (offset = 0) => {
    try {
      const d = await api.conversations.messages(params.id as string, PAGE_SIZE, offset);
      const msgs = d.messages || [];
      if (offset === 0) {
        setMessages(msgs);
      } else {
        setMessages(prev => [...msgs, ...prev]);
      }
      setHasMore(d.hasMore);
      return d;
    } catch {
      return { messages: [], hasMore: false, total: 0 };
    }
  };

  useEffect(() => {
    api.conversations.get(params.id as string)
      .then(d => {
        if (d.error) { router.push("/chat"); return; }
        setConv(d.conversation);
        loadMessages(0);
      })
      .catch(() => router.push("/chat"))
      .finally(() => setLoading(false));

    api.documents.list().then(d => {
      setDocs(d.documents || []);
    }).catch(() => {});
  }, [params.id]);

  // 新消息时滚到底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

  // 滚动到顶部时加载更多
  const handleScroll = useCallback(() => {
    const el = scrollRef.current;
    if (!el || !hasMore || loadingMore) return;
    if (el.scrollTop < 100) {
      setLoadingMore(true);
      const currentCount = messages.length;
      loadMessages(currentCount).finally(() => setLoadingMore(false));
    }
  }, [hasMore, loadingMore, messages.length, params.id]);

  const send = async () => {
    if (!question.trim() || sending) return;
    setSending(true);
    const q = question.trim();
    setQuestion("");
    const fullQuestion = attachDoc ? `[参考文档: ${attachDoc.title} (ID: ${attachDoc.id})]\n${q}` : q;

    setMessages(prev => [...prev, { id: "tmp-u-" + Date.now(), role: "user", content: q }, { id: "tmp-a-" + Date.now(), role: "assistant", content: "typing" }]);

    try {
      const body: any = { question: fullQuestion };
      if (attachDoc) body.docId = attachDoc.id;
      const data = await request(`/api/conversations/${params.id}`, { method: "POST", body: JSON.stringify(body) });
      setAttachDoc(null);
      setMessages(prev => [...prev.slice(0, -1), { id: "real-a-" + Date.now(), role: "assistant", content: data.answer }]);
    } catch (err: any) {
      setMessages(prev => [...prev.slice(0, -2), { id: "err-" + Date.now(), role: "assistant", content: `❌ ${err.message}` }]);
    } finally { setSending(false); }
  };

  if (loading) return (
    <div className="flex items-center justify-center h-full">
      <Skeleton className="h-8 w-32" />
    </div>
  );
  if (!conv) return null;

  return (
    <div className="flex flex-col h-full">
      {/* 消息列表 - 可滚动 + 懒加载 */}
      <div className="flex-1 overflow-y-auto min-h-0 px-1 pb-4" ref={scrollRef} onScroll={handleScroll}>
        <div className="max-w-3xl mx-auto space-y-6 py-4">
          {/* 加载更多 */}
          {loadingMore && (
            <div className="text-center text-muted-foreground py-2">
              <svg className="animate-spin w-4 h-4 mx-auto" viewBox="0 0 24 24"><circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" /><path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" /></svg>
            </div>
          )}
          {hasMore && !loadingMore && (
            <p className="text-center text-xs text-muted-foreground cursor-pointer hover:text-foreground" onClick={() => handleScroll()}>
              向上滚动加载历史消息
            </p>
          )}

          {/* 空状态 */}
          {messages.length === 0 && !loadingMore && (
            <div className="text-center py-16">
              <div className="w-16 h-16 rounded-2xl bg-gradient-to-br from-blue-50 to-blue-100 dark:from-blue-950 dark:to-blue-900 flex items-center justify-center mx-auto mb-4">
                <svg className="w-8 h-8 text-blue-500" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M8.625 12a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 01-2.555-.337A5.972 5.972 0 015.41 20.97a5.969 5.969 0 01-.474-.065 4.48 4.48 0 00.978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25z" />
                </svg>
              </div>
              <h3 className="text-lg font-semibold text-foreground mb-2">开始对话</h3>
              <p className="text-sm text-muted-foreground">向 AI 助手提问，获取基于文档知识的回答</p>
            </div>
          )}

          {messages.map((msg, idx) => {
            const isUser = msg.role === "user";
            const isTyping = msg.content === "typing";
            const showAvatar = idx === 0 || messages[idx - 1]?.role !== msg.role;

            return (
              <div key={msg.id} className={`flex gap-3 ${isUser ? "flex-row-reverse" : ""}`}>
                <div className={`shrink-0 w-8 h-8 rounded-full flex items-center justify-center text-xs font-medium ${
                  isUser ? "bg-blue-500 text-white" : "bg-gradient-to-br from-violet-400 to-purple-500 text-white"
                } ${showAvatar ? "opacity-100" : "opacity-0"}`}>
                  {isUser ? "你" : "AI"}
                </div>

                <div className={`max-w-[75%] ${isUser ? "items-end" : "items-start"}`}>
                  {showAvatar && (
                    <p className={`text-xs mb-1 px-1 ${isUser ? "text-right text-blue-500" : "text-violet-500"}`}>
                      {isUser ? "你" : "AI 助手"}
                    </p>
                  )}
                  <div className={`rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${
                    isUser ? "bg-blue-500 text-white rounded-tr-md" : "bg-card border rounded-tl-md shadow-sm"
                  }`}>
                    {isTyping ? (
                      <div className="flex gap-1 py-1">
                        <span className="w-2 h-2 bg-muted rounded-full animate-bounce" style={{animationDelay: "0ms"}} />
                        <span className="w-2 h-2 bg-muted rounded-full animate-bounce" style={{animationDelay: "150ms"}} />
                        <span className="w-2 h-2 bg-muted rounded-full animate-bounce" style={{animationDelay: "300ms"}} />
                      </div>
                    ) : (
                      <div className={`prose prose-sm max-w-none break-words ${isUser ? "prose-invert" : "prose-gray"}`}>
                        <ReactMarkdown remarkPlugins={[remarkGfm]}
                          components={{
                            code({ className, children, ...props }: any) {
                              const isBlock = className?.startsWith("language-");
                              if (isBlock) {
                                return <pre className="bg-gray-900 text-gray-100 rounded-lg p-3 overflow-x-auto text-xs my-2"><code className={className} {...props}>{children}</code></pre>;
                              }
                              return <code className="bg-muted text-rose-600 px-1.5 py-0.5 rounded text-xs font-mono" {...props}>{children}</code>;
                            },
                            pre({ children }) { return <>{children}</>; }
                          }}
                        >{msg.content}</ReactMarkdown>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
          <div ref={bottomRef} />
        </div>
      </div>

      {/* 输入区 */}
      <div className="border-t bg-card/80 backdrop-blur-sm pt-3 pb-1 px-1">
        <div className="max-w-3xl mx-auto">
          {/* 文档引用 */}
          {docs.length > 0 && (
            <div className="flex items-center gap-2 mb-2 flex-wrap">
              <span className="text-xs text-muted-foreground shrink-0">引用文档：</span>
              {attachDoc ? (
                <Badge className="cursor-pointer bg-blue-100 dark:bg-blue-900 text-blue-700 dark:text-blue-300 hover:bg-blue-200 text-xs" onClick={() => setAttachDoc(null)}>📎 {attachDoc.title} ✕</Badge>
              ) : (
                docs.map(doc => (
                  <Badge key={doc.id} variant="outline" className="cursor-pointer hover:bg-muted text-xs" onClick={() => setAttachDoc(doc)}>
                    {doc.fileType === "pdf" ? "📕" : doc.fileType === "pptx" ? "📊" : "📄"} {doc.title}
                  </Badge>
                ))
              )}
            </div>
          )}
          <div className="flex gap-2 items-end">
            <div className="flex-1 relative">
              <Input
                placeholder={attachDoc ? `基于「${attachDoc.title}」提问...` : "输入你的问题，Enter 发送"}
                value={question}
                onChange={(e) => setQuestion(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && !e.shiftKey && send()}
                disabled={sending}
                className="pr-10 h-10 rounded-xl border-gray-200 dark:border-gray-700 focus:border-blue-300"
              />
            </div>
            <Button onClick={send} disabled={sending || !question.trim()} size="sm" className="h-10 w-10 rounded-xl p-0 bg-blue-500 hover:bg-blue-600">
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M6 12L3.269 3.126A59.768 59.768 0 0121.485 12 59.77 59.77 0 013.27 20.876L5.999 12zm0 0h7.5" />
              </svg>
            </Button>
          </div>
          <p className="text-center text-xs text-muted-foreground mt-2">AI 基于上传的文档知识回答 · 请核实重要信息</p>
        </div>
      </div>
    </div>
  );
}
