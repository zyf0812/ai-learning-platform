package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import com.exam.model.WrongQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ExamService 核心业务逻辑测试
 *
 * 重点覆盖：
 * - 简答题采分点关键词解析与模糊匹配（核心判分算法）
 * - 非简答题精确匹配
 * - hitRatio 计算与四舍五入
 * - 错题自动写入错题本
 * - 考试记录持久化
 * - 权限校验（get/delete）
 */
@ExtendWith(MockitoExtension.class)
class ExamServiceTest {

    @Mock private ExamMapper examMapper;
    @Mock private QuestionMapper questionMapper;
    @Mock private DocumentMapper docMapper;
    @Mock private DeepSeekService deepSeek;
    @Mock private RAGService ragService;
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private TaskQueueService taskQueue;

    private ExamService service;

    @BeforeEach
    void setUp() {
        service = new ExamService(examMapper, questionMapper, docMapper,
                deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // ==================== submit() 判分逻辑 ====================

    @Test
    void submit_shortanswer_hitAllKeywords_fullScore() {
        List<Question> questions = List.of(question("q1", "shortanswer",
                "简述Java的封装机制是什么？",
                "将数据和方法包装在类中，通过访问修饰符控制对内部状态的访问。",
                "封装是面向对象编程的核心特性【采分点】数据隐藏、访问控制、类定义、private修饰符"));
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        Map<String, String> answers = Map.of("q1",
                "封装就是数据隐藏，通过private修饰符实现访问控制，在类定义中使用");

        Map<String, Object> result = service.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));

        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get("q1");
        assertTrue((Boolean) detail.get("correct"));
        // 命中关键词，hitRatio 应该是 1.0（全部命中）
        assertEquals(1.0, detail.get("hitRatio"));
        assertFalse((Boolean) detail.get("isFuzzyMatch"));
    }

