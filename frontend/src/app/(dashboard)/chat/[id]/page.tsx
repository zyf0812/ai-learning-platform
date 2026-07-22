"use client";

import { useState, useEffect, useRef, useCallback } from "react";
import { useParams, useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkMath from "remark-math";
import rehypeHighlight from "rehype-highlight";
import rehypeRaw from "rehype-raw";
import rehypeKatex from "rehype-katex";
import "highlight.js/styles/github.min.css";
import "katex/dist/katex.min.css";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "@/lib/api";

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
      const msgs = (d.messages || []).reverse();
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
    let cancelled = false;
    const init = async () => {
      try {
        const d = await api.conversations.get(params.id as string);
        if (cancelled) return;
        if (d.error) { router.push("/chat"); return; }
        setConv(d.conversation);
        await loadMessages(0);
      } catch {
        if (!cancelled) router.push("/chat");
      } finally {
        if (!cancelled) setLoading(false);
      }
    };
    init();

    api.documents.list().then(d => {
      setDocs(d.documents || []);
    }).catch(() => {});

    return () => { cancelled = true; };
  }, [params.id]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages.length]);

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

    const tmpUId = "tmp-u-" + Date.now();
    const tmpAId = "tmp-a-" + Date.now();
    setMessages(prev => [...prev, { id: tmpUId, role: "user", content: q }, { id: tmpAId, role: "assistant", content: "" }]);

    try {
      const body: { question: string; refDoc?: string } = { question: q };
      if (attachDoc) body.refDoc = attachDoc.id;

      await api.conversations.sendStream(
        params.id as string,
        body,
        (token) => {
          setMessages(prev => prev.map(m =>
            m.id === tmpAId ? { ...m, content: m.content + token } : m
          ));
        }
      );
      // 流式结束后，从后端重新加载消息，确保 ID/顺序/内容一致
      await loadMessages(0);
    } catch (err: any) {
      setMessages(prev => prev.map(m =>
        m.id === tmpAId
          ? { ...m, id: "err-" + Date.now(), content: m.content || `❌ ${err.message}` }
          : m
      ));
    } finally { setSending(false); }
  };

  if (loading) return (
    <div className="flex items-center justify-center h-full">
      <div className="flex flex-col items-center gap-3">
        <Skeleton className="h-6 w-40 rounded-lg" />
        <Skeleton className="h-3 w-24 rounded-full" />
      </div>
    </div>
  );
  if (!conv) return null;

  return (
    <div className="flex flex-col h-full">
      <div className="flex-1 overflow-y-auto min-h-0" ref={scrollRef} onScroll={handleScroll}>
        <div className="max-w-3xl mx-auto px-4 py-6 space-y-4">
          {loadingMore && (
            <div className="flex justify-center py-3">
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <svg className="animate-spin w-3.5 h-3.5" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" fill="none" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                加载历史消息...
              </div>
            </div>
          )}
          {hasMore && !loadingMore && (
            <p className="text-center text-xs text-muted-foreground/60">向上滚动加载历史消息</p>
          )}

          {messages.length === 0 && !loadingMore && (
            <div className="flex flex-col items-center justify-center py-20 text-center">
              <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-primary/10 to-primary/5 flex items-center justify-center mb-4 ring-1 ring-primary/10">
                <svg className="w-7 h-7 text-primary" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
                  <path strokeLinecap="round" strokeLinejoin="round" d="M8.625 12a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H8.25m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0H12m4.125 0a.375.375 0 11-.75 0 .375.375 0 01.75 0zm0 0h-.375M21 12c0 4.556-4.03 8.25-9 8.25a9.764 9.764 0 01-2.555-.337A5.972 5.972 0 015.41 20.97a5.969 5.969 0 01-.474-.065 4.48 4.48 0 00.978-2.025c.09-.457-.133-.901-.467-1.226C3.93 16.178 3 14.189 3 12c0-4.556 4.03-8.25 9-8.25s9 3.694 9 8.25z" />
                </svg>
              </div>
              <h3 className="text-base font-semibold text-foreground mb-1">开始对话</h3>
              <p className="text-sm text-muted-foreground max-w-xs">向 AI 助手提问，获取基于文档知识的智能回答</p>
            </div>
          )}

          {messages.map((msg, idx) => {
            const isUser = msg.role === "user";
            const isTyping = msg.content === "" && msg.id.startsWith("tmp-");
            const showAvatar = idx === 0 || messages[idx - 1]?.role !== msg.role;

            return (
              <div key={msg.id} className={`flex gap-3 message-animate ${isUser ? "flex-row-reverse" : ""}`}>
                <div className={`shrink-0 w-7 h-7 mt-1 rounded-full flex items-center justify-center text-[11px] font-medium transition-opacity ${
                  isUser
                    ? "bg-gradient-to-br from-blue-500 to-blue-600 text-white shadow-sm"
                    : "bg-gradient-to-br from-violet-400 to-purple-500 text-white shadow-sm"
                } ${showAvatar ? "opacity-100" : "opacity-0"}`}>
                  {isUser ? "U" : "AI"}
                </div>

                <div className={`flex flex-col max-w-[80%] ${isUser ? "items-end" : "items-start"}`}>
                  {showAvatar && (
                    <span className={`text-[11px] mb-1 px-1 font-medium ${isUser ? "text-blue-500" : "text-violet-500"}`}>
                      {isUser ? "你" : "AI 助手"}
                    </span>
                  )}
                  <div className={`px-4 py-2.5 text-sm leading-relaxed ${
                    isUser
                      ? "bg-blue-500 text-white rounded-2xl rounded-tr-md shadow-sm"
                      : "bg-card border rounded-2xl rounded-tl-md shadow-sm"
                  }`}>
                    {isTyping ? (
                      <div className="flex gap-1.5 py-1.5 px-1">
                        <span className="w-2 h-2 bg-muted-foreground/40 rounded-full animate-bounce" style={{animationDelay: "0ms"}} />
                        <span className="w-2 h-2 bg-muted-foreground/40 rounded-full animate-bounce" style={{animationDelay: "150ms"}} />
                        <span className="w-2 h-2 bg-muted-foreground/40 rounded-full animate-bounce" style={{animationDelay: "300ms"}} />
                      </div>
                    ) : (
                      <div className={`markdown-body ${isUser ? "text-white [&_a]:text-white/80 [&_code]:bg-white/20" : ""}`}>
                        <ReactMarkdown 
                          remarkPlugins={[remarkGfm, remarkMath]}
                          rehypePlugins={[rehypeHighlight, rehypeRaw, rehypeKatex]}
                          components={{
                            code({ className, children, ...props }: any) {
                              const isBlock = className?.startsWith("language-");
                              if (isBlock) {
                                return (
                                  <pre className="code-block">
                                    <code className={className} {...props}>{children}</code>
                                  </pre>
                                );
                              }
                              return <code {...props}>{children}</code>;
                            },
                            a({ href, children }) {
                              return <a href={href} className="text-primary hover:underline break-all" target="_blank" rel="noopener noreferrer">{children}</a>;
                            },
                            img({ src, alt }) {
                              return <img src={src} alt={alt} className="max-w-full rounded-lg my-4" />;
                            },
                            hr({}) {
                              return <hr className="my-6 border-border" />;
                            },
                          }}
                        >{msg.content}</ReactMarkdown>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            );
          })}
          <div ref={bottomRef} className="h-2" />
        </div>
      </div>

      <div className="border-t bg-background/95 backdrop-blur-sm px-4 pt-3 pb-3">
        <div className="max-w-3xl mx-auto">
          {docs.length > 0 && (
            <div className="flex items-center gap-1.5 mb-2.5 flex-wrap">
              <span className="text-xs text-muted-foreground/60 mr-0.5">引用：</span>
              {attachDoc ? (
                <Badge
                  className="cursor-pointer bg-primary/10 text-primary hover:bg-primary/15 text-xs gap-1 px-2.5 py-0.5 rounded-full"
                  onClick={() => setAttachDoc(null)}
                >
                  <svg className="w-3 h-3" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z" /></svg>
                  {attachDoc.title}
                  <svg className="w-3 h-3 ml-0.5 opacity-60 hover:opacity-100" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}><path d="M6 18L18 6M6 6l12 12" /></svg>
                </Badge>
              ) : (
                docs.map(doc => (
                  <Badge
                    key={doc.id}
                    variant="outline"
                    className="cursor-pointer hover:bg-muted/80 text-xs gap-1 px-2.5 py-0.5 rounded-full transition-colors"
                    onClick={() => setAttachDoc(doc)}
                  >
                    {doc.fileType === "pdf" ? "📕" : doc.fileType === "pptx" ? "📊" : "📄"}
                    {doc.title}
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
                className="h-11 rounded-xl bg-muted/50 border-muted focus-visible:bg-card transition-colors pr-12"
              />
              {question.trim() && !sending && (
                <Button
                  size="sm"
                  className="absolute right-1 top-1/2 -translate-y-1/2 h-9 w-9 rounded-lg p-0 bg-blue-500 hover:bg-blue-600 shadow-sm"
                  onClick={send}
                >
                  <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                    <path d="M22 2L11 13" /><path d="M22 2l-7 20-4-9-9-4 20-7z" />
                  </svg>
                </Button>
              )}
            </div>
            {!question.trim() && (
              <Button onClick={send} disabled={sending} size="sm" className="h-11 w-11 rounded-xl p-0 bg-blue-500 hover:bg-blue-600 shadow-sm shrink-0">
                <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2} strokeLinecap="round" strokeLinejoin="round">
                  <path d="M22 2L11 13" /><path d="M22 2l-7 20-4-9-9-4 20-7z" />
                </svg>
              </Button>
            )}
          </div>
          <p className="text-center text-xs text-muted-foreground/40 mt-2">AI 基于文档知识回答 · 请核实重要信息</p>
        </div>
      </div>
    </div>
  );
}
