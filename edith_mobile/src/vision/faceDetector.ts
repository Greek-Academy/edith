import { FaceDetector, FilesetResolver } from "@mediapipe/tasks-vision";
import type { FaceBox } from "../hud/faceBox";

// jsDelivr配信のWASMと、Google公式ホストのモデル。edith/native/edith_glass の
// client_face_detector_web.dart(MediaPipe)と同じ考え方（往復なしでクライアント内完結）。
const WASM_BASE = "https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.17/wasm";
const MODEL_URL =
  "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/1/blaze_face_short_range.tflite";

/** クライアント側（端末内）の顔検出。往復なしで滑らかに追従するために使う。 */
export class ClientFaceDetector {
  private detector: FaceDetector | null = null;

  async init(): Promise<void> {
    const fileset = await FilesetResolver.forVisionTasks(WASM_BASE);
    this.detector = await FaceDetector.createFromOptions(fileset, {
      baseOptions: { modelAssetPath: MODEL_URL },
      runningMode: "VIDEO",
    });
  }

  /** 1フレームを検出する。[timestampMs]は単調増加している必要がある(MediaPipeの制約)。 */
  detect(video: HTMLVideoElement, timestampMs: number): FaceBox[] {
    if (!this.detector || video.videoWidth === 0) return [];
    const result = this.detector.detectForVideo(video, timestampMs);
    const vw = video.videoWidth;
    const vh = video.videoHeight;
    return result.detections
      .filter((d) => d.boundingBox)
      .map((d) => {
        const bb = d.boundingBox!;
        return {
          x: bb.originX / vw,
          y: bb.originY / vh,
          w: bb.width / vw,
          h: bb.height / vh,
          score: d.categories?.[0]?.score ?? 0,
        };
      });
  }

  dispose(): void {
    this.detector?.close();
    this.detector = null;
  }
}
