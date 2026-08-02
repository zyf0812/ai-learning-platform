package com.exam.service;

import com.exam.mapper.*;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 针对 ExamService.submit() 判分逻辑的单元测试：
 * - 各类题型精确匹配
 * - 简答题 parseKeywords / keywordMatch 模糊匹配
 * - FUZZY_THRESHOLD = 0.6 边界
 * - 错题本写入
 * - 分数汇总
 */
@ExtendWith(MockitoExtension.class)
class ExamServiceGradingTest {

    @Mock private ExamMapper examMapper;
    @Mock private QuestionMapper questionMapper;
    @Mock private DocumentMapper docMapper;
    @Mock private DeepSeekService deepSeek;
    @Mock private RAGService ragService;
    @Mock private WrongQuestionMapper wqMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private TaskQueueService taskQueue;

    private ExamService examService;

    @BeforeEach
    void setUp() {
        examService = new ExamService(examMapper, questionMapper, docMapper,
            deepSeek, ragService, wqMapper, attemptMapper, taskQueue);
    }

    // ===== 工具方法：创建 Question =====
    private Question q(String id, String type, String content, String answer, String explanation, String options) {
        Question q = new Question();
        q.setId(id);
        q.setType(type);
        q.setContent(content);
        q.setAnswer(answer);
        q.setExplanation(explanation);
        q.setOptions(options == null ? "[]" : options);
        return q;
    }

    // ===== 选择题精确匹配 =====

