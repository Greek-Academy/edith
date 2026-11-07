/**
 * バックエンドのベースURL。edith/native/edith_glass の ApiConfig(Dart) に相当。
 * ビルド時の既定は Vite の環境変数(`VITE_EDITH_AGENT_URL`/`VITE_EDITH_FACE_URL`)で上書きでき、
 * 実行時はアプリ内の接続先設定シートで localStorage に保存した値が優先される。
 */
export interface ApiConfig {
  agentBaseUrl: string;
  faceBaseUrl: string;
}

const STORAGE_KEY = "edith.apiConfig";

const DEFAULT_AGENT_URL = import.meta.env.VITE_EDITH_AGENT_URL ?? "http://localhost:8010";
const DEFAULT_FACE_URL = import.meta.env.VITE_EDITH_FACE_URL ?? "http://localhost:8000";

export const defaultApiConfig: ApiConfig = {
  agentBaseUrl: DEFAULT_AGENT_URL,
  faceBaseUrl: DEFAULT_FACE_URL,
};

export function loadApiConfig(): ApiConfig {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { ...defaultApiConfig };
    const parsed = JSON.parse(raw) as Partial<ApiConfig>;
    return {
      agentBaseUrl: parsed.agentBaseUrl || DEFAULT_AGENT_URL,
      faceBaseUrl: parsed.faceBaseUrl || DEFAULT_FACE_URL,
    };
  } catch {
    return { ...defaultApiConfig };
  }
}

export function saveApiConfig(config: ApiConfig): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(config));
}
