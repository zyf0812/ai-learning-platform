const BASE = "";

export async function request(path: string, options: RequestInit = {}) {
  const token =
    typeof window !== "undefined" ? localStorage.getItem("token") : null;
  const headers: Record<string, string> = {
    ...((options.headers as Record<string, string>) || {}),
  };
  if (!(options.body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
  }
  if (token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(`${BASE}${path}`, { ...options, headers });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.error || "请求失败");
  return data;
}

export const api = {
  register: (body: { username: string; password: string; role?: string }) =>
    request("/api/auth/register", { method: "POST", body: JSON.stringify(body) }),
  login: (body: { username: string; password: string }) =>
    request("/api/auth/login", { method: "POST", body: JSON.stringify(body) }),
  me: () => request("/api/auth/me"),

  documents: {
    list: () => request("/api/documents"),
    upload: (formData: FormData) =>
      request("/api/documents", { method: "POST", body: formData }),
    get: (id: string) => request(`/api/documents/${id}`),
    delete: (id: string) => request(`/api/documents/${id}`, { method: "DELETE" }),
    knowledge: {
      list: (id: string) => request(`/api/documents/${id}/knowledge`),
      generate: (id: string) =>
        request(`/api/documents/${id}/knowledge`, { method: "POST" }),
    },
  },

  exams: {
    list: () => request("/api/exams"),
    get: (id: string) => request(`/api/exams/${id}`),
    delete: (id: string) => request(`/api/exams/${id}`, { method: "DELETE" }),
    generate: (body: { title: string; documentIds: string[]; types: string[]; count: number; [key: string]: any }) =>
      request("/api/exams/generate", { method: "POST", body: JSON.stringify(body) }),
    status: (id: string) => request(`/api/exams/${id}/status`),
    submit: (examId: string, answers: Record<string, string>) =>
      request(`/api/exams/${examId}/submit`, { method: "POST", body: JSON.stringify({ answers }) }),
  },

  attempts: {
    list: (params?: string | Record<string, string>) => {
      const query = typeof params === "string" ? params : params ? new URLSearchParams(params).toString() : "";
      return request(`/api/attempts${query ? "?" + query : ""}`);
    },
    get: (id: string) => request(`/api/attempts/${id}`),
  },

  conversations: {
    list: () => request("/api/conversations"),
    create: (body: { title: string }) =>
      request("/api/conversations", { method: "POST", body: JSON.stringify(body) }),
    get: (id: string) => request(`/api/conversations/${id}`),
    delete: (id: string) => request(`/api/conversations/${id}`, { method: "DELETE" }),
    messages: (id: string, limit: number, offset: number) =>
      request(`/api/conversations/${id}/messages?limit=${limit}&offset=${offset}`),
    send: (id: string, body: { content: string; documentId?: string }) =>
      request(`/api/conversations/${id}`, { method: "POST", body: JSON.stringify(body) }),
  },

  flashcards: {
    list: () => request("/api/flashcards"),
    review: (body: { cardId: string; quality: number }) =>
      request("/api/flashcards/review", { method: "POST", body: JSON.stringify(body) }),
  },

  wrongQuestions: {
    list: () => request("/api/wrong-questions"),
    update: (body: { id: string; mastered?: boolean }) =>
      request("/api/wrong-questions", { method: "PUT", body: JSON.stringify(body) }),
  },

  groups: {
    list: () => request("/api/groups"),
    create: (name: string) =>
      request("/api/groups", { method: "POST", body: JSON.stringify({ name }) }),
    join: (code: string) =>
      request("/api/groups/join", { method: "POST", body: JSON.stringify({ code }) }),
    get: (id: string) => request(`/api/groups/${id}`),
    delete: (id: string) => request(`/api/groups/${id}`, { method: "DELETE" }),
    chat: {
      list: (id: string) => request(`/api/groups/${id}/chat`),
      send: (id: string, content: string) =>
        request(`/api/groups/${id}/chat/send`, { method: "POST", body: JSON.stringify({ content }) }),
      upload: (id: string, formData: FormData) =>
        request(`/api/groups/${id}/chat/upload`, { method: "POST", body: formData }),
      notify: (id: string, body: { title: string; content: string; userIds: string[] }) =>
        request(`/api/groups/${id}/chat/notify`, { method: "POST", body: JSON.stringify(body) }),
      saveFile: (id: string, body: { fileName: string; fileUrl: string }) =>
        request(`/api/groups/${id}/chat/save-file`, { method: "POST", body: JSON.stringify(body) }),
    },
    approve: (id: string, memberId: string) =>
      request(`/api/groups/${id}/approve/${memberId}`, { method: "POST" }),
    reject: (id: string, memberId: string) =>
      request(`/api/groups/${id}/reject/${memberId}`, { method: "POST" }),
    remove: (id: string, memberId: string) =>
      request(`/api/groups/${id}/remove/${memberId}`, { method: "POST" }),
  },

  supervision: {
    code: () => request("/api/supervision/code"),
    users: () => request("/api/supervision/users"),
    join: (code: string) =>
      request("/api/supervision/join", { method: "POST", body: JSON.stringify({ code }) }),
    approve: (id: string) => request(`/api/supervision/approve/${id}`, { method: "POST" }),
    reject: (id: string) => request(`/api/supervision/reject/${id}`, { method: "POST" }),
    remove: (id: string) => request(`/api/supervision/remove/${id}`, { method: "POST" }),
  },

  admin: {
    users: () => request("/api/admin/users"),
    userExams: (userId: string) => request(`/api/admin/users/${userId}/exams`),
    userWrongQuestions: (userId: string) => request(`/api/admin/users/${userId}/wrong-questions`),
  },

  root: {
    pendingAdmins: () => request("/api/root/admins/pending"),
    users: () => request("/api/root/users"),
    approveAdmin: (id: string) => request(`/api/root/admins/${id}/approve`, { method: "POST" }),
    rejectAdmin: (id: string) => request(`/api/root/admins/${id}/reject`, { method: "POST" }),
    deleteUser: (id: string) => request(`/api/root/users/${id}`, { method: "DELETE" }),
    userDocuments: (userId: string) => request(`/api/root/users/${userId}/documents`),
    userExams: (userId: string) => request(`/api/root/users/${userId}/exams`),
    userChats: (userId: string) => request(`/api/root/users/${userId}/chats`),
  },

  notifications: {
    list: () => request("/api/notifications"),
    read: (id: string) => request(`/api/notifications/read/${id}`, { method: "POST" }),
    send: (body: { title: string; content: string; userIds: string[] }) =>
      request("/api/notifications/send", { method: "POST", body: JSON.stringify(body) }),
    broadcast: (body: { title: string; content: string }) =>
      request("/api/notifications/broadcast", { method: "POST", body: JSON.stringify(body) }),
  },

  stats: {
    all: () => request("/api/stats"),
    user: (userId: string) => request(`/api/stats?userId=${userId}`),
  },
};
