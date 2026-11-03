package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
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
import static org.mockito.Mockito.*;

/**
 * 闪卡复习使用 SM-2 间隔重复算法，复习质量直接决定下一次复习时间。
 * 覆盖难度因子下限、答错重置、连续答对时 1 -> 6 -> interval*ease 的间隔推进。
 */
@ExtendWith(MockitoExtension.class)
class FlashcardServiceTest {

    @Mock private FlashCardMapper mapper;
    @Mock private KnowledgeMapper knowledgeMapper;

    private FlashcardService service;

    @BeforeEach
    void setUp() {
        service = new FlashcardService(mapper, knowledgeMapper);
    }

    private FlashCard card(int repetitions, int interval, double ease) {
        FlashCard c = new FlashCard();
        c.setId("card1");
        c.setRepetitions(repetitions);
        c.setInterval(interval);
        c.setEaseFactor(ease);
        return c;
    }

    @Test
    void firstSuccessfulReviewSchedulesOneDay() {
        FlashCard card = card(0, 0, 2.5);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 5);

        assertEquals(1, result.get("repetitions"));
        assertEquals(1, result.get("interval"));
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.0001);
        verifyUpdatedCard(card, 1);
    }

    @Test
    void secondSuccessfulReviewSchedulesSixDays() {
        FlashCard card = card(1, 1, 2.5);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 4);

        assertEquals(2, result.get("repetitions"));
        assertEquals(6, result.get("interval"));
        assertEquals(2.5, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void subsequentReviewMultipliesIntervalByEase() {
        FlashCard card = card(2, 6, 2.5);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 5);

        assertEquals(3, result.get("repetitions"));
        // ease 变为 2.6，新间隔 = round(6 * 2.6) = 16
        assertEquals(16, result.get("interval"));
        assertEquals(2.6, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void failedReviewResetsRepetitionsButKeepsEaseAboveFloor() {
        FlashCard card = card(4, 30, 2.5);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 2);

        assertEquals(0, result.get("repetitions"), "质量低于3应重置连续答对次数");
        assertEquals(1, result.get("interval"), "答错后应安排次日复习");
        assertEquals(2.18, (Double) result.get("easeFactor"), 0.0001);
    }

    @Test
    void easeFactorNeverDropsBelowMinimum() {
        FlashCard card = card(0, 0, 1.3);
        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> result = service.review("card1", 0);

        assertEquals(1.3, (Double) result.get("easeFactor"), 0.0001, "难度因子下限为 1.3");
        assertEquals(1, result.get("interval"));
        assertEquals(0, result.get("repetitions"));
    }

    @Test
    void reviewSetsNextReviewWithinIntervalWindow() {
        FlashCard card = card(1, 1, 2.5);
        when(mapper.findById("card1")).thenReturn(card);

        service.review("card1", 5);

        LocalDateTime expected = LocalDateTime.now().plusDays(6);
        assertNotNull(card.getNextReview());
        assertTrue(card.getNextReview().isAfter(expected.minusMinutes(1)));
        assertTrue(card.getNextReview().isBefore(expected.plusMinutes(1)));
    }

    @Test
    void reviewMissingCardThrows() {
        when(mapper.findById("missing")).thenReturn(null);

        var ex = assertThrows(RuntimeException.class, () -> service.review("missing", 4));
        assertEquals("闪卡不存在", ex.getMessage());
        verify(mapper, never()).updateReview(any());
    }

    @Test
    void todayCardsReturnsStats() {
        FlashCard c = card(0, 0, 2.5);
        when(mapper.findDueCards("u1", 20)).thenReturn(List.of(c));
        when(mapper.countByUserId("u1")).thenReturn(10);
        when(mapper.countDue("u1")).thenReturn(1);

        Map<String, Object> result = service.getTodayCards("u1");

        assertEquals(List.of(c), result.get("cards"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertEquals(10, stats.get("total"));
        assertEquals(1, stats.get("due"));
    }

    @Test
    void generateForDocumentCreatesOneCardPerKnowledgePoint() {
        var kp1 = new com.exam.model.KnowledgePoint();
        kp1.setId("k1");
        kp1.setTitle("标题1");
        kp1.setContent("内容1");
        var kp2 = new com.exam.model.KnowledgePoint();
        kp2.setId("k2");
        kp2.setTitle("标题2");
        kp2.setContent("内容2");
        when(knowledgeMapper.findByDocumentId("d1")).thenReturn(List.of(kp1, kp2));

        int count = service.generateForDocument("d1");

        assertEquals(2, count);
        ArgumentCaptor<FlashCard> captor = ArgumentCaptor.forClass(FlashCard.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertEquals("标题1", captor.getAllValues().get(0).getFront());
        assertEquals("内容1", captor.getAllValues().get(0).getBack());
        assertEquals("k2", captor.getAllValues().get(1).getKnowledgePointId());
    }

    private void verifyUpdatedCard(FlashCard card, int repetitions) {
        verify(mapper).updateReview(card);
        assertEquals(repetitions, card.getRepetitions());
        assertNotNull(card.getNextReview());
    }
}