    @Test
    void submit_shortanswer_hitPartialKeywords_60PercentThreshold() {
        List<Question> questions = List.of(question("q1", "shortanswer",
                "什么是设计模式？",
                "设计模式是一套被反复使用的、多数人知晓的、经过分类编目的代码设计经验的总结。",
                "【采分点】代码设计、经验总结、可复用、分类目录"));
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        // 只命中 2/4 = 50%，低于阈值 0.6，应为错误
        Map<String, String> answers = Map.of("q1", "是一种代码设计");

        Map<String, Object> result = service.submit("exam1", "user1", answers);

        assertEquals(0, result.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get("q1");
        assertFalse((Boolean) detail.get("correct"));
    }

    @Test
    void submit_shortanswer_hitSeventyFivePercent_keywordCoverage_usingCharCoverage() {
        // 测试 keywordMatch 的字符覆盖率回退：关键词 "经验总结" 长度 <= 6，用户答案中 "经验总结" 完全包含
        // 关键词 "可复用" 长度 <= 6
        // 关键词 "分类目录" 长度 <= 6
        // 用户答案：包含 3 个关键词中的 2 个 = 66.7%（但 "分类目录" 不在答案中）
        // 这应刚好 >= 0.6，判为正确
        List<Question> questions = List.of(question("q1", "shortanswer",
                "什么是设计模式？",
                "一套被反复使用的代码设计经验总结。",
                "【采分点】代码设计、经验总结、可复用"));
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        // 命中 2/3 ≈ 0.667 >= 0.6，应判正确
        Map<String, String> answers = Map.of("q1", "设计模式是一套代码设计经验总结");

        Map<String, Object> result = service.submit("exam1", "user1", answers);

        assertEquals(1, result.get("score"));

        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get("q1");
        assertTrue((Boolean) detail.get("correct"));
        assertTrue((Double) detail.get("hitRatio") >= 0.6);
    }

    @Test
    void submit_shortanswer_noKeywords_exactMatchFallback() {
        // 无采分点标记，回退精确匹配
        List<Question> questions = List.of(question("q1", "shortanswer",
                "什么是变量？",
                "变量是存储数据的容器。",
                "普通解析，不含采分点标记"));
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        // 精确匹配
        Map<String, Object> r1 = service.submit("exam1", "user1",
                Map.of("q1", "变量是存储数据的容器。"));
        assertEquals(1, r1.get("score"));

        // 不精确
        Map<String, Object> r2 = service.submit("exam1", "user1",
                Map.of("q1", "变量是存储数据的容器"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submit_shortanswer_blankExplanation_exactMatchFallback() {
        List<Question> questions = List.of(question("q1", "shortanswer",
                "题目", "标准答案", null));
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        Map<String, Object> result = service.submit("exam1", "user1",
                Map.of("q1", "标准答案"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void submit_shortanswer_keywordsExtractedBeforeNewline() {
        // 采分点后有换行，只取到行尾
        List<Question> questions = List.of(question("q1", "shortanswer",
                "题目", "答案",
                "解析内容\n【采分点】关键词A、关键词B、关键词C\n其他说明文字"));
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        // 命中 2/3 = 0.667
        Map<String, Object> result = service.submit("exam1", "user1",
                Map.of("q1", "关键词A和关键词C"));

        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) ((Map<String, Object>) result.get("answers")).get("q1");
        assertTrue((Boolean) detail.get("correct"));
    }

    @Test
    void submit_choice_exactMatch() {
        List<Question> questions = List.of(question("q1", "choice",
                "Java是哪种语言？", null, null));
        questions.get(0).setAnswer("B");
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        Map<String, Object> r1 = service.submit("exam1", "user1", Map.of("q1", "B"));
        assertEquals(1, r1.get("score"));

        Map<String, Object> r2 = service.submit("exam1", "user1", Map.of("q1", "A"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submit_truefalse_exactMatch() {
        List<Question> questions = List.of(question("q1", "truefalse",
                "Java是编译型语言。", null, null));
        questions.get(0).setAnswer("正确");
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        Map<String, Object> r1 = service.submit("exam1", "user1", Map.of("q1", "正确"));
        assertEquals(1, r1.get("score"));

        Map<String, Object> r2 = service.submit("exam1", "user1", Map.of("q1", "错误"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submit_wrongAnswer_createsWrongQuestion() {
        Question q = question("q1", "choice", "题目", null, null);
        q.setAnswer("A");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        service.submit("exam1", "user1", Map.of("q1", "B"));

        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());

        WrongQuestion wq = captor.getValue();
        assertEquals("user1_q1", wq.getId());
        assertEquals("user1", wq.getUserId());
        assertEquals("q1", wq.getQuestionId());
        assertEquals("exam1", wq.getExamId());
        assertEquals("B", wq.getUserAnswer());
        assertFalse(wq.getMastered());
        assertEquals(0, wq.getReviewCount());
    }

    @Test
    void submit_correctAnswer_doesNotCreateWrongQuestion() {
        Question q = question("q1", "choice", "题目", null, null);
        q.setAnswer("A");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        service.submit("exam1", "user1", Map.of("q1", "A"));

        verify(wqMapper, never()).insert(any());
    }

    @Test
    void submit_emptyAnswers_allWrong() {
        Question q1 = question("q1", "choice", "题目1", null, null);
        q1.setAnswer("A");
        Question q2 = question("q2", "choice", "题目2", null, null);
        q2.setAnswer("B");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q1, q2));

        Map<String, Object> result = service.submit("exam1", "user1", Map.of());

        assertEquals(0, result.get("score"));
        assertEquals(2, result.get("total"));
    }

    @Test
    void submit_blankUserAnswer_trimmed() {
        Question q = question("q1", "choice", "题目", null, null);
        q.setAnswer("A");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        Map<String, Object> r1 = service.submit("exam1", "user1",
                Map.of("q1", "  A  "));
        assertEquals(1, r1.get("score"));
    }

    @Test
    void submit_hitRatiosProperlyRounded() {
        // 5 个关键词，命中 3 个 = 0.6，刚好阈值
        List<Question> questions = List.of(question("q1", "shortanswer",
                "题目", "答案",
                "【采分点】甲、乙、丙、丁、戊"));
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        // 命中 3/5 = 0.6，刚好通过阈值
        Map<String, Object> r = service.submit("exam1", "user1",
                Map.of("q1", "甲、乙、丙"));
        assertEquals(1, r.get("score"));

        // 命中 2/5 = 0.4，低于阈值
        Map<String, Object> r2 = service.submit("exam1", "user1",
                Map.of("q1", "甲、乙"));
        assertEquals(0, r2.get("score"));
    }

    @Test
    void submit_correctAnswer_withFuzzyFlagIsFalse() {
        // 命中全部关键词 100%，hitRatio=1.0
        List<Question> questions = List.of(question("q1", "shortanswer",
                "题目", "答案", "【采分点】甲、乙"));
        when(questionMapper.findByExamId("exam1")).thenReturn(questions);

        Map<String, Object> r = service.submit("exam1", "user1",
                Map.of("q1", "甲和乙都提到了"));

        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) ((Map<String, Object>) r.get("answers")).get("q1");
        assertTrue((Boolean) detail.get("correct"));
        assertEquals(1.0, detail.get("hitRatio"));
        // 100% 命中不应标记为模糊匹配
        assertFalse((Boolean) detail.get("isFuzzyMatch"));
    }

    @Test
    void submit_attemptPersisted() {
        Question q = question("q1", "choice", "题目", null, null);
        q.setAnswer("A");
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of(q));

        service.submit("exam1", "user1", Map.of("q1", "A"));

        verify(attemptMapper).insert(any());
    }

    // ==================== 权限校验 ====================

    @Test
    void get_wrongUser_throwsForbidden() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("otherUser");
        when(examMapper.findById("exam1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.get("exam1", "user1"));
        assertEquals("无权访问该考试", ex.getMessage());
    }

    @Test
    void get_notFound_returnsNull() {
        when(examMapper.findById("exam1")).thenReturn(null);
        assertNull(service.get("exam1", "user1"));
    }

    @Test
    void get_correctUser_returnsData() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("user1");
        when(examMapper.findById("exam1")).thenReturn(exam);
        when(questionMapper.findByExamId("exam1")).thenReturn(List.of());

        Map<String, Object> result = service.get("exam1", "user1");
        assertNotNull(result);
        assertEquals("exam1", result.get("id"));
    }

    @Test
    void delete_wrongUser_throwsForbidden() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("otherUser");
        when(examMapper.findById("exam1")).thenReturn(exam);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.delete("exam1", "user1"));
        assertEquals("无权删除该考试", ex.getMessage());
    }

    @Test
    void delete_notFound_throws() {
        when(examMapper.findById("exam1")).thenReturn(null);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.delete("exam1", "user1"));
        assertEquals("考试不存在", ex.getMessage());
    }

    @Test
    void delete_success() {
        Exam exam = new Exam();
        exam.setId("exam1");
        exam.setUserId("user1");
        when(examMapper.findById("exam1")).thenReturn(exam);

        service.delete("exam1", "user1");

        verify(examMapper).deleteById("exam1");
    }

    // ==================== 辅助方法 ====================

    private Question question(String id, String type, String content, String options, String explanation) {
        Question q = new Question();
        q.setId(id);
        q.setExamId("exam1");
        q.setType(type);
        q.setContent(content);
        q.setOptions(options);
        q.setExplanation(explanation);
        // 如果调用方传了 options 参数且不为 null，把它同时作为 answer（shortanswer/fill 题型的 answer 就是这个参数；choice 题型传 null，后面再显式 setAnswer）
        q.setAnswer(options != null ? options : "标准答案");
        return q;
    }
}
