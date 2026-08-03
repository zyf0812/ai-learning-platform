package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import com.exam.model.KnowledgePoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    // ==================== SM-2 间隔重复算法核心测试 ====================

    private FlashCard makeCard(double ease, int interval, int reps) {
        FlashCard c = new FlashCard();
        c.setId("c1");
        c.setEaseFactor(ease);
        c.setInterval(interval);
        c.setRepetitions(reps);
        c.setNextReview(LocalDateTime.now());
        return c;
    }

    @Test
    void reviewFirstRepetitionQualityGood() {
        // reps=0 时，quality >= 3 进入 reps=1，interval=1
        FlashCard c = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("c1")).thenReturn(c);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 5);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(2.5 + (0.1 - (5 - 5) * (0.08 + (5 - 5) * 0.02)),
            (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewSecondRepetitionSetsIntervalTo6() {
        // reps=1 时，quality >= 3 进入 reps=2，interval=6
        FlashCard c = makeCard(2.5, 1, 1);
        when(flashCardMapper.findById("c1")).thenReturn(c);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 4);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
    }

    @Test
    void reviewThirdRepetitionUsesIntervalTimesEase() {
        // reps>=2 时，新 interval = round(old_interval * ease)
        // old=6, ease=2.5 → 15
        FlashCard c = makeCard(2.5, 6, 2);
        when(flashCardMapper.findById("c1")).thenReturn(c);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 4);

        assertEquals(3, result.get("repetitions"));
        assertEquals(15, result.get("interval")); // round(6 * 2.5)
    }

    @Test
    void reviewLowQualityResetsIntervalToOne() {
        // quality < 3 → interval=1, reps=0
        FlashCard c = makeCard(2.5, 30, 10);
        when(flashCardMapper.findById("c1")).thenReturn(c);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 2);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewWorstQualityResetsEverything() {
        FlashCard c = makeCard(2.5, 100, 50);
        when(flashCardMapper.findById("c1")).thenReturn(c);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 0);

        assertEquals(0, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
    }

    @Test
    void reviewEaseFactorHasFloorOf1_3() {
        // quality=0 时 ease 会减少很多：ease = e + (0.1 - 5*(0.08+5*0.02))
        //    = 1.4 + (0.1 - 5*(0.08+0.10)) = 1.4 + (0.1 - 5*0.18) = 1.4 + (0.1 - 0.9) = 1.4 - 0.8 = 0.6
        // 应该被夹到 1.3
        FlashCard c = makeCard(1.4, 10, 5);
        when(flashCardMapper.findById("c1")).thenReturn(c);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 0);

        assertEquals(1.3, (Double) result.get("easeFactor"), 0.0001,
            "Ease factor should be clamped to minimum 1.3");
    }

    @Test
    void reviewQuality5KeepsEaseTheSame() {
        // quality=5: ease = e + (0.1 - 0) = 增加 0.1
        FlashCard c = makeCard(2.5, 10, 5);
        when(flashCardMapper.findById("c1")).thenReturn(c);
        doNothing().when(flashCardMapper).updateReview(any());

        var result = flashcardService.review("c1", 5);
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void reviewNonexistentCardThrows() {
        when(flashCardMapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class,
            () -> flashcardService.review("missing", 4));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    @Test
    void reviewPersistsUpdatedCard() {
        FlashCard c = makeCard(2.5, 0, 0);
        when(flashCardMapper.findById("c1")).thenReturn(c);
        doNothing().when(flashCardMapper).updateReview(any());

        flashcardService.review("c1", 4);

        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper).updateReview(captor.capture());
        FlashCard saved = captor.getValue();

        assertEquals(1, saved.getRepetitions());
        assertEquals(1, saved.getInterval());
        assertNotNull(saved.getNextReview(),
            "nextReview should be set");
        // 新的复习日期应 >= 今天 + interval 天 - 1分钟余量
        assertTrue(saved.getNextReview().isAfter(LocalDateTime.now().plusDays(1).minusMinutes(1)),
            "nextReview should be approximately 1 day in the future");
    }

    // ==================== getTodayCards 测试 ====================

    @Test
    void getTodayCardsReturnsCardsAndStats() {
        FlashCard c1 = new FlashCard(); c1.setId("c1");
        FlashCard c2 = new FlashCard(); c2.setId("c2");

        when(flashCardMapper.findDueCards("u1", 20)).thenReturn(List.of(c1, c2));
        when(flashCardMapper.countByUserId("u1")).thenReturn(100);
        when(flashCardMapper.countDue("u1")).thenReturn(15);

        var result = flashcardService.getTodayCards("u1");

        @SuppressWarnings("unchecked")
        var cards = (List<FlashCard>) result.get("cards");
        @SuppressWarnings("unchecked")
        var stats = (Map<String, Object>) result.get("stats");

        assertEquals(2, cards.size());
        assertEquals(100, stats.get("total"));
        assertEquals(15, stats.get("due"));
    }

    // ==================== generateForDocument 测试 ====================

    @Test
    void generateForDocumentCreatesFlashcards() {
        KnowledgePoint p1 = new KnowledgePoint();
        p1.setId("kp1"); p1.setTitle("标题1"); p1.setContent("内容1");
        KnowledgePoint p2 = new KnowledgePoint();
        p2.setId("kp2"); p2.setTitle("标题2"); p2.setContent("内容2");
        KnowledgePoint p3 = new KnowledgePoint();
        p3.setId("kp3"); p3.setTitle("标题3"); p3.setContent("内容3");

        when(knowledgeMapper.findByDocumentId("d1"))
            .thenReturn(List.of(p1, p2, p3));

        int count = flashcardService.generateForDocument("d1");

        assertEquals(3, count);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(flashCardMapper, times(3)).insert(captor.capture());

        var created = captor.getAllValues();
        assertEquals("标题1", created.get(0).getFront());
        assertEquals("内容1", created.get(0).getBack());
        assertEquals("kp1", created.get(0).getKnowledgePointId());
        assertNotNull(created.get(0).getId(), "ID should be generated");

        assertEquals("标题3", created.get(2).getFront());
        assertEquals("kp3", created.get(2).getKnowledgePointId());
    }

    @Test
    void generateForDocumentNoKnowledgePointsReturnsZero() {
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of());

        assertEquals(0, flashcardService.generateForDocument("d1"));
        verify(flashCardMapper, never()).insert(any());
    }

    // ==================== delete 测试 ====================

    @Test
    void deleteDelegatesToMapper() {
        doNothing().when(flashCardMapper).deleteById("c1");

        flashcardService.delete("c1");

        verify(flashCardMapper).deleteById("c1");
    }
}
