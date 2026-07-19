"use client";

import { useState, useEffect, useCallback } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  BookOpen,
  FileArchive,
  FileText,
  MoreHorizontal,
  Network,
  Sparkles,
  Trash2,
  Upload,
} from "lucide-react";

interface Doc {
  id: string;
  title: string;
  originalFilename: string;
  fileType: string;
  createdAt: string;
}

const FILE_STYLE: Record<string, { label: string; className: string }> = {
  pdf: { label: "PDF", className: "bg-red-50 text-red-700 border-red-100" },
  docx: { label: "DOC", className: "bg-sky-50 text-sky-700 border-sky-100" },
  doc: { label: "DOC", className: "bg-sky-50 text-sky-700 border-sky-100" },
  pptx: { label: "PPT", className: "bg-orange-50 text-orange-700 border-orange-100" },
  ppt: { label: "PPT", className: "bg-orange-50 text-orange-700 border-orange-100" },
  md: { label: "MD", className: "bg-emerald-50 text-emerald-700 border-emerald-100" },
  txt: { label: "TXT", className: "bg-slate-50 text-slate-700 border-slate-100" },
};

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "请求失败";
}

export default function DocumentsPage() {
  const [docs, setDocs] = useState<Doc[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const [isQuestionBank, setIsQuestionBank] = useState(false);
  const [error, setError] = useState("");
  const [deleteTarget, setDeleteTarget] = useState<string | null>(null);
  const router = useRouter();

  const load = useCallback(async () => {
    try {
      const data = await api.documents.list();
      setDocs(data.documents);
    } catch {
      setError("加载失败");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    queueMicrotask(load);
  }, [load]);

  const handleUpload = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) {
      setError("请选择文件");
      return;
    }
    setUploading(true);
    setError("");
    try {
      const formData = new FormData();
      formData.append("file", file);
      if (title) formData.append("title", title);
      if (isQuestionBank) formData.append("isQuestionBank", "true");
      await api.documents.upload(formData);
      setUploadOpen(false);
      setTitle("");
      setFile(null);
      load();
    } catch (err: unknown) {
      setError(getErrorMessage(err));
    } finally {
      setUploading(false);
    }
  };

  const handleDelete = async () => {
    if (!deleteTarget) return;
    await api.documents.delete(deleteTarget);
    setDeleteTarget(null);
    load();
  };

  if (loading) {
    return (
      <div className="space-y-3">
        <Skeleton className="h-28 w-full rounded-2xl" />
        {Array.from({ length: 3 }).map((_, i) => (
          <Skeleton key={i} className="h-20 w-full rounded-2xl" />
        ))}
      </div>
    );
  }

  return (
    <div className="space-y-5">
      <section className="surface-panel flex flex-col gap-5 rounded-2xl p-6 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="page-kicker">Documents</div>
          <h1 className="mt-2 text-2xl font-bold text-foreground">我的文档</h1>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-muted-foreground">
            上传教材、课件或题库，系统会解析内容并用于 AI 问答、自动出题和知识点复习。
          </p>
        </div>
        <Button onClick={() => setUploadOpen(true)} className="h-10 bg-slate-950 hover:bg-slate-800">
          <Upload className="size-4" />
          上传文档
        </Button>
      </section>

      {docs.length === 0 ? (
        <Card className="surface-panel flex min-h-80 flex-col items-center justify-center rounded-2xl border-dashed p-10 text-center">
          <div className="mb-5 flex size-14 items-center justify-center rounded-2xl bg-emerald-50 text-emerald-700">
            <FileArchive className="size-7" />
          </div>
          <h2 className="text-lg font-semibold text-foreground">还没有文档</h2>
          <p className="mt-2 max-w-md text-sm leading-6 text-muted-foreground">
            上传 PDF、Word、PPT、Markdown 或文本文件，开始构建你的个人知识库。
          </p>
          <Button onClick={() => setUploadOpen(true)} className="mt-6 bg-slate-950 hover:bg-slate-800">
            <Upload className="size-4" />
            上传第一份文档
          </Button>
        </Card>
      ) : (
        <div className="grid gap-3">
          {docs.map((doc) => {
            const style = FILE_STYLE[doc.fileType] || { label: doc.fileType?.toUpperCase() || "FILE", className: "bg-slate-50 text-slate-700 border-slate-100" };
            return (
              <Card
                key={doc.id}
                className="surface-panel group flex cursor-pointer items-center justify-between gap-4 rounded-2xl p-4 transition hover:-translate-y-0.5 hover:shadow-xl"
                onClick={() => router.push(`/documents/${doc.id}`)}
              >
                <div className="flex min-w-0 items-center gap-4">
                  <div className={`flex size-12 shrink-0 items-center justify-center rounded-xl border text-xs font-bold ${style.className}`}>
                    {style.label}
                  </div>
                  <div className="min-w-0">
                    <h3 className="truncate font-semibold text-foreground">{doc.title}</h3>
                    <p className="mt-1 truncate text-xs text-muted-foreground">
                      {doc.originalFilename} · {new Date(doc.createdAt).toLocaleDateString("zh-CN")}
                    </p>
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-2" onClick={(e) => e.stopPropagation()}>
                  <DropdownMenu>
                    <DropdownMenuTrigger className="inline-flex size-9 items-center justify-center rounded-xl border border-border bg-white/70 text-muted-foreground transition hover:bg-white hover:text-foreground dark:bg-white/10">
                      <MoreHorizontal className="size-4" />
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end" className="rounded-xl">
                      <DropdownMenuItem onClick={() => router.push(`/documents/${doc.id}`)}>
                        <FileText className="mr-2 size-4" /> 查看内容
                      </DropdownMenuItem>
                      <DropdownMenuItem onClick={() => router.push(`/documents/${doc.id}/knowledge`)}>
                        <BookOpen className="mr-2 size-4" /> 知识点复习
                      </DropdownMenuItem>
                      <DropdownMenuItem onClick={() => router.push(`/documents/${doc.id}/mindmap`)}>
                        <Network className="mr-2 size-4" /> 思维导图
                      </DropdownMenuItem>
                      <DropdownMenuItem className="text-red-500" onClick={() => setDeleteTarget(doc.id)}>
                        <Trash2 className="mr-2 size-4" /> 删除
                      </DropdownMenuItem>
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      <Dialog open={uploadOpen} onOpenChange={setUploadOpen}>
        <DialogContent className="rounded-2xl">
          <DialogHeader>
            <DialogTitle>上传文档</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleUpload} className="space-y-4">
            <Input
              placeholder="文档标题，可选"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
            <Input
              type="file"
              accept=".pdf,.docx,.doc,.pptx,.ppt,.md,.txt"
              onChange={(e) => setFile(e.target.files?.[0] || null)}
            />
            {error && <p className="rounded-xl bg-red-50 p-3 text-sm text-red-600">{error}</p>}
            <label className="flex cursor-pointer items-start gap-2 rounded-xl border bg-muted/40 p-3 text-sm">
              <input
                type="checkbox"
                checked={isQuestionBank}
                onChange={(e) => setIsQuestionBank(e.target.checked)}
                className="mt-1"
              />
              <span className="text-foreground">
                此文档为题库，出题时严格使用原题和原答案。
              </span>
            </label>
            <p className="flex items-center gap-2 text-xs text-muted-foreground">
              <Sparkles className="size-3.5" />
              支持 PDF、Word、PowerPoint、Markdown、TXT，最大 20MB。
            </p>
            <Button type="submit" disabled={uploading} className="h-10 w-full bg-slate-950 hover:bg-slate-800">
              {uploading ? "上传解析中..." : "上传"}
            </Button>
          </form>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteTarget}
        title="删除文档"
        message="确认删除此文档？相关考题和知识点也会被删除。"
        confirmText="删除"
        danger
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
