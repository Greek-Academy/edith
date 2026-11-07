import { FaceApi } from "./api/faceApi";
import { VoiceAgentApi, type AgentAction } from "./api/voiceAgentApi";
import { CameraSource, type CameraInitOptions, type CameraOption } from "./camera/cameraSource";
import { defaultApiConfig, loadApiConfig, saveApiConfig, type ApiConfig } from "./config/apiConfig";
import { drawHud } from "./hud/hudCanvas";
import type { HudState } from "./hud/hudState";
import { Track, TrackManager } from "./tracking/trackManager";
import { ClientFaceDetector } from "./vision/faceDetector";
import { SpeechInput } from "./voice/speechInput";

/** 確定済みトラックの再識別/学習の間隔（ms）。未確定トラックは毎回試す。 */
const RELEARN_MS = 4000;
/** 識別パスを回す間隔（ms）。枠自体は毎フレーム(rAF)更新、名前はこの間隔でサーバ照会する。 */
const IDENTIFY_INTERVAL_MS = 1200;

/**
 * デバイスごとのカメラ取得挙動。edith_mobile(スマホ)/edith_rokid(グラス)で
 * アプリ本体(App)を共有しつつ、ここだけ差し替える（[`../edith_rokid`]参照）。
 */
export interface AppOptions {
  /** 省略時はfacingMode制約なしで既定カメラを使う（グラス側ブラウザ向け）。 */
  cameraFacingMode?: CameraInitOptions["facingMode"];
}

/**
 * E.D.I.T.H Webクライアントのメイン画面。edith_mobile(スマホ)/edith_rokid(Rokidグラス)の
 * 両方から共有される（カメラ取得の既定値だけ[AppOptions]で差し替え可能）。
 * edith/native/edith_glass の home_page.dart(_HomePageState) をWeb向けに移植したもの。
 */
export class App {
  private readonly options: AppOptions;
  private config: ApiConfig = loadApiConfig();
  private faceApi = new FaceApi(this.config.faceBaseUrl || defaultApiConfig.faceBaseUrl);
  private agentApi = new VoiceAgentApi(this.config.agentBaseUrl || defaultApiConfig.agentBaseUrl);

  private readonly video = el<HTMLVideoElement>("camera-video");
  private readonly canvas = el<HTMLCanvasElement>("hud-canvas");
  private readonly camera = new CameraSource(this.video);
  private readonly detector = new ClientFaceDetector();
  private readonly tracks = new TrackManager();
  private readonly speech = new SpeechInput();

  private mirror = false;
  private auto = true;
  private autoLearn = true;
  private identifyingPass = false;
  private identifyTimer: ReturnType<typeof setInterval> | undefined;
  private logLines: string[] = [];
  private cameraOptions: CameraOption[] = [];

  constructor(options: AppOptions = {}) {
    this.options = options;
  }

  async start(): Promise<void> {
    this.bindUi();
    window.addEventListener("resize", () => this.resizeCanvas());
    try {
      await this.camera.init(this.options.cameraFacingMode ? { facingMode: this.options.cameraFacingMode } : {});
      this.mirror = this.camera.isFront;
      el<HTMLInputElement>("toggle-mirror").checked = this.mirror;
      await this.video.play().catch(() => undefined);
      this.addLog(this.options.cameraFacingMode === "environment" ? "📷 カメラ起動（背面＝グラスカメラ）" : "📷 カメラ起動");
      await this.detector.init();
      this.resizeCanvas();
      requestAnimationFrame((t) => this.renderLoop(t));
      this.startAuto();
      void this.loadCameraOptions();
    } catch (e) {
      this.showPermissionHint();
      this.addLog(`⚠️ カメラ初期化に失敗: ${(e as Error).message}`);
    }
  }

  // --- レンダリングループ（毎フレーム: 検出 + 描画） ---

  private resizeCanvas(): void {
    const dpr = window.devicePixelRatio || 1;
    const rect = this.canvas.getBoundingClientRect();
    this.canvas.width = Math.round(rect.width * dpr);
    this.canvas.height = Math.round(rect.height * dpr);
  }

