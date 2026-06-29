package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.model.FlashCard;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class FlashcardService {
    private final FlashCardMapper mapper;

    public FlashcardService(FlashCardMapper m) { this.mapper = m; }

    public Map<String, Object> getTodayCards(String userId) {
        List<FlashCard> cards = mapper.findDueCards(userId, 20);
        int total = mapper.countByUserId(userId);
        int due = mapper.countDue(userId);
        return Map.of("cards", cards, "stats", Map.of("total", total, "due", due));
    }

    public Map<String, Object> review(String cardId, int quality) {
        FlashCard card = mapper.findById(cardId);
        if (card == null) throw new RuntimeException("闪卡不存在");

        double ease = card.getEaseFactor() + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
        if (ease < 1.3) ease = 1.3;

        int interval, reps;
        if (quality < 3) { interval = 1; reps = 0; }
        else {
            reps = card.getRepetitions() + 1;
            if (reps == 1) interval = 1;
            else if (reps == 2) interval = 6;
            else interval = (int) Math.round(card.getInterval() * ease);
        }

        card.setEaseFactor(ease);
        card.setInterval(interval);
        card.setRepetitions(reps);
        card.setNextReview(LocalDateTime.now().plusDays(interval));
        mapper.updateReview(card);

        return Map.of("easeFactor", ease, "interval", interval, "repetitions", reps);
    }
}
