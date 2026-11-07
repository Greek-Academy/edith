/** Agentがクライアントに要求するデバイス操作（capture_face等, PRD §20）。 */
export interface AgentAction {
  id: string;
  type: string;
  payload: Record<string, unknown>;
}

export interface AgentReply {
  sessionId: string;
  message: string;
  actions: AgentAction[];
  transcript?: string;
}

/**
 * Voice Agent (`/v1/agent/*`) クライアント。
 * edith/native/edith_glass の VoiceAgentApi(Dart) と同じAPI契約。
 */
export class VoiceAgentApi {
  sessionId: string | null = null;

  constructor(private readonly baseUrl: string) {}

  /** テキスト指示を送る（PRD §8.1）。 */
  async sendText(content: string): Promise<AgentReply> {
    const res = await fetch(`${this.baseUrl}/v1/agent/input`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sessionId: this.sessionId, type: "text", content }),
    });
    if (!res.ok) throw new Error(`agent input failed: ${res.status} ${await res.text()}`);
    return this.parse(await res.json());
  }

  /** 音声を送る（PRD §8/§12, Push-to-Talk）。STTはサーバ側でローカル実行。 */
  async sendAudio(bytes: Blob, filename = "speech.webm"): Promise<AgentReply> {
    const form = new FormData();
    form.append("language", "ja");
    if (this.sessionId) form.append("sessionId", this.sessionId);
    form.append("audio", bytes, filename);
    const res = await fetch(`${this.baseUrl}/v1/agent/audio`, { method: "POST", body: form });
    if (!res.ok) throw new Error(`agent audio failed: ${res.status} ${await res.text()}`);
    return this.parse(await res.json());
  }

  /** クライアントが実行したActionの結果を返す（PRD §8）。 */
  async reportActionResult(requestId: string, result: Record<string, unknown>): Promise<void> {
    await fetch(`${this.baseUrl}/v1/agent/actions/${requestId}/result`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(result),
    });
  }

  private parse(j: Record<string, unknown>): AgentReply {
    this.sessionId = (j.sessionId as string | undefined) ?? this.sessionId;
    const actions = ((j.actions as unknown[] | undefined) ?? []).map((a) => {
      const obj = a as Record<string, unknown>;
      return {
        id: obj.id as string,
        type: obj.type as string,
        payload: (obj.payload as Record<string, unknown> | undefined) ?? {},
      };
    });
    return {
      sessionId: this.sessionId ?? "",
      message: (j.message as string | undefined) ?? "",
      actions,
      transcript: j.transcript as string | undefined,
    };
  }
}
