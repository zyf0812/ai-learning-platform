package com.exam.controller;

import com.exam.mapper.GroupChatMapper;
import com.exam.mapper.GroupMapper;
import com.exam.mapper.UserMapper;
import com.exam.model.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class GroupControllerTest {

    @Mock private GroupMapper groupMapper;
    @Mock private UserMapper userMapper;
    @Mock private GroupChatMapper chatMapper;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void setUp() {
        GroupController controller = new GroupController(groupMapper, userMapper, chatMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder, String userId) {
        return builder.principal(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private User user(String id, String role) {
        User u = new User();
        u.setId(id);
        u.setRole(role);
        return u;
    }

    @Test
    void createRejectsNonAdmin() throws Exception {
        when(userMapper.findById("u1")).thenReturn(user("u1", "user"));

        mockMvc.perform(asUser(post("/api/groups"), "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"学习小组\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("仅管理员可创建群组"));

        verify(groupMapper, never()).insertGroup(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void createRejectsBlankName() throws Exception {
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));

        mockMvc.perform(asUser(post("/api/groups"), "admin1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请输入群名称"));
    }

    @Test
    void createByAdminSucceeds() throws Exception {
        when(userMapper.findById("admin1")).thenReturn(user("admin1", "admin"));

        mockMvc.perform(asUser(post("/api/groups"), "admin1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"学习小组\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.group.name").value("学习小组"));

        verify(groupMapper).insertGroup(anyString(), eq("学习小组"), anyString(), eq("admin1"));
    }

    @Test
    void approveRejectsNonOwner() throws Exception {
        Map<String, Object> group = new HashMap<>();
        group.put("id", "g1");
        group.put("adminUserId", "owner");
        when(groupMapper.findById("g1")).thenReturn(group);

        mockMvc.perform(asUser(post("/api/groups/g1/approve/m1"), "intruder"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("仅群主可审批"));

        verify(groupMapper, never()).updateMemberStatus(anyString(), anyString());
    }

    @Test
    void approveByOwnerUpdatesMember() throws Exception {
        Map<String, Object> group = new HashMap<>();
        group.put("id", "g1");
        group.put("adminUserId", "owner");
        when(groupMapper.findById("g1")).thenReturn(group);

        mockMvc.perform(asUser(post("/api/groups/g1/approve/m1"), "owner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(groupMapper).updateMemberStatus("m1", "approved");
    }

    @Test
    void joinRejectsDuplicateApplication() throws Exception {
        Map<String, Object> group = new HashMap<>();
        group.put("id", "g1");
        when(groupMapper.findByCode("12345678")).thenReturn(group);
        when(groupMapper.findMember("g1", "u1")).thenReturn(Map.of("id", "m1"));

        mockMvc.perform(asUser(post("/api/groups/join"), "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("code", "12345678"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("你已经申请过此群组"));

        verify(groupMapper, never()).insertMember(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void disbandRejectsNonOwner() throws Exception {
        Map<String, Object> group = new HashMap<>();
        group.put("id", "g1");
        group.put("adminUserId", "owner");
        when(groupMapper.findById("g1")).thenReturn(group);

        mockMvc.perform(asUser(delete("/api/groups/g1"), "intruder"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("仅群主可解散"));

        verify(groupMapper, never()).deleteGroup("g1");
    }
}