  private renderLoop(timestampMs: number): void {
    if (this.camera.isLive && this.video.videoWidth > 0) {
      const raw = this.detector.detect(this.video, timestampMs);
      this.tracks.ingest(raw);
    }
    const faces = this.tracks.tick();
    const state = this.computeHudState();
    drawHud(this.canvas, {
      state,
      faces,
      mirror: this.mirror,
      videoWidth: this.video.videoWidth,
      videoHeight: this.video.videoHeight,
    });
    requestAnimationFrame((t) => this.renderLoop(t));
  }

  private computeHudState(): HudState {
    const total = this.tracks.tracks.length;
    const named = this.tracks.tracks.filter((t) => t.name !== undefined).length;
    if (total === 0) return { status: "SCANNING", locked: false };
    if (named === 0) return { status: "DETECTED", locked: false };
    if (total === 1) return { status: "TARGET LOCKED", name: this.tracks.tracks[0].name, locked: true };
    return { status: `${named} / ${total} 人 認識`, locked: true };
  }

  // --- 自動識別パス ---

  private startAuto(): void {
    this.stopAuto();
    this.identifyTimer = setInterval(() => void this.runIdentifyPass(), IDENTIFY_INTERVAL_MS);
  }

  private stopAuto(): void {
    if (this.identifyTimer !== undefined) clearInterval(this.identifyTimer);
    this.identifyTimer = undefined;
  }

  /** 名前が必要な各トラックの顔を切り出して個別に識別する。home_page.dart の _identifyPass 相当。 */
  private async runIdentifyPass(): Promise<void> {
    if (!this.auto || this.identifyingPass || this.tracks.tracks.length === 0 || !this.camera.isLive) return;
    this.identifyingPass = true;
    try {
      const now = Date.now();
      for (const t of [...this.tracks.tracks]) {
        if (t.identifying) continue;
        if (t.name !== undefined && t.lastId !== undefined && now - t.lastId < RELEARN_MS) continue;
        await this.identifyTrack(t, now);
      }
    } finally {
      this.identifyingPass = false;
    }
  }

  private async identifyTrack(t: Track, now: number): Promise<void> {
    t.identifying = true;
    t.lastId = now;
    try {
      const crop = await this.camera.captureCrop(t.box).catch(() => null);
      if (!crop) return;
      const r = await this.faceApi.identify(crop, { autoEnroll: this.autoLearn });
      if (r === null) {
        if (t.name !== undefined && this.autoLearn) {
          try {
            await this.faceApi.register(crop, t.name);
            this.addLog(`📚 苦手な角度を学習: ${t.name}`);
          } catch {
            /* 追跡ループは止めない */
          }
        }
        return;
      }
      if (r.sampleAdded) this.addLog(`📚 角度サンプルを自動学習: ${r.name}`);
      if (t.name === undefined) {
        t.name = r.name;
        this.addLog(`識別: ${r.name} (${Math.round(r.similarity * 100)}%)`);
      }
    } catch {
      /* 追跡ループは止めない */
    } finally {
      t.identifying = false;
    }
  }

  /** 「今すぐ識別」ボタン。home_page.dart の _identify 相当。 */
  private async manualIdentify(): Promise<void> {
    this.setBusy(true);
    try {
      if (this.tracks.tracks.length === 0) {
        if (!this.camera.isLive) {
          this.addLog("フレームを取得できませんでした");
          return;
        }
        const bytes = await this.camera.capture();
        const r = await this.faceApi.identify(bytes, { autoEnroll: this.autoLearn });
        this.addLog(r === null ? "識別: 一致なし" : `識別: ${r.name}`);
      } else {
        await this.runIdentifyPass();
      }
    } catch (e) {
      this.addLog(`エラー: ${(e as Error).message}`);
    } finally {
      this.setBusy(false);
    }
  }

  // --- テキスト/音声指示 ---

  private async send(text: string): Promise<void> {
    const content = text.trim();
    if (!content) return;
    el<HTMLInputElement>("text-input").value = "";
    this.addLog(`🗣 ${content}`);
    this.setBusy(true);
    try {
      const reply = await this.agentApi.sendText(content);
      await this.handleReply(reply.message, reply.actions);
    } catch (e) {
      this.addLog(`エラー: ${(e as Error).message}`);
    } finally {
      this.setBusy(false);
    }
  }

