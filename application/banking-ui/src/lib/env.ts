export const env = {
  apiUrl: import.meta.env.VITE_API_URL || "http://localhost:8080/api/v1",
  authMode: import.meta.env.VITE_AUTH_MODE === "keycloak" ? "keycloak" : "dev",
  // Opt-in only: a build with the variable missing must never serve fake data.
  useMocks: import.meta.env.VITE_USE_MOCKS === "true",
  keycloak: {
    url: import.meta.env.VITE_KEYCLOAK_URL,
    realm: import.meta.env.VITE_KEYCLOAK_REALM,
    clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID,
  },
} as const;
