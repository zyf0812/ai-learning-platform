"use client";

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";

interface FlashCard {
  id: string;
  front: string;
  back: string;
  easeFactor: number;
  repetitions: number;
}

export default function ReviewPage() {
  const [cards, setCards] = useState<FlashCard[]>([]);
  const [index, setIndex] = useState(0);
  const [flipped, setFlipped] = useState(false);
  const [loading, setLoading] = useState(true);
  const [done, setDone] = useState(false);
  const router = useRouter();

  useEffect(() => {
    const token = localStorage.getItem("token");
    fetch("/api/flashcards", {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then((r) => r.json())
      .then((d) => {
        if (d.cards.length === 0) setDone(true);
        setCards(d.cards);
        setLoading(false);
      });
  }, []);

  const submit = async (quality: number) => {
    const card = cards[index];
    const token = localStorage.getItem("token");
    await fetch("/api/flashcards/review", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ cardId: card.id, quality }),
    });

    setFlipped(false);
    if (index + 1 < cards.length) {
      setIndex(index + 1);
    } else {
      setDone(true);
    }
  };

  if (loading) return <p className="text-gray-400">加载中...</p>;
  if (done) {
    return (
      <div className="max-w-lg mx-auto space-y-4 text-center">
        <h2 className="text-xl font-semibold">🎉 复习完成！</h2>
        <p className="text-gray-400">今天的闪卡已经全部复习完毕</p>
        <Button onClick={() => router.push("/flashcards")}>返回</Button>
      </div>
    );
  }

  const card = cards[index];

  return (
    <div className="max-w-lg mx-auto space-y-4">
      <p className="text-sm text-gray-400">
        {index + 1} / {cards.length}
      </p>

      <Card
        className="p-8 min-h-[200px] flex items-center justify-center cursor-pointer text-center hover:shadow-md transition"
        onClick={() => setFlipped(!flipped)}
      >
        <div>
          <p className="text-sm text-gray-400 mb-2">
            {flipped ? "背面 · 答案" : "正面 · 问题"}
          </p>
          <p className="text-lg font-medium whitespace-pre-wrap">
            {flipped ? card.back : card.front}
          </p>
        </div>
      </Card>

      <p className="text-xs text-center text-gray-400">点击卡片翻转查看答案</p>

      {flipped && (
        <div className="space-y-2">
          <p className="text-sm font-medium text-center">你的掌握程度？</p>
          <div className="grid grid-cols-6 gap-2">
            {[
              { label: "完全不会", value: 0, color: "bg-red-500" },
              { label: "几乎不会", value: 1, color: "bg-red-400" },
              { label: "有点印象", value: 2, color: "bg-orange-400" },
              { label: "记得一些", value: 3, color: "bg-yellow-400" },
              { label: "基本掌握", value: 4, color: "bg-green-400" },
              { label: "完全掌握", value: 5, color: "bg-green-500" },
            ].map((opt) => (
              <Button
                key={opt.value}
                size="sm"
                className={`text-xs text-white ${opt.color}`}
                onClick={() => submit(opt.value)}
              >
                {opt.label}
              </Button>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
