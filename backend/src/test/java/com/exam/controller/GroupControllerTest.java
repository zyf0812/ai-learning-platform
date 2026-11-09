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
import org.springframework.security.core.Authentication;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

    @Mock private GroupMapper groupMapper;
    @Mock private UserMapper userMapper;
    @Mock private GroupChatMapper chatMapper;
    @Mock private Authentication auth;

    private GroupController controller;

    @BeforeEach
    void setUp() {
        controller = new GroupController(groupMapper, userMapper, chatMapper);
    }

    private static User user(String role) {
        User user = new User();
        user.setId("u1");
        user.setRole(role);
        return user;
    }

    @Test
    void regularUserCannotCreateGroup() {
        when(auth.getName()).thenReturn("u1");
        when(userMapper.findById("u1")).thenReturn(user("user"));

        ResponseEntity<?> response = controller.create(Map.of("name", "Study"), auth);

        assertEquals(403, response.getStatusCode().value());
        assertEquals("仅管理员可创建群组", ((Map<?, ?>) response.getBody()).get("error"));
        verify(groupMapper, never()).insertGroup(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unknownUserCannotCreateGroup() {
        when(auth.getName()).thenReturn("ghost");
        when(userMapper.findById("ghost")).thenReturn(null);

        ResponseEntity<?> response = controller.create(Map.of("name", "Study"), auth);

        assertEquals(403, response.getStatusCode().value());
        verify(groupMapper, never()).insertGroup(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void adminCreatesGroupWithTrimmedName() {
        when(auth.getName()).thenReturn("admin1");
        when(userMapper.findById("admin1")).thenReturn(user("admin"));

        ResponseEntity<?> response = controller.create(Map.of("name", "  Study  "), auth);

        assertEquals(201, response.getStatusCode().value());
        Map<?, ?> group = (Map<?, ?>) ((Map<?, ?>) response.getBody()).get("group");
        assertEquals("Study", group.get("name"));
        assertNotNull(group.get("code"));
        verify(groupMapper).insertGroup(anyString(), eq("Study"), anyString(), eq("admin1"));
    }

    @Test
    void blankGroupNameIsRejected() {
        when(auth.getName()).thenReturn("admin1");
        when(userMapper.findById("admin1")).thenReturn(user("admin"));

        ResponseEntity<?> response = controller.create(Map.of("name", "   "), auth);

        assertEquals(400, response.getStatusCode().value());
        verify(groupMapper, never()).insertGroup(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void joinWithUnknownCodeIsRejected() {
        when(groupMapper.findByCode("00000000")).thenReturn(null);

        ResponseEntity<?> response = controller.join(Map.of("code", "00000000"), auth);

        assertEquals(400, response.getStatusCode().value());
        verify(groupMapper, never()).insertMember(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void duplicateJoinRequestIsRejected() {
        when(auth.getName()).thenReturn("u1");
        when(groupMapper.findByCode("12345678")).thenReturn(Map.of("id", "g1"));
        when(groupMapper.findMember("g1", "u1")).thenReturn(Map.of("id", "m1"));

        ResponseEntity<?> response = controller.join(Map.of("code", "12345678"), auth);

        assertEquals(400, response.getStatusCode().value());
        verify(groupMapper, never()).insertMember(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void nonOwnerCannotApproveMember() {
        when(auth.getName()).thenReturn("intruder");
        when(groupMapper.findById("g1")).thenReturn(Map.of("id", "g1", "adminUserId", "owner"));

        ResponseEntity<?> response = controller.approve("g1", "m1", auth);

        assertEquals(403, response.getStatusCode().value());
        verify(groupMapper, never()).updateMemberStatus(anyString(), anyString());
    }

    @Test
    void ownerCanApproveMember() {
        when(auth.getName()).thenReturn("owner");
        when(groupMapper.findById("g1")).thenReturn(Map.of("id", "g1", "adminUserId", "owner"));

        ResponseEntity<?> response = controller.approve("g1", "m1", auth);

        assertEquals(200, response.getStatusCode().value());
        verify(groupMapper).updateMemberStatus("m1", "approved");
    }

    @Test
    void nonOwnerCannotRemoveMember() {
        when(auth.getName()).thenReturn("intruder");
        when(groupMapper.findById("g1")).thenReturn(Map.of("id", "g1", "adminUserId", "owner"));

        ResponseEntity<?> response = controller.remove("g1", "m1", auth);

        assertEquals(403, response.getStatusCode().value());
        verify(groupMapper, never()).deleteMember(anyString());
    }
}
