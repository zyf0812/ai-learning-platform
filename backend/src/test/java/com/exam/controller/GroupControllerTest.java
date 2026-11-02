package com.exam.controller;

import com.exam.mapper.GroupChatMapper;
import com.exam.mapper.GroupMapper;
import com.exam.mapper.UserMapper;
import com.exam.model.User;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 群组接口包含创建/审批/加入的权限边界，此前没有测试，越权回归风险高。
 */
@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

    @Mock
    private GroupMapper groupMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private GroupChatMapper chatMapper;

    private GroupController controller;

    @BeforeEach
    void setUp() {
        controller = new GroupController(groupMapper, userMapper, chatMapper);
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
    void createRejectsNonAdmin() {
        when(userMapper.findById("u1")).thenReturn(user("u1", "user"));

        ResponseEntity<?> response = controller.create(Map.of("name", "学习小组"), auth("u1"));

        assertEquals(403, response.getStatusCode().value());
        verify(groupMapper, never()).insertGroup(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createRejectsBlankName() {
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));

        ResponseEntity<?> response = controller.create(Map.of("name", "   "), auth("admin1"));

        assertEquals(400, response.getStatusCode().value());
        verify(groupMapper, never()).insertGroup(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createPersistsGroupForAdmin() {
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));

        ResponseEntity<?> response = controller.create(Map.of("name", " 学习小组 "), auth("admin1"));

        assertEquals(201, response.getStatusCode().value());
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        Map<?, ?> group = (Map<?, ?>) body.get("group");
        assertEquals("学习小组", group.get("name"));
        assertNotNull(group.get("code"));
        verify(groupMapper).insertGroup(anyString(), eq("学习小组"), anyString(), eq("admin1"));
    }

    @Test
    void approveRejectsNonOwner() {
        when(groupMapper.findById("g1")).thenReturn(Map.of("adminUserId", "owner"));

        ResponseEntity<?> response = controller.approve("g1", "m1", auth("u1"));

        assertEquals(403, response.getStatusCode().value());
        verify(groupMapper, never()).updateMemberStatus(anyString(), anyString());
    }

    @Test
    void approveAllowsGroupOwner() {
        when(groupMapper.findById("g1")).thenReturn(Map.of("adminUserId", "owner"));

        ResponseEntity<?> response = controller.approve("g1", "m1", auth("owner"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(true, ((Map<?, ?>) response.getBody()).get("success"));
        verify(groupMapper).updateMemberStatus("m1", "approved");
    }

    @Test
    void joinRejectsMissingCode() {
        ResponseEntity<?> response = controller.join(Map.of(), auth("u1"));

        assertEquals(400, response.getStatusCode().value());
        verifyNoInteractions(groupMapper);
    }

    @Test
    void joinRejectsUnknownCode() {
        when(groupMapper.findByCode("bad")).thenReturn(null);

        ResponseEntity<?> response = controller.join(Map.of("code", "bad"), auth("u1"));

        assertEquals(400, response.getStatusCode().value());
        assertTrue(((Map<?, ?>) response.getBody()).get("error").toString().contains("加入码错误"));
    }

    @Test
    void joinRejectsDuplicateApplication() {
        when(groupMapper.findByCode("code1")).thenReturn(Map.of("id", "g1"));
        when(groupMapper.findMember("g1", "u1")).thenReturn(Map.of("id", "m1"));

        ResponseEntity<?> response = controller.join(Map.of("code", "code1"), auth("u1"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("你已经申请过此群组", ((Map<?, ?>) response.getBody()).get("error"));
        verify(groupMapper, never()).insertMember(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void joinCreatesPendingMembership() {
        when(groupMapper.findByCode("code1")).thenReturn(Map.of("id", "g1"));
        when(groupMapper.findMember("g1", "u1")).thenReturn(null);

        ResponseEntity<?> response = controller.join(Map.of("code", "code1"), auth("u1"));

        assertEquals(200, response.getStatusCode().value());
        verify(groupMapper).insertMember(anyString(), eq("g1"), eq("u1"), eq("pending"));
    }

    @Test
    void disbandRejectsNonOwner() {
        when(groupMapper.findById("g1")).thenReturn(Map.of("adminUserId", "owner"));

        ResponseEntity<?> response = controller.disband("g1", auth("u1"));

        assertEquals(403, response.getStatusCode().value());
        verify(groupMapper, never()).deleteGroup(anyString());
    }
}
