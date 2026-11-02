package com.exam.controller;

import com.exam.mapper.*;
import com.exam.model.Exam;
import com.exam.model.Question;
import com.exam.model.User;
import com.exam.model.WrongQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 管理后台的可见范围依赖角色与监管关系，错误的放行会导致跨用户数据泄露。
 */
@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private ExamMapper examMapper;
    @Mock
    private WrongQuestionMapper wrongQuestionMapper;
    @Mock
    private QuestionMapper questionMapper;
    @Mock
    private GroupMapper groupMapper;
    @Mock
    private SupervisionMapper supervisionMapper;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(userMapper, examMapper, wrongQuestionMapper,
            questionMapper, groupMapper, supervisionMapper);
    }

    private Authentication auth(String name) {
        return new UsernamePasswordAuthenticationToken(name, null, List.of());
    }

    private User user(String id, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername(id);
        u.setRole(role);
        return u;
    }

    @Test
    void listUsersRejectsNormalUser() {
        when(userMapper.findById("u1")).thenReturn(user("u1", "user"));

        ResponseEntity<?> response = controller.listUsers(auth("u1"));

        assertEquals(403, response.getStatusCode().value());
        verify(userMapper, never()).findByRole(anyString());
        verify(supervisionMapper, never()).findByAdmin(anyString());
    }

    @Test
    void rootSeesAllNormalUsers() {
        when(userMapper.findById("root1")).thenReturn(user("root1", "root"));
        when(userMapper.findByRole("user")).thenReturn(List.of(
            user("u1", "user"),
            user("u2", "user")
        ));

        ResponseEntity<?> response = controller.listUsers(auth("root1"));

        assertEquals(200, response.getStatusCode().value());
        List<?> users = (List<?>) ((Map<?, ?>) response.getBody()).get("users");
        assertEquals(2, users.size());
        verify(supervisionMapper, never()).findByAdmin(anyString());
    }

    @Test
    void adminSeesOnlyApprovedSupervisedUsers() {
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));
        when(supervisionMapper.findByAdmin("admin1")).thenReturn(List.of(
            Map.of("userId", "u1", "username", "alice", "status", "approved"),
            Map.of("userId", "u2", "username", "bob", "status", "pending")
        ));

        ResponseEntity<?> response = controller.listUsers(auth("admin1"));

        assertEquals(200, response.getStatusCode().value());
        List<?> users = (List<?>) ((Map<?, ?>) response.getBody()).get("users");
        assertEquals(1, users.size());
        assertEquals("u1", ((Map<?, ?>) users.get(0)).get("id"));
        verify(userMapper, never()).findByRole(anyString());
    }

    @Test
    void userExamsRejectNonAdmin() {
        when(userMapper.findById("u1")).thenReturn(user("u1", "user"));

        ResponseEntity<?> response = controller.userExams("u2", auth("u1"));

        assertEquals(403, response.getStatusCode().value());
        verify(examMapper, never()).findByUserId(anyString());
    }

    @Test
    void rootCanReadUserExams() {
        when(userMapper.findById("root1")).thenReturn(user("root1", "root"));
        Exam exam = new Exam();
        exam.setId("e1");
        exam.setUserId("u2");
        when(examMapper.findByUserId("u2")).thenReturn(List.of(exam));

        ResponseEntity<?> response = controller.userExams("u2", auth("root1"));

        assertEquals(200, response.getStatusCode().value());
        verify(examMapper).findByUserId("u2");
    }

    @Test
    void userWrongQuestionsJoinsQuestionDetails() {
        WrongQuestion wq = new WrongQuestion();
        wq.setId("w1");
        wq.setQuestionId("q1");
        wq.setUserAnswer("A");
        wq.setMastered(false);
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of(wq));

        Question question = new Question();
        question.setId("q1");
        question.setContent("题干");
        question.setAnswer("B");
        question.setExplanation("解析");
        question.setType("choice");
        when(questionMapper.findById("q1")).thenReturn(question);

        ResponseEntity<?> response = controller.userWrongQuestions("u1", auth("admin1"));

        assertEquals(200, response.getStatusCode().value());
        List<?> items = (List<?>) ((Map<?, ?>) response.getBody()).get("wrongQuestions");
        assertEquals(1, items.size());
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        assertEquals("w1", item.get("id"));
        assertEquals("题干", item.get("content"));
        assertEquals("B", item.get("answer"));
    }

    @Test
    void userWrongQuestionsToleratesDeletedQuestion() {
        WrongQuestion wq = new WrongQuestion();
        wq.setId("w1");
        wq.setQuestionId("deleted");
        wq.setUserAnswer("A");
        wq.setMastered(false);
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));
        when(wrongQuestionMapper.findByUserId("u1")).thenReturn(List.of(wq));
        when(questionMapper.findById("deleted")).thenReturn(null);

        ResponseEntity<?> response = controller.userWrongQuestions("u1", auth("admin1"));

        assertEquals(200, response.getStatusCode().value());
        List<?> items = (List<?>) ((Map<?, ?>) response.getBody()).get("wrongQuestions");
        assertEquals(1, items.size());
        assertFalse(((Map<?, ?>) items.get(0)).containsKey("content"));
    }
}
