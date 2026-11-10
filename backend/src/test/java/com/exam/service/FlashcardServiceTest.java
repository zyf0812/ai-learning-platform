package com.exam.service;

import com.exam.mapper.FlashCardMapper;
import com.exam.mapper.KnowledgeMapper;
import com.exam.model.FlashCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * FlashcardService 间隔重复算法测试
 *
 * 重点覆盖：
 * - SuperMemo 变体算法的 easeFactor 计算公式
 * - easeFactor 下限保护（>= 1.3）
 * - 第一次复习（reps == 1）、第二次（reps == 2）、多次后的间隔递推
 * - quality < 3（忘记）后的重置行为
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

    // ==================== 第一次复习（reps = 0 → reps = 1） ====================

    @Test
    void review_firstTime_quality5_interval1() {
        FlashCard card = baseCard();
        card.setRepetitions(0);
        card.setInterval(1);

        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> r = service.review("card1", 5);

        // quality >= 3，reps+1 = 1，reps==1 时 interval = 1
        assertEquals(1, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // ease = 2.5 + (0.1 - 0*(0.08+0.02)) = 2.6
        assertTrue((Double) r.get("easeFactor") > 2.5);
        verify(mapper).updateReview(any());
    }

    @Test
    void review_firstTime_quality3_interval1() {
        FlashCard card = baseCard();
        card.setRepetitions(0);

        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> r = service.review("card1", 3);

        // 勉强记住（quality=3），reps=1，interval=1
        assertEquals(1, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        // ease = 2.5 + (0.1 - 2*0.10) = 2.5 - 0.1 = 2.4
        assertTrue((Double) r.get("easeFactor") >= 1.3);
    }

    // ==================== 第二次复习（reps = 1 → reps = 2） ====================

    @Test
    void review_secondTime_quality5_interval6() {
        FlashCard card = baseCard();
        card.setRepetitions(1);
        card.setInterval(1);

        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> r = service.review("card1", 5);

        assertEquals(2, r.get("repetitions"));
        assertEquals(6, r.get("interval")); // reps == 2 固定 6 天
    }

    // ==================== 第三次及以后（reps >= 2） ====================

    @Test
    void review_thirdTime_intervalMultipliedByEase() {
        FlashCard card = baseCard();
        card.setRepetitions(2);
        card.setInterval(6);
        card.setEaseFactor(2.5);

        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> r = service.review("card1", 5);

        assertEquals(3, r.get("repetitions"));
        // interval = round(6 * easeFactor) = round(6 * 2.6) = round(15.6) = 16
        assertEquals(16, r.get("interval"));
    }

    // ==================== 忘记卡片（quality < 3） ====================

    @Test
    void review_forgottenCard_intervalResetTo1_repsResetTo0() {
        FlashCard card = baseCard();
        card.setRepetitions(3);
        card.setInterval(20);

        when(mapper.findById("card1")).thenReturn(card);

        Map<String, Object> r = service.review("card1", 1);

        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
    }

    @Test
    void review_forgottenCard_easeStillCalculated() {
        FlashCard card = baseCard();
        card.setRepetitions(3);
        card.setInterval(20);
        card.setEaseFactor(2.5);

        when(mapper.findById("card1")).thenReturn(card);

        // quality = 1 → (5-1)=4
        // ease = 2.5 + (0.1 - 4*(0.08+4*0.02))
        //      = 2.5 + (0.1 - 4*0.16) = 2.5 + (0.1 - 0.64) = 1.96
        Map<String, Object> r = service.review("card1", 1);

        assertEquals(0, r.get("repetitions"));
        assertEquals(1, r.get("interval"));
        double ease = (Double) r.get("easeFactor");
        // quality=1 时 ease = 2.5 + (0.1 - 4*0.16) = 1.96
        assertTrue(ease >= 1.3);
    }

    // ==================== easeFactor 边界 ====================

    @Test
    void review_easeFactorBelowMinimum_clampedTo13() {
        FlashCard card = baseCard();
        card.setEaseFactor(1.3);

        when(mapper.findById("card1")).thenReturn(card);

        // quality=1 且 ease 已经很低，计算后应该 < 1.3，但被钳位
        Map<String, Object> r = service.review("card1", 1);

        assertEquals(1.3, r.get("easeFactor"));
    }

    @Test
    void review_easeFactorVeryLow_quality1_clamped() {
        FlashCard card = baseCard();
        card.setEaseFactor(1.3);

        when(mapper.findById("card1")).thenReturn(card);

        // (5-1)=4, ease = 1.3 + (0.1 - 4*0.16) = 1.3 - 0.54 = 0.76 → clamp to 1.3
        Map<String, Object> r = service.review("card1", 1);

        assertEquals(1.3, r.get("easeFactor"));
    }

    // ==================== 卡片不存在 ====================

    @Test
    void review_notFound_throws() {
        when(mapper.findById("unknown")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.review("unknown", 5));
        assertEquals("闪卡不存在", ex.getMessage());
    }

    // ==================== 辅助 ====================

    private FlashCard baseCard() {
        FlashCard card = new FlashCard();
        card.setId("card1");
        card.setFront("front");
        card.setBack("back");
        card.setRepetitions(0);
        card.setInterval(0);
        card.setEaseFactor(2.5);
        return card;
    }
}
