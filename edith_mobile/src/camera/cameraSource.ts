/**
 * 「グラスのカメラフレーム」を供給する層（edith/native/edith_glass の FrameSource(Dart) に相当）。
 * edith_mobile（スマホ）では背面カメラ(`facingMode:'environment'`)をグラスのカメラとみなす。
 * edith_rokid（Rokidグラス）ではグラス側ブラウザ経路(AIUI Studio/Lumen的な仕組み)が
 * 通常カメラを1つしか公開しないため、facingMode指定なしで`getUserMedia`する
 * （[`init`]の`facingMode`省略時はブラウザ/環境が返す既定カメラをそのまま使う）。
 */
export interface CameraOption {
  deviceId: string;
  label: string;
}

export interface CameraInitOptions {
  /** 省略時は制約を付けず既定カメラを使う（グラス側で1カメラのみ公開される場合を想定）。 */
  facingMode?: "environment" | "user";
}

export class CameraSource {
  private stream: MediaStream | null = null;
  private _facingMode: "environment" | "user" | undefined;

  constructor(private readonly video: HTMLVideoElement) {}

  get isLive(): boolean {
    return this.stream !== null && this.video.readyState >= 2;
  }

  get aspectRatio(): number {
    const w = this.video.videoWidth;
    const h = this.video.videoHeight;
    return h > 0 ? w / h : 1;
  }

  /** 前面カメラか（プレビューのミラー既定値に使う）。不明な場合はfalse扱い。 */
  get isFront(): boolean {
    return this._facingMode === "user";
  }

  /**
   * カメラを起動する。[opts.facingMode]を指定すると、そのカメラを優先して要求する
   * （スマホの背面カメラ＝グラスのカメラとみなす場合は`'environment'`）。
   * 省略時は制約を付けずに既定カメラを要求する（グラス側ブラウザでカメラが1つしか無い場合向け）。
   */
  async init(opts: CameraInitOptions = { facingMode: "environment" }): Promise<void> {
    const video: MediaTrackConstraints = opts.facingMode ? { facingMode: { ideal: opts.facingMode } } : {};
    const stream = await navigator.mediaDevices.getUserMedia({ video, audio: false });
    this.attach(stream, opts.facingMode);
  }

  async listCameras(): Promise<CameraOption[]> {
    const devices = await navigator.mediaDevices.enumerateDevices();
    return devices
      .filter((d) => d.kind === "videoinput")
      .map((d, i) => ({ deviceId: d.deviceId, label: d.label || `カメラ ${i + 1}` }));
  }

  async switchTo(deviceId: string): Promise<void> {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: { deviceId: { exact: deviceId } },
      audio: false,
    });
    // 切替先がフロントかどうかはラベルから推測（取得できない場合は既定のまま）。
    const track = stream.getVideoTracks()[0];
    const settings = track.getSettings();
    const facing = (settings.facingMode as "environment" | "user" | undefined) ?? this._facingMode;
    this.attach(stream, facing);
  }

  private attach(stream: MediaStream, facingMode: "environment" | "user" | undefined): void {
    this.stop();
    this.stream = stream;
    this._facingMode = facingMode;
    this.video.srcObject = stream;
  }

  /** 現在のフレームをJPEG Blobとして取得する(識別・登録・検出に送る)。 */
  async capture(quality = 0.9): Promise<Blob> {
    const canvas = document.createElement("canvas");
    canvas.width = this.video.videoWidth;
    canvas.height = this.video.videoHeight;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("2d context を取得できませんでした");
    ctx.drawImage(this.video, 0, 0, canvas.width, canvas.height);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
    if (!blob) throw new Error("フレームのエンコードに失敗しました");
    return blob;
  }

  /** トラックの枠(正規化)から、余白付きで顔領域を切り出す。 */
  async captureCrop(box: { x: number; y: number; w: number; h: number }, margin = 0.4, quality = 0.9): Promise<Blob> {
    const vw = this.video.videoWidth;
    const vh = this.video.videoHeight;
    const mx = box.w * vw * margin;
    const my = box.h * vh * margin;
    const x = Math.max(0, Math.min(vw - 1, Math.round(box.x * vw - mx)));
    const y = Math.max(0, Math.min(vh - 1, Math.round(box.y * vh - my)));
    let w = Math.round(box.w * vw + mx * 2);
    let h = Math.round(box.h * vh + my * 2);
    if (x + w > vw) w = vw - x;
    if (y + h > vh) h = vh - y;
    if (w < 8 || h < 8) throw new Error("顔の切り出しに失敗しました（領域が小さすぎます）");

    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("2d context を取得できませんでした");
    ctx.drawImage(this.video, x, y, w, h, 0, 0, w, h);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
    if (!blob) throw new Error("切り出しのエンコードに失敗しました");
    return blob;
  }

  stop(): void {
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
  }
}
