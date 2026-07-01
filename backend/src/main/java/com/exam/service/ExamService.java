package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ExamService {
    private static final Logger log = LoggerFactory.getLogger(ExamService.class);
    private final ExamMapper examMapper;
    private final QuestionMapper questionMapper;
    private final DocumentMapper docMapper;
    private final DeepSeekService deepSeek;
    private final RAGService ragService;
    private final WrongQuestionMapper wqMapper;
    private final ExamAttemptMapper attemptMapper;
    private final TaskQueueService taskQueue;
    private final ObjectMapper json = new ObjectMapper();

    public ExamService(ExamMapper examMapper, QuestionMapper questionMapper,
                       DocumentMapper docMapper,
                       DeepSeekService deepSeek, RAGService ragService,
                       WrongQuestionMapper wqMapper, ExamAttemptMapper attemptMapper,
                       TaskQueueService taskQueue) {
        this.examMapper = examMapper;
        this.questionMapper = questionMapper;
        this.docMapper = docMapper;
        this.deepSeek = deepSeek;
        this.ragService = ragService;
        this.wqMapper = wqMapper;
        this.attemptMapper = attemptMapper;
        this.taskQueue = taskQueue;
    }

    public List<Exam> list(String userId) {
        return examMapper.findByUserId(userId);
    }

    public Map<String, Object> get(String id) {
        Exam exam = examMapper.findById(id);
        if (exam == null) return null;
        List<Question> questions = questionMapper.findByExamId(id);
        Map<String, Object> result = new HashMap<>();
        result.put("id", exam.getId());
        result.put("title", exam.getTitle());
        result.put("userId", exam.getUserId());
        result.put("questionTypes", exam.getQuestionTypes());
        result.put("questionCount", exam.getQuestionCount());
        result.put("questions", questions);
        return result;
    }

    public void delete(String id) {
        examMapper.deleteById(id);
    }

    /** 异步出题：创建考试 → 返回 taskId → 后台生成 */
    public Map<String, Object> generateAsync(String userId, String title, List<String> documentIds,
                         List<String> types, int count, Map<String, Integer> typeCounts, boolean questionBankMode) {
        Exam exam = new Exam();
        exam.setId(UUID.randomUUID().toString().substring(0, 8));
        exam.setTitle(title);
        exam.setUserId(userId);
        exam.setQuestionTypes(String.join(",", types));
        exam.setQuestionCount(count);
        examMapper.insert(exam);

        String examId = exam.getId();
        log.info("考试 {} 已创建，等待 DeepSeek 异步出题...", examId);

        taskQueue.submit(examId, () -> {
            try {
                List<String> allChunks = new ArrayList<>();
                for (String docId : documentIds) {
                    List<String> chunks = ragService.getChunksRandom(docId, 8);
                    allChunks.addAll(chunks);
                }
                if (allChunks.isEmpty()) {
                    for (String docId : documentIds) {
                        var doc = docMapper.findById(docId);
                        if (doc != null && doc.getContent() != null) {
                            allChunks.add(doc.getContent().substring(0, Math.min(doc.getContent().length(), 2000)));
                        }
                    }
                }
                if (allChunks.isEmpty()) return "ERROR:请先上传文档";

                List<Map<String, Object>> questionData;
                
                // 检查是否有题库文档
                boolean hasQuestionBank = false;
                for (String docId : documentIds) {
                    var doc = docMapper.findById(docId);
                    if (doc != null && Boolean.TRUE.equals(doc.getIsQuestionBank())) {
                        hasQuestionBank = true;
                        break;
                    }
                }
                
                if (hasQuestionBank) {
                    // 题库模式：严格按文档中的原题出题，仅生成解析
                    String docContent = String.join("\n", allChunks);
                    String bankPrompt = "请从以下文档中严格提取所有题目，完全保留原题内容和答案，不要修改。仅对每道题生成解析。\n" +
                        "返回JSON数组：[{\"type\":\"choice/fill/truefalse/shortanswer\",\"content\":\"原题内容\",\"options\":[\"A|原选项A\",\"B|原选项B\"],\"answer\":\"原答案\",\"explanation\":\"AI生成的解析\"}]\n" +
                        "文档内容：\n" + docContent.substring(0, Math.min(docContent.length(), 15000));
                    
                    String raw = deepSeek.chat("你是一个题库解析器。严格从文档中提取原题和原答案，不要修改题目内容。只对每道题补充解析。返回JSON数组。", bankPrompt);
                    raw = raw.trim().replaceAll("```[\\\\w]*\\\\n?", "").replaceAll("```", "");
                    raw = raw.replaceAll("^(json|JSON)\\s*", "");
                    questionData = json.readValue(raw, new TypeReference<>() {});
                } else {
                    // 正常模式：AI 出题
                    String typeDesc = String.join("、", types);
                    // 分题型数量描述
                    StringBuilder typeCountDesc = new StringBuilder("出题数量：");
                    if (typeCounts != null && !typeCounts.isEmpty()) {
                        for (var e : typeCounts.entrySet()) {
                            String label = switch (e.getKey()) {
                                case "choice" -> "选择题"; case "fill" -> "填空题";
                                case "truefalse" -> "判断题"; case "shortanswer" -> "简答题";
                                default -> e.getKey();
                            };
                            typeCountDesc.append(label).append(e.getValue()).append("道，");
                        }
                    } else {
                        typeCountDesc.append("共").append(count).append("道，类型包括").append(typeDesc);
                    }
                    String knowledge2 = String.join("\n---\n", allChunks);

                    String prompt = String.format("""
                        %s
                        
                        严格JSON数组，每题格式：
                        - choice：选择题 {"type":"choice","content":"题目","options":["A|选项|解析","B|选项|解析","C|选项|解析","D|选项|解析"],"answer":"B","explanation":"解析"}
                        - fill：填空题 {"type":"fill","content":"___是Java的核心框架","options":[],"answer":"Spring","explanation":"解析"}
                        - truefalse：判断题 {"type":"truefalse","content":"题目","options":[],"answer":"正确","explanation":"解析"}
                        - shortanswer：简答题 {"type":"shortanswer","content":"题目","options":[],"answer":"参考答案","explanation":"解析"}

                        必须遵守：
                        1. 严格按上述格式，填空题和判断题的answer绝不能是字母
                        2. 选择题answer只能是A/B/C/D中的一个
                        3. 每题有且仅有1个正确答案
                        
                        知识点：
                        %s
                        """, typeCountDesc.toString(), knowledge2.length() > 10000 ? knowledge2.substring(0, 10000) : knowledge2);

                    String resp = deepSeek.chat("你是严谨的考试出题专家。严格区分题型：选择题answer写字母，填空题answer写文本，判断题answer写正确/错误。每题格式严格按要求。输出纯JSON数组。", prompt);
                    String raw = resp.trim().replaceAll("```[\\\\w]*\\\\n?", "").replaceAll("```", "");
                    raw = raw.replaceAll("^(json|JSON)\\s*", "");
                    questionData = json.readValue(raw, new TypeReference<>() {});
                }

                for (Map<String, Object> q : questionData) {
                    Question question = new Question();
                    question.setId(UUID.randomUUID().toString().substring(0, 8));
                    question.setExamId(examId);
                    question.setType((String) q.get("type"));
                    question.setContent((String) q.get("content"));
                    question.setOptions(json.writeValueAsString(q.getOrDefault("options", List.of())));
                    question.setAnswer((String) q.get("answer"));
                    question.setExplanation((String) q.getOrDefault("explanation", ""));
                    questionMapper.insert(question);
                }
                log.info("考试 {} 出题完成: {}题", examId, questionData.size());
                return examId;
            } catch (Exception e) {
                log.error("考试 {} 出题失败: {}", examId, e.getMessage());
                return "ERROR:" + e.getMessage();
            }
        }, "exam:task:");

        Map<String, Object> result = new HashMap<>();
        result.put("exam", exam);
        result.put("taskId", examId);
        return result;
    }

    /** 查询出题状态 */
    public String getTaskStatus(String taskId) {
        return taskQueue.getStatus("exam:task:", taskId);
    }

    public Map<String, Object> submit(String examId, String userId, Map<String, String> answers) {
        List<Question> questions = questionMapper.findByExamId(examId);
        int score = 0;
        Map<String, Object> graded = new HashMap<>();

        for (Question q : questions) {
            String userAns = answers.getOrDefault(q.getId(), "").trim();
            boolean correct = q.getAnswer().trim().equals(userAns);
            if (correct) score++;

            graded.put(q.getId(), Map.of(
                "userAnswer", userAns,
                "correct", correct,
                "correctAnswer", q.getAnswer()
            ));

            // 错题写入错题本
            if (!correct) {
                WrongQuestion wq = new WrongQuestion();
                wq.setId(userId + "_" + q.getId());
                wq.setUserId(userId);
                wq.setQuestionId(q.getId());
                wq.setExamId(examId);
                wq.setUserAnswer(userAns);
                wq.setMastered(false);
                wq.setReviewCount(0);
                try { wqMapper.insert(wq); } catch (Exception ignored) {}
            }
        }

        Map<String, Object> result = new HashMap<>();
        String attemptId = UUID.randomUUID().toString().substring(0, 8);
        result.put("id", attemptId);
        result.put("score", score);
        result.put("total", questions.size());
        result.put("answers", graded);

        // 持久化考试记录
        ExamAttempt attempt = new ExamAttempt();
        attempt.setId(attemptId);
        attempt.setExamId(examId);
        attempt.setUserId(userId);
        try {
            attempt.setAnswers(json.writeValueAsString(graded));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            attempt.setAnswers("{}");
        }
        attempt.setScore(score);
        attempt.setTotal(questions.size());
        try { attemptMapper.insert(attempt); } catch (Exception e) {
            log.error("保存考试记录失败: {}", e.getMessage());
        }

        return result;
    }
}
