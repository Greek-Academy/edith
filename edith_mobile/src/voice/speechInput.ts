/**
 * 端末の音声認識。Web Speech API（Android Chrome等）を優先し、未対応（主にiOS Safari）では
 * MediaRecorderで録音したBlobを呼び出し側がstt_service/voice_assistant_agentへ送るフォールバックに切替える。
 * edith/native/edith_glass の speech_to_text(Dart, iOSネイティブSTT) に相当する役割。
 */

type SpeechRecognitionCtor = new () => SpeechRecognitionLike;

interface SpeechRecognitionLike extends EventTarget {
  lang: string;
  interimResults: boolean;
  maxAlternatives: number;
  onresult: ((event: unknown) => void) | null;
  onend: (() => void) | null;
  onerror: (() => void) | null;
  start(): void;
  stop(): void;
}

function getRecognitionCtor(): SpeechRecognitionCtor | null {
  const w = window as unknown as Record<string, unknown>;
  return (w.SpeechRecognition as SpeechRecognitionCtor) ?? (w.webkitSpeechRecognition as SpeechRecognitionCtor) ?? null;
}

export class SpeechInput {
  private recognition: SpeechRecognitionLike | null = null;
  private mediaRecorder: MediaRecorder | null = null;
  private micStream: MediaStream | null = null;
  private chunks: Blob[] = [];
  listening = false;

  /** Web Speech APIが使える環境か（主にChrome系）。 */
  get nativeAvailable(): boolean {
    return getRecognitionCtor() !== null;
  }

  /** ネイティブ音声認識を開始する。確定結果が出たら[onResult]、終了したら[onEnd]を呼ぶ。 */
  startNative(onResult: (text: string) => void, onEnd: () => void): void {
    const Ctor = getRecognitionCtor();
    if (!Ctor) {
      onEnd();
      return;
    }
    const rec = new Ctor();
    rec.lang = "ja-JP";
    rec.interimResults = false;
    rec.maxAlternatives = 1;
    rec.onresult = (event) => {
      const e = event as { results: { length: number; [i: number]: { [i: number]: { transcript: string } } } };
      const text = e.results[e.results.length - 1]?.[0]?.transcript?.trim();
      if (text) onResult(text);
    };
    rec.onend = () => {
      this.listening = false;
      onEnd();
    };
    rec.onerror = () => {
      this.listening = false;
      onEnd();
    };
    this.recognition = rec;
    this.listening = true;
    rec.start();
  }

  stopNative(): void {
    this.recognition?.stop();
    this.recognition = null;
  }

  /** フォールバック: マイクを取得して録音を開始する。 */
  async startRecording(): Promise<void> {
    this.micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    this.chunks = [];
    this.mediaRecorder = new MediaRecorder(this.micStream, { mimeType: "audio/webm" });
    this.mediaRecorder.ondataavailable = (e) => {
      if (e.data.size > 0) this.chunks.push(e.data);
    };
    this.mediaRecorder.start();
    this.listening = true;
  }

  /** 録音を止め、収録した音声をBlobとして返す。 */
  stopRecording(): Promise<Blob> {
    return new Promise((resolve) => {
      const mr = this.mediaRecorder;
      if (!mr) {
        resolve(new Blob());
        return;
      }
      mr.onstop = () => {
        this.listening = false;
        this.micStream?.getTracks().forEach((t) => t.stop());
        this.micStream = null;
        resolve(new Blob(this.chunks, { type: "audio/webm" }));
      };
      mr.stop();
    });
  }
}
