import {
  ArrowRight,
  BarChart3,
  BookOpenCheck,
  Brain,
  ClipboardCheck,
  FileText,
  Layers3,
  MessageSquareText,
  TriangleAlert,
} from "lucide-react";
import Link from "next/link";

const MODULES = [
  {
    href: "/documents",
    icon: FileText,
    title: "文档管理",
    desc: "上传课件、讲义和题库，自动解析并建立学习材料。",
    tone: "bg-sky-50 text-sky-700 border-sky-100 dark:bg-sky-950/40 dark:text-sky-200 dark:border-sky-900",
  },
  {
    href: "/chat",
    icon: MessageSquareText,
    title: "AI 学习助手",
    desc: "围绕文档内容提问，获得可追溯的学习解释。",
    tone: "bg-teal-50 text-teal-700 border-teal-100 dark:bg-teal-950/40 dark:text-teal-200 dark:border-teal-900",
  },
  {
    href: "/flashcards",
    icon: Layers3,
    title: "闪卡记忆",
    desc: "用间隔重复安排复习，把知识点留在长期记忆里。",
    tone: "bg-amber-50 text-amber-700 border-amber-100 dark:bg-amber-950/40 dark:text-amber-200 dark:border-amber-900",
  },
  {
    href: "/exams/generate",
    icon: ClipboardCheck,
    title: "生成考题",
    desc: "从多份文档生成练习题，快速检测掌握程度。",
    tone: "bg-emerald-50 text-emerald-700 border-emerald-100 dark:bg-emerald-950/40 dark:text-emerald-200 dark:border-emerald-900",
  },
  {
    href: "/exams",
    icon: BookOpenCheck,
    title: "考试列表",
    desc: "进入考试、查看成绩，并回顾答题表现。",
    tone: "bg-rose-50 text-rose-700 border-rose-100 dark:bg-rose-950/40 dark:text-rose-200 dark:border-rose-900",
  },
  {
    href: "/wrong-questions",
    icon: TriangleAlert,
    title: "错题本",
    desc: "集中整理薄弱项，反复练习直到真正掌握。",
    tone: "bg-orange-50 text-orange-700 border-orange-100 dark:bg-orange-950/40 dark:text-orange-200 dark:border-orange-900",
  },
  {
    href: "/stats",
    icon: BarChart3,
    title: "学习统计",
    desc: "用数据看清学习进度、正确率和复习节奏。",
    tone: "bg-cyan-50 text-cyan-700 border-cyan-100 dark:bg-cyan-950/40 dark:text-cyan-200 dark:border-cyan-900",
  },
];

export default function Home() {
  return (
    <div className="space-y-6">
      <section className="surface-panel overflow-hidden rounded-2xl p-6 sm:p-8">
        <div className="grid gap-8 lg:grid-cols-[1.1fr_0.9fr] lg:items-center">
          <div className="space-y-5">
            <div className="page-kicker">Learning Workspace</div>
            <div className="space-y-3">
              <h1 className="max-w-2xl text-3xl font-bold leading-tight text-foreground sm:text-4xl">
                把文档变成问答、练习和复习计划
              </h1>
              <p className="max-w-xl text-sm leading-7 text-muted-foreground sm:text-base">
                从上传资料开始，系统会帮你完成知识提取、AI 问答、自动出题、错题复盘和闪卡复习。
              </p>
            </div>
            <div className="flex flex-wrap gap-3">
              <Link
                href="/documents"
                className="inline-flex h-10 items-center gap-2 rounded-xl bg-slate-950 px-4 text-sm font-semibold text-white shadow-lg shadow-slate-900/10 transition hover:-translate-y-0.5 hover:bg-slate-800"
              >
                上传学习资料
                <ArrowRight className="size-4" />
              </Link>
              <Link
                href="/exams/generate"
                className="inline-flex h-10 items-center gap-2 rounded-xl border border-border bg-white/70 px-4 text-sm font-semibold text-foreground transition hover:-translate-y-0.5 hover:bg-white dark:bg-white/10 dark:hover:bg-white/15"
              >
                生成一次练习
              </Link>
            </div>
          </div>

          <div className="rounded-2xl border border-slate-900/10 bg-slate-950 p-5 text-white shadow-2xl shadow-slate-900/15">
            <div className="mb-5 flex items-center justify-between">
              <div>
                <p className="text-xs text-white/50">今日学习流</p>
                <h2 className="mt-1 text-lg font-semibold">文档到掌握</h2>
              </div>
              <div className="flex size-10 items-center justify-center rounded-xl bg-emerald-500/20 text-emerald-200">
                <Brain className="size-5" />
              </div>
            </div>
            <div className="space-y-3">
              {["解析资料", "AI 解释", "生成考题", "错题复盘"].map((item, index) => (
                <div key={item} className="flex items-center gap-3 rounded-xl bg-white/8 px-3 py-3">
                  <span className="flex size-7 items-center justify-center rounded-lg bg-white/10 text-xs font-bold">
                    {index + 1}
                  </span>
                  <span className="text-sm font-medium">{item}</span>
                  <span className="ml-auto h-2 w-16 rounded-full bg-white/10">
                    <span
                      className="block h-full rounded-full bg-emerald-300"
                      style={{ width: `${90 - index * 16}%` }}
                    />
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </section>

      <section className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4">
        {MODULES.map((m) => {
          const Icon = m.icon;
          return (
            <Link key={m.href} href={m.href} className="group">
              <div className="surface-panel h-full rounded-2xl p-5 transition duration-200 hover:-translate-y-1 hover:shadow-xl">
                <div className={`mb-5 flex size-11 items-center justify-center rounded-xl border ${m.tone}`}>
                  <Icon className="size-5" />
                </div>
                <div className="space-y-2">
                  <h3 className="font-semibold text-foreground">{m.title}</h3>
                  <p className="min-h-12 text-sm leading-6 text-muted-foreground">{m.desc}</p>
                </div>
                <div className="mt-5 flex items-center text-sm font-semibold text-primary opacity-0 transition group-hover:opacity-100">
                  进入模块
                  <ArrowRight className="ml-1 size-4" />
                </div>
              </div>
            </Link>
          );
        })}
      </section>
    </div>
  );
}
