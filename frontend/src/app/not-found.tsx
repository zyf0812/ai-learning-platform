import Link from "next/link";

export default function NotFound() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 text-center">
      <div className="rounded-full bg-amber-100 p-4 dark:bg-amber-900/20">
        <svg className="h-8 w-8 text-amber-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9.172 16.172a4 4 0 015.656 0M9 10h.01M15 10h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
      </div>
      <h2 className="text-lg font-semibold text-foreground">页面不存在</h2>
      <p className="max-w-md text-sm text-muted-foreground">
        你访问的页面不存在，可能已被删除或链接有误
      </p>
      <Link
        href="/"
        className="rounded-lg bg-slate-950 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-slate-800"
      >
        返回首页
      </Link>
    </div>
  );
}
