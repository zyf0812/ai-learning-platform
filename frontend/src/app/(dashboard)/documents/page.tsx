"use client";

import { useState, useEffect, useCallback } from "react";
import { useRouter } from "next/navigation";
import { api } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { ConfirmDialog } from "@/components/confirm-dialog";
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

interface Doc {
  id: string;
  title: string;
  originalFilename: string;
  fileType: string;
  createdAt: string;
}

const FILE_ICON: Record<string, string> = {
  pdf: "📕",
  docx: "📘",
  doc: "📘",
  pptx: "📊",
  ppt: "📊",
  md: "📝",
  txt: "📄",
};

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
    load();
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
    } catch (err: any) {
      setError(err.message);
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

  if (loading) return <p className="text-muted-foreground">加载中...</p>;

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <h2 className="text-xl font-semibold">我的文档</h2>
        <Button onClick={() => setUploadOpen(true)}>上传文档</Button>
      </div>

      {docs.length === 0 ? (
        <Card className="p-12 text-center text-muted-foreground">
          <p className="text-lg mb-2">还没有文档</p>
          <p>上传 PDF、Word、PPT、Markdown 或文本文件开始学习</p>
        </Card>
      ) : (
        <div className="grid gap-3">
          {docs.map((doc) => (
            <Card
              key={doc.id}
              className="p-4 flex items-center justify-between hover:shadow-md cursor-pointer"
              onClick={() => router.push(`/documents/${doc.id}`)}
            >
              <div className="flex items-center gap-3">
                <span className="text-2xl">
                  {FILE_ICON[doc.fileType] || "📄"}
                </span>
                <div>
                  <h3 className="font-semibold">{doc.title}</h3>
                  <p className="text-xs text-muted-foreground">
                    {doc.originalFilename} ·{" "}
                    {new Date(doc.createdAt).toLocaleDateString("zh-CN")}
                  </p>
                </div>
              </div>
              <div className="flex gap-2 flex-wrap sm:flex-nowrap" onClick={(e) => e.stopPropagation()}>
                <DropdownMenu>
                  <DropdownMenuTrigger className="inline-flex items-center justify-center rounded-md border border-input bg-background px-3 py-1.5 text-sm font-medium hover:bg-accent hover:text-accent-foreground">
                    操作
                  </DropdownMenuTrigger>
                  <DropdownMenuContent>
                    <DropdownMenuItem
                      onClick={() => router.push(`/documents/${doc.id}`)}
                    >
                      查看内容
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      onClick={() =>
                        router.push(`/documents/${doc.id}/knowledge`)
                      }
                    >
                      知识点复习
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      onClick={() =>
                        router.push(`/documents/${doc.id}/mindmap`)
                      }
                    >
                      思维导图
                    </DropdownMenuItem>
                    <DropdownMenuItem
                      className="text-red-500"
                      onClick={() => setDeleteTarget(doc.id)}
                    >
                      删除
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            </Card>
          ))}
        </div>
      )}

      <Dialog open={uploadOpen} onOpenChange={setUploadOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>上传文档</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleUpload} className="space-y-4">
            <Input
              placeholder="文档标题（可选，默认用文件名）"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
            />
            <Input
              type="file"
              accept=".pdf,.docx,.doc,.pptx,.ppt,.md,.txt"
              onChange={(e) => setFile(e.target.files?.[0] || null)}
            />
            {error && <p className="text-red-500 text-sm">{error}</p>}
            <label className="flex items-center gap-2 cursor-pointer text-sm">
              <input type="checkbox" checked={isQuestionBank} onChange={e => setIsQuestionBank(e.target.checked)} />
              <span className="text-foreground">📚 此文档为题库（出题时严格使用原题原答案）</span>
            </label>
            <p className="text-xs text-muted-foreground">
              支持 PDF、Word、PowerPoint、Markdown、TXT（最大 20MB）
            </p>
            <Button type="submit" disabled={uploading} className="w-full">
              {uploading ? "上传解析中..." : "上传"}
            </Button>
          </form>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={!!deleteTarget}
        title="删除文档"
        message="确认删除此文档？相关的考题和知识点也会被删除。"
        confirmText="删除"
        danger
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      />
    </div>
  );
}
