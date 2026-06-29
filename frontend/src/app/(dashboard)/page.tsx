import Link from "next/link";

const MODULES = [
  {
    href: "/documents", icon: "📄", title: "文档管理", desc: "上传教学文档，自动解析和索引",
    color: "from-blue-500 to-blue-600", bg: "bg-blue-50 dark:bg-blue-950",
  },
  {
    href: "/chat", icon: "💬", title: "AI 学习助手", desc: "基于文档内容的智能问答",
    color: "from-violet-500 to-purple-600", bg: "bg-violet-50 dark:bg-violet-950",
  },
  {
    href: "/flashcards", icon: "🃏", title: "闪卡记忆", desc: "间隔重复记忆，科学复习",
    color: "from-amber-500 to-orange-600", bg: "bg-amber-50 dark:bg-amber-950",
  },
  {
    href: "/exams/generate", icon: "📝", title: "生成考题", desc: "多文档 AI 自动出题",
    color: "from-emerald-500 to-green-600", bg: "bg-emerald-50 dark:bg-emerald-950",
  },
  {
    href: "/exams", icon: "📋", title: "考试列表", desc: "参加考试，查漏补缺",
    color: "from-rose-500 to-pink-600", bg: "bg-rose-50 dark:bg-rose-950",
  },
  {
    href: "/wrong-questions", icon: "❌", title: "错题本", desc: "错题收集，反复练习",
    color: "from-red-500 to-red-600", bg: "bg-red-50 dark:bg-red-950",
  },
  {
    href: "/stats", icon: "📊", title: "学习统计", desc: "进度、正确率一览",
    color: "from-cyan-500 to-teal-600", bg: "bg-cyan-50 dark:bg-cyan-950",
  },
];

export default function Home() {
  return (
    <div className="space-y-8">
      <div className="text-center py-4">
        <h2 className="text-2xl font-bold text-foreground mb-2">
          欢迎使用 AI 学习平台
        </h2>
        <p className="text-muted-foreground">
          上传教学文档 → AI 提取知识点 → 智能出题 → 巩固复习
        </p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        {MODULES.map((m) => (
          <Link key={m.href} href={m.href}>
            <div className={`group relative ${m.bg} border border-transparent rounded-xl p-5 h-full hover:shadow-md hover:border-primary/20 transition-all duration-200 hover:-translate-y-0.5`}>
              <div className="w-10 h-10 rounded-lg bg-white/80 dark:bg-white/10 flex items-center justify-center text-xl mb-4 group-hover:scale-110 transition-transform">
                {m.icon}
              </div>
              <h3 className="font-semibold text-foreground mb-1">{m.title}</h3>
              <p className="text-sm text-muted-foreground leading-relaxed">{m.desc}</p>
              <div className={`mt-3 h-1 rounded-full bg-gradient-to-r ${m.color} w-0 group-hover:w-full transition-all duration-300`} />
            </div>
          </Link>
        ))}
      </div>
    </div>
  );
}