  private async sendAudio(blob: Blob): Promise<void> {
    this.setBusy(true);
    try {
      const reply = await this.agentApi.sendAudio(blob);
      if (reply.transcript) this.addLog(`🗣 ${reply.transcript}`);
      await this.handleReply(reply.message, reply.actions);
    } catch (e) {
      this.addLog(`エラー: ${(e as Error).message}`);
    } finally {
      this.setBusy(false);
    }
  }

  private async handleReply(message: string, actions: AgentAction[]): Promise<void> {
    this.addLog(`🤖 ${message}`);
    for (const action of actions) {
      if (action.type === "capture_face") {
        const name = (action.payload.name as string | undefined) ?? "";
        this.addLog(`📷 capture_face → ${name} を登録中…`);
        if (!this.camera.isLive) {
          this.addLog("フレームを取得できませんでした");
          continue;
        }
        const bytes = await this.camera.capture();
        const personId = await this.faceApi.register(bytes, name);
        await this.agentApi.reportActionResult(action.id, { success: true, personId });
        this.addLog(`✅ 登録完了: ${name} (${personId})`);
      } else if (action.type === "capture_labeled_face") {
        const marker = (action.payload.marker as string | undefined) ?? "";
        const name = (action.payload.name as string | undefined) ?? "";
        try {
          const ok = await this.registerLabeled(marker, name);
          await this.agentApi.reportActionResult(action.id, { success: ok, marker, name });
          this.addLog(ok ? `✅ 「${marker}」→ ${name} として登録` : `⚠️ 識別子「${marker}」の顔が見つかりません`);
        } catch (e) {
          await this.agentApi.reportActionResult(action.id, { success: false, marker });
          this.addLog(`⚠️ 登録に失敗: ${(e as Error).message}`);
        }
      }
    }
  }

  /** 識別子(A,B..)のトラックの顔を切り出し、指定名で登録して以後identifiedにする。 */
  private async registerLabeled(marker: string, name: string): Promise<boolean> {
    if (!name) return false;
    let track = this.tracks.findByMarker(marker);
    if (!track) {
      const unnamed = this.tracks.unnamedTracks();
      if (unnamed.length === 1) {
        track = unnamed[0];
        this.addLog(`（識別子「${marker}」は未検出だが未識別の顔が1つなのでそれを使用）`);
      }
    }
    if (!this.camera.isLive) throw new Error("フレームを取得できませんでした");

    if (!track || this.tracks.tracks.length <= 1) {
      const bytes = await this.camera.capture();
      await this.faceApi.register(bytes, name);
      if (track) track.name = name;
      return true;
    }

    try {
      const crop = await this.camera.captureCrop(track.box);
      await this.faceApi.register(crop, name);
    } catch {
      const bytes = await this.camera.capture();
      await this.faceApi.register(bytes, name);
    }
    track.name = name;
    return true;
  }

  // --- 音声入力 ---

  private async toggleMic(): Promise<void> {
    const micBtn = el<HTMLButtonElement>("btn-mic");
    if (this.speech.listening) {
      if (this.speech.nativeAvailable) {
        this.speech.stopNative();
      } else {
        const blob = await this.speech.stopRecording();
        micBtn.classList.remove("listening");
        await this.sendAudio(blob);
      }
      return;
    }
    if (this.speech.nativeAvailable) {
      micBtn.classList.add("listening");
      this.speech.startNative(
        (text) => void this.send(text),
        () => micBtn.classList.remove("listening"),
      );
    } else {
      try {
        await this.speech.startRecording();
        micBtn.classList.add("listening");
      } catch (e) {
        this.addLog(`⚠️ マイクを使用できません: ${(e as Error).message}`);
      }
    }
  }

  // --- カメラ切替 ---

  private async loadCameraOptions(): Promise<void> {
    this.cameraOptions = await this.camera.listCameras();
    const select = el<HTMLSelectElement>("camera-select");
    select.innerHTML = "";
    for (const opt of this.cameraOptions) {
      const o = document.createElement("option");
      o.value = opt.deviceId;
      o.textContent = opt.label;
      select.appendChild(o);
    }
    el("camera-picker-row").hidden = this.cameraOptions.length <= 1;
  }

