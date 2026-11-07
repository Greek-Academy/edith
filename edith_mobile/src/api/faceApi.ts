import type { FaceBox } from "../hud/faceBox";

/** Face API の識別結果。 */
export interface IdentifyResult {
  name: string;
  similarity: number;
  info?: Record<string, unknown>;
  /** 別角度サンプルが自動学習（auto-enroll）で追加されたか。 */
  sampleAdded: boolean;
}

export interface DetectResult {
  width: number;
  height: number;
  faces: FaceBox[];
}

export const emptyDetectResult: DetectResult = { width: 0, height: 0, faces: [] };

/**
 * 顔の識別・登録クライアント。Face API(FastAPI, `/faces/*`)を直接呼ぶ。
 * edith/native/edith_glass の FaceApi(Dart) と同じAPI契約。
 */
export class FaceApi {
  constructor(private readonly baseUrl: string) {}

  /** 画像から人物を識別する。一致が無ければnull(404/422)。 */
  async identify(jpegBlob: Blob, opts: { autoEnroll?: boolean; source?: string } = {}): Promise<IdentifyResult | null> {
    const form = new FormData();
    form.append("auto_enroll", String(opts.autoEnroll ?? true));
    form.append("source", opts.source ?? "edith-mobile");
    form.append("image", jpegBlob, "frame.jpg");

    const res = await fetch(`${this.baseUrl}/faces/identify`, { method: "POST", body: form });
    // 404=一致なし / 422=顔が検出できない → どちらも「未識別(null)」として扱う。
    if (res.status === 404 || res.status === 422) return null;
    if (!res.ok) throw new Error(`identify failed: ${res.status} ${await res.text()}`);
    const j = await res.json();
    return {
      name: j.person.name as string,
      similarity: j.similarity as number,
      info: j.person.info as Record<string, unknown> | undefined,
      sampleAdded: j.sample_added === true,
    };
  }

  /** ライブ追跡用の高速な顔検出（枠のみ）。追跡ループを止めないためエラー時は空結果を返す。 */
  async detect(jpegBlob: Blob): Promise<DetectResult> {
    try {
      const form = new FormData();
      form.append("image", jpegBlob, "frame.jpg");
      const res = await fetch(`${this.baseUrl}/faces/detect`, { method: "POST", body: form });
      if (!res.ok) return emptyDetectResult;
      const j = await res.json();
      const faces: FaceBox[] = (j.faces ?? []).map((f: Record<string, number>) => ({
        x: f.x,
        y: f.y,
        w: f.w,
        h: f.h,
        score: f.score ?? 0,
      }));
      return { width: j.width, height: j.height, faces };
    } catch {
      return emptyDetectResult;
    }
  }

  /** 画像と名前で人物を登録する（register_personの実処理）。 */
  async register(jpegBlob: Blob, name: string): Promise<string> {
    const form = new FormData();
    form.append("name", name);
    form.append("image", jpegBlob, "frame.jpg");
    const res = await fetch(`${this.baseUrl}/faces/register`, { method: "POST", body: form });
    if (!res.ok) throw new Error(`register failed: ${res.status} ${await res.text()}`);
    const j = await res.json();
    return j.id as string;
  }
}