    @Test
    void choiceCorrectAnswer() {
        Question choice = q("c1", "choice", "Java之父？", "B", "詹姆斯·高斯林",
            "[\"A|Bill\",\"B|James\",\"C|Linus\"]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(choice));

        var result = examService.submit("e1", "u1", Map.of("c1", "B"));

        assertEquals(1, result.get("score"));
        assertEquals(1, result.get("total"));
        @SuppressWarnings("unchecked")
        var answers = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) answers.get("c1").get("correct"));
    }

    @Test
    void choiceWrongAnswerNoScore() {
        Question choice = q("c1", "choice", "2+2?", "C", "", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(choice));

        var result = examService.submit("e1", "u1", Map.of("c1", "A"));

        assertEquals(0, result.get("score"));
        // 错题写入
        verify(wqMapper).insert(any(WrongQuestion.class));
    }

    @Test
    void choiceAnswerIsCaseSensitive() {
        // answer 存储是 "A"，用户答小写 "a" 应该判错
        Question choice = q("c1", "choice", "Q", "A", "", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(choice));

        var result = examService.submit("e1", "u1", Map.of("c1", "a"));

        assertEquals(0, result.get("score"));
    }

    // ===== 填空题精确匹配 =====

    @Test
    void fillBlankExactMatch() {
        Question fill = q("f1", "fill", "___是Java集合框架的根接口", "Collection", "", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(fill));

        var result = examService.submit("e1", "u1", Map.of("f1", "Collection"));

        assertEquals(1, result.get("score"));
    }

    @Test
    void fillBlankAnswerTrimmedBeforeCompare() {
        // 用户有前后空格应该被 trim
        Question fill = q("f1", "fill", "Q", "Spring", "", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(fill));

        var result = examService.submit("e1", "u1", Map.of("f1", "  Spring  "));

        assertEquals(1, result.get("score"));
    }

    // ===== 判断题 =====

    @Test
    void trueFalseCorrect() {
        Question tf = q("t1", "truefalse", "Java是编译型语言", "正确", "", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(tf));

        var result = examService.submit("e1", "u1", Map.of("t1", "正确"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void trueFalseWrong() {
        Question tf = q("t1", "truefalse", "Golang由Google发明", "正确", "", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(tf));

        var result = examService.submit("e1", "u1", Map.of("t1", "错误"));
        assertEquals(0, result.get("score"));
    }

    // ===== 简答题：无采分点时退化为精确匹配 =====

    @Test
    void shortAnswerNoKeywordsExactMatch() {
        Question sa = q("s1", "shortanswer", "什么是OOP？", "面向对象编程",
            "解释：四大特性。",  // 无【采分点】标记
            "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(sa));

        var result = examService.submit("e1", "u1", Map.of("s1", "面向对象编程"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void shortAnswerNoKeywordsMismatch() {
        Question sa = q("s1", "shortanswer", "Q", "标准答案", "无采分点解释", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(sa));

        var result = examService.submit("e1", "u1", Map.of("s1", "完全不同"));
        assertEquals(0, result.get("score"));
    }

    // ===== 简答题：有采分点的模糊匹配 =====

    @Test
    void shortAnswerAllKeywordsHitScoreFull() {
        // 3个关键词全部命中 -> 100% >= 60% 阈值
        Question sa = q("s1", "shortanswer", "简述Java三大特性", "封装继承多态",
            "三大特性是面向对象基础\n【采分点】封装、继承、多态",
            "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(sa));

        var result = examService.submit("e1", "u1",
            Map.of("s1", "Java三大特性包括封装，还有继承和多态的概念"));
        assertEquals(1, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals(1.0, graded.get("s1").get("hitRatio"));
    }

    @Test
    void shortAnswerTwoOfThreeHitsPassesThreshold() {
        // 3个命中2个 = 66.6% >= 60%，通过
        Question sa = q("s1", "shortanswer", "Q", "A",
            "xxx\n【采分点】线程、进程、协程、锁",
            "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(sa));

        // 4个关键词：只命中 线程 + 进程 = 50%，失败？不，我们要测试 3/5 = 60%，所以换
        Question sa2 = q("s2", "shortanswer", "Q", "A",
            "xxx\n【采分点】封装、继承、多态、抽象、接口",
            "[]");
        when(questionMapper.findByExamId("e2")).thenReturn(List.of(sa2));

        // 命中其中 3 个：封装/继承/多态 -> 3/5=60% 刚好及格
        var result = examService.submit("e2", "u1",
            Map.of("s2", "这题需要用封装和继承，还要考虑多态来解决"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void shortAnswerBelowThresholdFails() {
        // 5个关键词只命中 2 个 = 40% < 60%，判错
        Question sa = q("s1", "shortanswer", "Q", "A",
            "【采分点】A、B、C、D、E",
            "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(sa));

        var result = examService.submit("e1", "u1",
            Map.of("s1", "我的答案包含A和B两个观点"));
        assertEquals(0, result.get("score"));
    }

    @Test
    void shortAnswerPartialHitMarksFuzzyFlag() {
        // 覆盖率介于 60%~99% 之间时 isFuzzyMatch=true
        Question sa = q("s1", "shortanswer", "Q", "A",
            "【采分点】封装、继承、多态",
            "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(sa));

        // 只命中 2/3 = 66.7%，应标记模糊匹配
        var result = examService.submit("e1", "u1",
            Map.of("s1", "用到了封装，还有继承。"));

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertTrue((Boolean) graded.get("s1").get("isFuzzyMatch"));
        assertTrue((Boolean) graded.get("s1").get("correct"));
    }

    // ===== keywordMatch 子串+覆盖率 =====

    @Test
    void keywordExactSubstringHit() {
        Question sa = q("s1", "shortanswer", "Q", "A",
            "【采分点】数据隐藏",
            "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(sa));

        var result = examService.submit("e1", "u1", Map.of("s1", "封装提供了数据隐藏机制"));
        assertEquals(1, result.get("score"));
    }

    @Test
    void keywordCoverageMatchForLongerKeywords() {
        // 关键词长度>6且无子串命中则直接false（防止"数据隐藏"vs"信息隐藏"共享"隐藏"时，由于长度4<=6触发字符覆盖率）
        // 测试关键词长度<=6时触发字符覆盖率：如"隐藏"(2字)，50%覆盖率要求至少1字
        Question sa = q("s1", "shortanswer", "Q", "A",
            "【采分点】隐藏",
            "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(sa));

        // 用户答案含"隐"字 -> 覆盖率 50% -> 通过
        var result = examService.submit("e1", "u1", Map.of("s1", "信息隐式保护"));
        assertEquals(1, result.get("score"));
    }

    // ===== 错题本 + id 组合键 =====

    @Test
    void wrongQuestionIdIsComposite() {
        Question choice = q("q99", "choice", "Q", "B", "", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(choice));

        examService.submit("e1", "user001", Map.of("q99", "A"));

        ArgumentCaptor<WrongQuestion> captor = ArgumentCaptor.forClass(WrongQuestion.class);
        verify(wqMapper).insert(captor.capture());
        assertEquals("user001_q99", captor.getValue().getId());
        assertEquals("user001", captor.getValue().getUserId());
        assertEquals("q99", captor.getValue().getQuestionId());
        assertEquals("e1", captor.getValue().getExamId());
        assertEquals("A", captor.getValue().getUserAnswer());
        assertFalse(captor.getValue().getMastered());
        assertEquals(0, captor.getValue().getReviewCount());
    }

    @Test
    void wrongQuestionInsertExceptionsSwallowed() {
        Question choice = q("q1", "choice", "Q", "A", "", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(choice));
        // 重复主键会抛异常（同一用户再次答错）
        doThrow(new RuntimeException("duplicate key")).when(wqMapper).insert(any());

        // 不应抛出
        assertDoesNotThrow(() -> examService.submit("e1", "u1", Map.of("q1", "B")));
    }

    // ===== 分数汇总 =====

    @Test
    void mixedQuestionsScoreSum() {
        List<Question> qs = List.of(
            q("c1", "choice", "", "A", "", "[]"),   // 用户答A -> 对
            q("f1", "fill", "", "Spring", "", "[]"), // 用户答 Spring -> 对
            q("t1", "truefalse", "", "错误", "", "[]"), // 用户答正确 -> 错
            q("s1", "shortanswer", "", "", "【采分点】甲、乙、丙", "[]") // 甲乙丙 -> 对
        );
        when(questionMapper.findByExamId("e1")).thenReturn(qs);

        var result = examService.submit("e1", "u1", Map.of(
            "c1", "A",
            "f1", "Spring",
            "t1", "正确",
            "s1", "甲和乙，还有丙都算上"
        ));

        assertEquals(3, result.get("score"));
        assertEquals(4, result.get("total"));
    }

    // ===== 未作答 / 缺失答案 =====

    @Test
    void missingAnswerTreatedAsEmptyString() {
        Question fill = q("f1", "fill", "", "hello", "", "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(fill));

        var result = examService.submit("e1", "u1", Map.of()); // 未作答

        assertEquals(0, result.get("score"));
        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        assertEquals("", graded.get("f1").get("userAnswer"));
    }

    // ===== hitRatio 保留两位小数 =====

    @Test
    void hitRatioRoundedToTwoDecimals() {
        // 3个命中2个 = 0.666... -> 应取 0.67 或 0.66？
        Question sa = q("s1", "shortanswer", "Q", "A",
            "【采分点】关键词1、关键词2、关键词3",
            "[]");
        when(questionMapper.findByExamId("e1")).thenReturn(List.of(sa));

        var result = examService.submit("e1", "u1",
            Map.of("s1", "答案中只包含关键词1和关键词2"));

        @SuppressWarnings("unchecked")
        var graded = (Map<String, Map<String, Object>>) result.get("answers");
        double ratio = (Double) graded.get("s1").get("hitRatio");
        // 2/3 = 0.666 ... Math.round(ratio*100)/100.0 => 0.67
        assertEquals(0.67, ratio, 0.001);
    }
}
