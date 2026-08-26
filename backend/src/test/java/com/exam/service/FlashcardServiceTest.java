package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import com.exam.model.KnowledgePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock
    private FlashCardMapper flashCardMapper;

    @Mock
    private KnowledgeMapper knowledgeMapper;

    private FlashcardService flashcardService;

    @BeforeEach
    void setUp() {
        flashcardService = new FlashcardService(flashCardMapper, knowledgeMapper);
    }

    private FlashCard makeCard(double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    // ======== SM-2 算法：首次复习（reps=0） ========

    @Test
    void review_firstTimeQuality5_easeUnchangedFirstInterval1() {
        // 初次 reps=0, quality=5（完美）
        // quality >=3 → reps=1, reps==1 → interval=1
        // ease = 2.5 + (0.1 - 0 * (0.08 + 0*0.02)) = 2.5 + 0.1 = 2.6
        FlashCard card = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("c1", 5);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.001);
        verify(flashCardMapper).updateReview(argThat(c ->
            c.getRepetitions() == 1 && c.getInterval() == 1
        ));
    }

    @Test
    void review_firstTimeQuality3_minimumPassing_easeDecreases() {
        // quality=3 → (5-3)=2 → ease = 2.5 + (0.1 - 2*(0.08 + 2*0.02))
        // = 2.5 + (0.1 - 2*(0.08+0.04)) = 2.5 + (0.1 - 2*0.12) = 2.5 + (0.1 - 0.24) = 2.5 - 0.14 = 2.36
        FlashCard card = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("c1", 3);
        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(2.36, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void review_firstTimeQuality2_lapseResetsRepsAndInterval() {
        // quality <3 → reps=0, interval=1, ease 仍计算但会有下限
        // (5-2)=3 → ease = 2.5 + (0.1 - 3*(0.08 + 3*0.02))
        // = 2.5 + (0.1 - 3*(0.14)) = 2.5 + (0.1 - 0.42) = 2.18
        FlashCard card = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("c1", 2);
        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(2.18, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void review_firstTimeQuality0_lapseWithLowestQuality() {
        FlashCard card = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("c1", 0);
        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    // ======== SM-2：第二次复习（reps=1 → reps=2） ========

    @Test
    void review_secondRepQuality5_intervalBecomes6() {
        // reps=1 (已复习过1次通过), quality=5
        // reps==2 → interval=6
        FlashCard card = makeCard(2.6, 1, 1);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("c1", 5);
        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    // ======== SM-2：第三次及以上（interval = prev * ease） ========

    @Test
    void review_thirdRep_intervalMultipliedByEase() {
        // reps=2 → reps=3, 先算新的 ease：quality=5 → delta=+0.1 → ease=2.7+0.1=2.8
        // new_interval = round(6 * 2.8) = round(16.8) = 17
        FlashCard card = makeCard(2.7, 6, 2);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("c1", 5);
        assertEquals(3, result.get("repetitions"));
        assertEquals(17, result.get("interval")); // round(6*2.8=16.8)
    }

    @Test
    void review_fourthRep_intervalMultipliedAgain() {
        // reps=3, interval=15, ease=2.5, quality=4
        // (5-4)=1 → ease delta = 0.1 - 1*(0.08 + 1*0.02) = 0.1 - 0.1 = 0 → ease 保持 2.5
        // new_interval = round(15 * 2.5) = round(37.5) = 38
        FlashCard card = makeCard(2.5, 15, 3);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("c1", 4);
        assertEquals(4, result.get("repetitions"));
        assertEquals(38, result.get("interval"));
        assertEquals(2.5, (Double) result.get("easeFactor"), 0.001);
    }

    // ======== Ease factor 下限保护（1.3） ========

    @Test
    void review_easeWouldDropBelow13_flooredTo13() {
        // 低 quality 会导致 ease 大幅下降，但不能 < 1.3
        FlashCard card = makeCard(1.35, 10, 5);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        // quality=0 → (5-0)=5 → delta = 0.1 - 5*(0.08 + 5*0.02)
        // = 0.1 - 5*(0.08+0.10) = 0.1 - 5*0.18 = 0.1 - 0.9 = -0.8 → 1.35 - 0.8 = 0.55 < 1.3 → 被钳制
        Map<String, Object> result = flashcardService.review("c1", 0);
        // quality<3 → reps=0, interval=1
        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(1.3, (Double) result.get("easeFactor"), 0.001);
    }

    @Test
    void review_easeExactly13_quality0_stays13() {
        FlashCard card = makeCard(1.3, 5, 4);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("c1", 0);
        assertEquals(1.3, (Double) result.get("easeFactor"), 0.001);
    }

    // ======== Lapse 后再次通过 ========

    @Test
    void review_afterLapseQuality4_startsFromReps1Interval1() {
        // 之前 reps=0 (lapsed), ease=1.8, interval=1
        // quality=4 → (5-4)=1 → delta=0.1 - 1*(0.08+0.02) = 0.1 - 0.1 = 0 → ease=1.8
        FlashCard card = makeCard(1.8, 1, 0);
        when(flashCardMapper.findById("c1")).thenReturn(card);

        Map<String, Object> result = flashcardService.review("c1", 4);
        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(1.8, (Double) result.get("easeFactor"), 0.001);
    }

    // ======== 不存在的闪卡 ========

    @Test
    void review_nonexistentCard_throwsNotFound() {
        when(flashCardMapper.findById("nope")).thenReturn(null);
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> flashcardService.review("nope", 4));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ======== getTodayCards ========

    @Test
    void getTodayCards_returnsCardsAndStats() {
        FlashCard c1 = new FlashCard();
        FlashCard c2 = new FlashCard();
        List<FlashCard> cards = List.of(c1, c2);

        when(flashCardMapper.findDueCards("u1", 20)).thenReturn(cards);
        when(flashCardMapper.countByUserId("u1")).thenReturn(100);
        when(flashCardMapper.countDue("u1")).thenReturn(15);

        Map<String, Object> result = flashcardService.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        List<FlashCard> resultCards = (List<FlashCard>) result.get("cards");
        @SuppressWarnings("unchecked")
        Map<String, Integer> stats = (Map<String, Integer>) result.get("stats");

        assertEquals(2, resultCards.size());
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ======== generateForDocument ========

    @Test
    void generateForDocument_createsCardPerKnowledgePoint() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("k1");
        p1.setTitle("知识点1");
        p1.setContent("内容1");

        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("k2");
        p2.setTitle("知识点2");
        p2.setContent("内容2");

        when(knowledgeMapper.findByDocumentId("doc1")).thenReturn(List.of(p1, p2));

        int count = flashcardService.generateForDocument("doc1");

        assertEquals(2, count);
        verify(flashCardMapper, times(2)).insert(any(FlashCard.class));
    }

    @Test
    void generateForDocument_noKnowledgePoints_returnsZero() {
        when(knowledgeMapper.findByDocumentId("empty")).thenReturn(List.of());
        assertEquals(0, flashcardService.generateForDocument("empty"));
        verify(flashCardMapper, never()).insert(any());
    }

    // ======== delete ========

    @Test
    void delete_delegatesToMapper() {
        doNothing().when(flashCardMapper).deleteById("c1");
        flashcardService.delete("c1");
        verify(flashCardMapper).deleteById("c1");
    }
}
