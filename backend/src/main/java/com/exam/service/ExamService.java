package com.exam.service;

import com.exam.mapper.*;
import com.exam.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Transactional
public class ExamService {
    private static final Logger log = LoggerFactory.getLogger(ExamService.class);
    /** 简答题采分点命中阈值：>= 60% 判为正确 */
    private static final double FUZZY_THRESHOLD = 0.6;
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

    public Map<String, Object> get(String id, String userId) {
        Exam exam = examMapper.findById(id);
        if (exam == null) return null;
        if (!exam.getUserId().equals(userId)) throw new RuntimeException("无权访问该考试");
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

    public void delete(String id, String userId) {
        Exam exam = examMapper.findById(id);
        if (exam == null) throw new RuntimeException("考试不存在");
        if (!exam.getUserId().equals(userId)) throw new RuntimeException("无权删除该考试");
        examMapper.deleteById(id);
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
                            String structure = ragService.extractStructure(docId);
                            String raw = doc.getContent().substring(0, Math.min(doc.getContent().length(), 12000));
                            allChunks.add(structure.isEmpty() ? raw : structure + "\n\n" + raw);
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
                    // 题库模式：取完整文档内容提取原题
                    List<String> rawContents = new ArrayList<>();
                    for (String docId : documentIds) {
                        var doc = docMapper.findById(docId);
                        if (doc != null && doc.getContent() != null) {
                            rawContents.add(doc.getContent());
                        }
                    }
                    String docContent = rawContents.isEmpty() ? String.join("\n", allChunks)
                        : String.join("\n---\n", rawContents);
                    String bankPrompt = "请从以下文档中严格提取所有题目，完全保留原题内容和答案，不要修改。仅对每道题生成解析。\n" +
                        "返回JSON数组：[{\"type\":\"choice/fill/truefalse/shortanswer\",\"content\":\"原题内容\",\"options\":[\"A|原选项A\",\"B|原选项B\"],\"answer\":\"原答案\",\"explanation\":\"AI生成的解析\"}]\n" +
                        "文档内容：\n" + docContent.substring(0, Math.min(docContent.length(), 30000));
                    
                    String raw = deepSeek.chat("你是一个题库解析器。严格从文档中提取原题和原答案，不要修改题目内容。只对每道题补充解析。返回JSON数组。", bankPrompt);
                    raw = raw.trim().replaceAll("```[\\\\w]*\\\\n?", "").replaceAll("```", "");
                    raw = raw.replaceAll("^(json|JSON)\\s*", "");
                    questionData = json.readValue(raw, new TypeReference<>() {});
                } else {
                    // 正常模式：AI 出题（分批+并行+知识分片+去重+重试）
                    // 展平为单个题型列表：[choice, choice, fill, fill, ...]
                    List<String> flatTypes = new ArrayList<>();
                    if (typeCounts != null && !typeCounts.isEmpty()) {
                        for (var e : typeCounts.entrySet()) {
                            for (int i = 0; i < e.getValue(); i++) flatTypes.add(e.getKey());
                        }
                    } else {
                        int perType = count / types.size();
                        int rem = count % types.size();
                        for (int i = 0; i < types.size(); i++) {
                            int n = perType + (i < rem ? 1 : 0);
                            for (int j = 0; j < n; j++) flatTypes.add(types.get(i));
                        }
                    }

                    // 按每批15题切分
                    int BATCH = 15;
                    int totalBatches = (flatTypes.size() + BATCH - 1) / BATCH;
                    String systemMsg = "你是严谨的考试出题专家。严格区分题型：选择题answer写字母，填空题answer写文本，判断题answer写正确/错误。每题格式严格按要求。输出纯JSON数组。";

                    // 并行出题
                    ExecutorService pool = Executors.newFixedThreadPool(Math.min(totalBatches, 5));
                    List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();

                    for (int bi = 0; bi < totalBatches; bi++) {
                        int from = bi * BATCH;
                        int to = Math.min(from + BATCH, flatTypes.size());
                        List<String> batch = flatTypes.subList(from, to);

                        // 知识分片：每批分配不同的知识点
                        int chunkPerBatch = allChunks.size() / totalBatches + 1;
                        int cf = bi * chunkPerBatch;
                        int ct = Math.min(cf + chunkPerBatch, allChunks.size());
                        String batchKnowledge = String.join("\n---\n",
                            cf < allChunks.size() ? allChunks.subList(cf, ct) : allChunks);
                        if (batchKnowledge.length() < 1000) {
                            Collections.shuffle(allChunks);
                            batchKnowledge = String.join("\n---\n",
                                allChunks.subList(0, Math.min(5, allChunks.size())));
                        }

                        // 统计本批题型数量
                        Map<String, Integer> batchCounts = new LinkedHashMap<>();
                        for (String t : batch) batchCounts.merge(t, 1, Integer::sum);
                        StringBuilder desc = new StringBuilder("请生成以下题目：");
                        for (var e : batchCounts.entrySet()) {
                            String label = switch (e.getKey()) {
                                case "choice" -> "选择题"; case "fill" -> "填空题";
                                case "truefalse" -> "判断题"; case "shortanswer" -> "简答题";
                                default -> e.getKey();
                            };
                            desc.append(label).append(e.getValue()).append("道，");
                        }
                        desc.append("共").append(batch.size()).append("题");

                        String prompt = String.format("""
                            %s
                            
                            严格JSON数组，每题格式：
                            - choice：选择题 {"type":"choice","content":"题目","options":["A|选项|解析","B|选项|解析","C|选项|解析","D|选项|解析"],"answer":"B","explanation":"解析"}
                            - fill：填空题 {"type":"fill","content":"___是Java的核心框架","options":[],"answer":"Spring","explanation":"解析"}
                            - truefalse：判断题 {"type":"truefalse","content":"题目","options":[],"answer":"正确","explanation":"解析"}
                            - shortanswer：简答题 {"type":"shortanswer","content":"题目","options":[],"answer":"参考答案","explanation":"解析【采分点】关键词1、关键词2、关键词3"}

                            必须遵守：
                            1. 严格按上述格式，填空题和判断题的answer绝不能是字母
                            2. 选择题answer只能是A/B/C/D中的一个
                            3. 每题有且仅有1个正确答案
                            4. 简答题的explanation末尾必须追加【采分点】标记，后接3-5个核心关键词，用顿号分隔。关键词必须是能独立体现答题要点的词或短语（如"封装"、"8种基本数据类型"、"int占4字节"），不要把同类的细分项拆成多个关键词
                            
                            知识点：
                            %s
                            """, desc.toString(), batchKnowledge);

                        int batchIdx = bi;
                        futures.add(CompletableFuture.supplyAsync(() -> {
                            // 单批重试最多3次
                            for (int retry = 0; retry < 3; retry++) {
                                try {
                                    String retryHint = retry > 0 ? "\n【重要】上次返回格式有误，本次请严格输出纯JSON数组。" : "";
                                    String resp = deepSeek.chat(systemMsg + retryHint, prompt);
                                    String raw = resp.trim().replaceAll("```[\\\\w]*\\\\n?", "").replaceAll("```", "");
                                    raw = raw.replaceAll("^(json|JSON)\\s*", "");
                                    List<Map<String, Object>> data = json.readValue(raw, new TypeReference<>() {});
                                    log.info("考试 {} 第{}/{}批完成: {}题", examId, batchIdx + 1, totalBatches, data.size());
                                    return data;
                                } catch (Exception e) {
                                    if (retry == 2) throw new RuntimeException("批次" + (batchIdx + 1) + "重试3次均失败: " + e.getMessage(), e);
                                    log.warn("考试 {} 第{}/{}批失败，重试{}/3: {}", examId, batchIdx + 1, totalBatches, retry + 1, e.getMessage());
                                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                                }
                            }
                            return List.of(); // unreachable
                        }, pool));
                    }

                    // 等待全部批次完成
                    List<Map<String, Object>> allData = futures.stream()
                        .map(CompletableFuture::join)
                        .flatMap(List::stream)
                        .collect(java.util.stream.Collectors.toList());
                    pool.shutdown();

                    // 题目内容去重
                    Set<String> seen = new HashSet<>();
                    questionData = new ArrayList<>();
                    for (Map<String, Object> q : allData) {
                        String content = ((String) q.get("content")).trim();
                        if (seen.add(content)) {
                            questionData.add(q);
                        }
                    }
                    if (questionData.size() < allData.size()) {
                        log.warn("考试 {} 去重移除 {} 道重复题", examId, allData.size() - questionData.size());
                    }
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
            String correctAnswer = q.getAnswer().trim();
            boolean isFuzzy = false;
            double hitRatio = 0.0;
            boolean correct;

            if ("shortanswer".equals(q.getType())) {
                // 简答题：基于采分点关键词命中比例判分
                List<String> keywords = parseKeywords(q.getExplanation());
                if (keywords.isEmpty()) {
                    // 无采分点标记，回退精确匹配
                    correct = correctAnswer.equals(userAns);
                    hitRatio = correct ? 1.0 : 0.0;
                } else {
                    int hit = 0;
                    String userLower = userAns.toLowerCase();
                    for (String kw : keywords) {
                        if (keywordMatch(userLower, kw.toLowerCase())) hit++;
                    }
                    hitRatio = (double) hit / keywords.size();
                    correct = hitRatio >= FUZZY_THRESHOLD;
                    isFuzzy = correct && hitRatio < 1.0;
                }
            } else {
                // 其他题型：精确匹配
                correct = correctAnswer.equals(userAns);
                hitRatio = correct ? 1.0 : 0.0;
            }
            if (correct) score++;

            Map<String, Object> g = new HashMap<>();
            g.put("userAnswer", userAns);
            g.put("correct", correct);
            g.put("correctAnswer", q.getAnswer());
            g.put("isFuzzyMatch", isFuzzy);
            g.put("hitRatio", Math.round(hitRatio * 100) / 100.0);
            graded.put(q.getId(), g);

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

    /** 从 explanation 中解析【采分点】关键词列表 */
    private List<String> parseKeywords(String explanation) {
        if (explanation == null || explanation.isBlank()) return List.of();
        int idx = explanation.indexOf("【采分点】");
        if (idx < 0) return List.of();
        String tail = explanation.substring(idx + "【采分点】".length()).trim();
        // 取到行尾或字符串尾（防止解析后续内容）
        int nl = tail.indexOf('\n');
        if (nl >= 0) tail = tail.substring(0, nl);
        // 按顿号、逗号、空格分隔
        String[] parts = tail.split("[、,，\\s]+");
        List<String> kws = new ArrayList<>();
        for (String p : parts) {
            String s = p.trim();
            if (!s.isEmpty()) kws.add(s);
        }
        return kws;
    }

    /** 关键词匹配：子串包含 OR 关键词字符覆盖率 >= 0.5 */
    private boolean keywordMatch(String text, String keyword) {
        if (text.contains(keyword)) return true;
        // 关键词字符覆盖率：用户答案中包含的关键词字符比例
        // 解决同义词问题：如"数据隐藏" vs "信息隐藏"（共享"隐藏"两字）
        if (keyword.length() > 6) return false;
        Set<Character> kwChars = new HashSet<>();
        for (char c : keyword.toCharArray()) kwChars.add(c);
        int covered = 0;
        for (char c : kwChars) {
            if (text.indexOf(c) >= 0) covered++;
        }
        double coverage = (double) covered / kwChars.size();
        return coverage >= 0.5;
    }
}
