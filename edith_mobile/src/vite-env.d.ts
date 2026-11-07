/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_EDITH_AGENT_URL?: string;
  readonly VITE_EDITH_FACE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