  private async switchCamera(deviceId: string): Promise<void> {
    this.tracks.clear();
    try {
      await this.camera.switchTo(deviceId);
      this.mirror = this.camera.isFront;
      el<HTMLInputElement>("toggle-mirror").checked = this.mirror;
      this.addLog("📷 カメラを切替えました");
      void this.loadCameraOptions();
    } catch (e) {
      this.addLog(`⚠️ カメラの切替に失敗しました: ${(e as Error).message}`);
    }
  }

  // --- UI配線 ---

  private bindUi(): void {
    el<HTMLButtonElement>("btn-send").addEventListener("click", () => void this.send(el<HTMLInputElement>("text-input").value));
    el<HTMLInputElement>("text-input").addEventListener("keydown", (e) => {
      if (e.key === "Enter") void this.send(el<HTMLInputElement>("text-input").value);
    });
    el<HTMLButtonElement>("btn-identify").addEventListener("click", () => void this.manualIdentify());
    el<HTMLButtonElement>("btn-mic").addEventListener("click", () => void this.toggleMic());

    el<HTMLButtonElement>("btn-connection").addEventListener("click", () => this.openConnectionSheet());
    el<HTMLButtonElement>("btn-connection-save").addEventListener("click", () => this.saveConnectionSheet());
    el<HTMLButtonElement>("btn-connection-cancel").addEventListener("click", () => this.closeSheet("sheet-connection"));

    el<HTMLButtonElement>("btn-controls").addEventListener("click", () => this.openControlsSheet());
    el<HTMLButtonElement>("btn-controls-close").addEventListener("click", () => this.closeSheet("sheet-controls"));
    el<HTMLInputElement>("toggle-auto").addEventListener("change", (e) => {
      this.auto = (e.target as HTMLInputElement).checked;
      if (this.auto) this.startAuto();
      else this.stopAuto();
    });
    el<HTMLInputElement>("toggle-autolearn").addEventListener("change", (e) => {
      this.autoLearn = (e.target as HTMLInputElement).checked;
    });
    el<HTMLInputElement>("toggle-mirror").addEventListener("change", (e) => {
      this.mirror = (e.target as HTMLInputElement).checked;
    });
    el<HTMLSelectElement>("camera-select").addEventListener("change", (e) => {
      void this.switchCamera((e.target as HTMLSelectElement).value);
    });
  }

  private openConnectionSheet(): void {
    el<HTMLInputElement>("input-agent-url").value = this.config.agentBaseUrl;
    el<HTMLInputElement>("input-face-url").value = this.config.faceBaseUrl;
    el("sheet-connection").hidden = false;
  }

  private saveConnectionSheet(): void {
    const agentBaseUrl = el<HTMLInputElement>("input-agent-url").value.trim() || defaultApiConfig.agentBaseUrl;
    const faceBaseUrl = el<HTMLInputElement>("input-face-url").value.trim() || defaultApiConfig.faceBaseUrl;
    this.config = { agentBaseUrl, faceBaseUrl };
    saveApiConfig(this.config);
    this.faceApi = new FaceApi(faceBaseUrl);
    this.agentApi = new VoiceAgentApi(agentBaseUrl);
    this.addLog(`🔧 接続先を更新: agent=${agentBaseUrl} face=${faceBaseUrl}`);
    this.closeSheet("sheet-connection");
  }

  private openControlsSheet(): void {
    el("sheet-controls").hidden = false;
  }

  private closeSheet(id: string): void {
    el(id).hidden = true;
  }

  private showPermissionHint(): void {
    el("permission-hint").hidden = false;
  }

  private setBusy(busy: boolean): void {
    el<HTMLButtonElement>("btn-send").disabled = busy;
    el<HTMLButtonElement>("btn-identify").disabled = busy;
  }

  private addLog(line: string): void {
    this.logLines.unshift(line);
    this.logLines = this.logLines.slice(0, 20);
    el("log").textContent = this.logLines.slice(0, 3).join("\n");
  }
}

function el<T extends HTMLElement = HTMLElement>(id: string): T {
  const found = document.getElementById(id);
  if (!found) throw new Error(`#${id} が見つかりません`);
  return found as T;
}
