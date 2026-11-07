package com.exam.controller;

import com.exam.mapper.GroupChatMapper;
import com.exam.mapper.GroupMapper;
import com.exam.mapper.UserMapper;
import com.exam.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class GroupControllerPermissionTest {

    @Mock
    private GroupMapper groupMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private GroupChatMapper chatMapper;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GroupController controller = new GroupController(groupMapper, userMapper, chatMapper);
        ReflectionTestUtils.setField(controller, "uploadPath", "target/test-groups-no-such-dir");
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void createRejectsNonAdmin() throws Exception {
        when(userMapper.findById("u1")).thenReturn(user("u1", "user"));

        mockMvc.perform(post("/api/groups")
                .principal(auth("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Study\"}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("仅管理员可创建群组"));

        verify(groupMapper, never()).insertGroup(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createAllowsAdminAndGeneratesJoinCode() throws Exception {
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));

        mockMvc.perform(post("/api/groups")
                .principal(auth("admin1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"  Study  \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.group.name").value("Study"));

        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(groupMapper).insertGroup(anyString(), eq("Study"), code.capture(), eq("admin1"));
        assertTrue(code.getValue().matches("\\d{8}"));
    }

    @Test
    void createRejectsBlankName() throws Exception {
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));

        mockMvc.perform(post("/api/groups")
                .principal(auth("admin1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"   \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("请输入群名称"));

        verify(groupMapper, never()).insertGroup(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void approveRejectsNonOwner() throws Exception {
        when(groupMapper.findById("g1")).thenReturn(group("g1", "owner"));

        mockMvc.perform(post("/api/groups/g1/approve/m1").principal(auth("intruder")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("仅群主可审批"));

        verify(groupMapper, never()).updateMemberStatus(anyString(), anyString());
    }

    @Test
    void approveAllowsOwner() throws Exception {
        when(groupMapper.findById("g1")).thenReturn(group("g1", "owner"));

        mockMvc.perform(post("/api/groups/g1/approve/m1").principal(auth("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        verify(groupMapper).updateMemberStatus("m1", "approved");
    }

    @Test
    void rejectAndRemoveAreOwnerOnly() throws Exception {
        when(groupMapper.findById("g1")).thenReturn(group("g1", "owner"));

        mockMvc.perform(post("/api/groups/g1/reject/m1").principal(auth("intruder")))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/groups/g1/remove/m1").principal(auth("intruder")))
            .andExpect(status().isForbidden());

        verify(groupMapper, never()).updateMemberStatus(anyString(), anyString());
        verify(groupMapper, never()).deleteMember(anyString());
    }

    @Test
    void joinRejectsDuplicateApplication() throws Exception {
        when(groupMapper.findByCode("12345678")).thenReturn(group("g1", "owner"));
        when(groupMapper.findMember("g1", "u1")).thenReturn(Map.of("id", "m1"));

        mockMvc.perform(post("/api/groups/join")
                .principal(auth("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"12345678\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("你已经申请过此群组"));

        verify(groupMapper, never()).insertMember(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void joinCreatesPendingMembership() throws Exception {
        when(groupMapper.findByCode("12345678")).thenReturn(group("g1", "owner"));
        when(groupMapper.findMember("g1", "u1")).thenReturn(null);

        mockMvc.perform(post("/api/groups/join")
                .principal(auth("u1"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"12345678\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("申请已提交，等待管理员审批"));

        verify(groupMapper).insertMember(anyString(), eq("g1"), eq("u1"), eq("pending"));
    }

    @Test
    void disbandRejectsNonOwner() throws Exception {
        when(groupMapper.findById("g1")).thenReturn(group("g1", "owner"));

        mockMvc.perform(delete("/api/groups/g1").principal(auth("intruder")))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("仅群主可解散"));

        verify(groupMapper, never()).deleteGroup(anyString());
    }

    @Test
    void disbandDeletesOwnersGroup() throws Exception {
        when(groupMapper.findById("g1")).thenReturn(group("g1", "owner"));

        mockMvc.perform(delete("/api/groups/g1").principal(auth("owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        verify(groupMapper).deleteGroup("g1");
    }

    private static Authentication auth(String username) {
        return new UsernamePasswordAuthenticationToken(username, null, List.of());
    }

    private static User user(String id, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(id);
        user.setRole(role);
        return user;
    }

    private static Map<String, Object> group(String id, String adminUserId) {
        Map<String, Object> group = new HashMap<>();
        group.put("id", id);
        group.put("adminUserId", adminUserId);
        return group;
    }
}
