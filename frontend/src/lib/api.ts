const BASE = "";

async function request(path: string, options: RequestInit = {}) {
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
  const data = await res.json();
  if (!res.ok) throw new Error(data.error || "请求失败");
  return data;
}

export const api = {
  register: (body: { username: string; password: string; role?: string }) =>
    request("/api/auth/register", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  login: (body: { username: string; password: string }) =>
    request("/api/auth/login", {
      method: "POST",
      body: JSON.stringify(body),
    }),
  me: () => request("/api/auth/me"),

  documents: {
    list: () => request("/api/documents"),
    upload: (formData: FormData) => {
      const token = localStorage.getItem("token");
      return fetch("/api/documents", {
        method: "POST",
        headers: token
          ? { Authorization: `Bearer ${token}` }
          : {},
        body: formData,
      })
        .then((r) => r.json())
        .then((d) => {
          if (!d.document) throw new Error(d.error);
          return d;
        });
    },
    get: (id: string) => request(`/api/documents/${id}`),
    delete: (id: string) =>
      request(`/api/documents/${id}`, { method: "DELETE" }),
  },

  exams: {
    list: () => request("/api/exams"),
    get: (id: string) => request(`/api/exams/${id}`),
    delete: (id: string) =>
      request(`/api/exams/${id}`, { method: "DELETE" }),
    generate: (body: {
      title: string;
      documentIds: string[];
      types: string[];
      count: number;
    }) =>
      request("/api/exams/generate", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    submit: (examId: string, answers: Record<string, string>) =>
      request(`/api/exams/${examId}/submit`, {
        method: "POST",
        body: JSON.stringify({ answers }),
      }),
  },

  attempts: {
    get: (id: string) => request(`/api/attempts/${id}`),
  },
};
