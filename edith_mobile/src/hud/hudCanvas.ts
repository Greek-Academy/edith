import type { FaceBox } from "./faceBox";
import type { HudState } from "./hudState";

const CYAN = "#00e5ff";
const GREEN = "#7cff6b";

export interface HudDrawOptions {
  state: HudState;
  faces: readonly FaceBox[];
  mirror: boolean;
  /** 映像ソースの実寸（`<video>`のvideoWidth/videoHeight）。object-fit:coverのクロップを再現するのに使う。 */
  videoWidth: number;
  videoHeight: number;
}

interface CoverTransform {
  scale: number;
  offsetX: number;
  offsetY: number;
}

/** `object-fit:cover`と同じ拡大率・オフセットを計算する。 */
function coverTransform(canvasW: number, canvasH: number, videoW: number, videoH: number): CoverTransform {
  if (videoW <= 0 || videoH <= 0) return { scale: 1, offsetX: 0, offsetY: 0 };
  const scale = Math.max(canvasW / videoW, canvasH / videoH);
  const offsetX = (canvasW - videoW * scale) / 2;
  const offsetY = (canvasH - videoH * scale) / 2;
  return { scale, offsetX, offsetY };
}

/** 正規化座標[0,1]（videoの実寸基準）をcanvasのピクセル矩形に変換する。 */
function boxToCanvasRect(box: FaceBox, cover: CoverTransform, videoW: number, videoH: number) {
  const x = box.x * videoW * cover.scale + cover.offsetX;
  const y = box.y * videoH * cover.scale + cover.offsetY;
  const w = box.w * videoW * cover.scale;
  const h = box.h * videoH * cover.scale;
  return { x, y, w, h };
}

/**
 * HUDをcanvasに描画する（中央の状態バッジ＋各トラックの枠＋名前/markerラベル）。
 * edith/native/edith_glass の reticle_painter.dart / face_box_painter.dart と同じ役割。
 * `<video>`はCSSで`object-fit:cover`表示されているため、正規化座標はcanvasの寸法にそのまま
 * 掛けるのではなく、同じcover変換をかけて位置を合わせる。
 */
export function drawHud(canvas: HTMLCanvasElement, opts: HudDrawOptions): void {
  const ctx = canvas.getContext("2d");
  if (!ctx) return;
  const w = canvas.width;
  const h = canvas.height;
  ctx.clearRect(0, 0, w, h);

  const cover = coverTransform(w, h, opts.videoWidth, opts.videoHeight);

  ctx.save();
  if (opts.mirror) {
    ctx.translate(w, 0);
    ctx.scale(-1, 1);
  }
  for (const face of opts.faces) {
    const rect = boxToCanvasRect(face, cover, opts.videoWidth, opts.videoHeight);
    drawFaceBox(ctx, rect, face, opts.mirror);
  }
  ctx.restore();

  drawStatusBadge(ctx, w, opts.state);
}

function drawFaceBox(
  ctx: CanvasRenderingContext2D,
  rect: { x: number; y: number; w: number; h: number },
  box: FaceBox,
  mirrored: boolean,
): void {
  const { x, y, w: bw, h: bh } = rect;
  const locked = box.name !== undefined;
  const color = locked ? GREEN : CYAN;

  ctx.strokeStyle = color;
  ctx.lineWidth = 2;
  ctx.strokeRect(x, y, bw, bh);

  const label = box.name ?? (box.marker ? `#${box.marker}` : "");
  if (!label) return;

  ctx.font = "600 14px sans-serif";
  const padding = 6;
  const textWidth = ctx.measureText(label).width;
  const tagH = 22;
  const tagY = Math.max(0, y - tagH);

  ctx.fillStyle = "#000000b3";
  ctx.fillRect(x, tagY, textWidth + padding * 2, tagH);
  ctx.fillStyle = color;

  if (mirrored) {
    // ラベルは鏡像にしない（可読性のため個別に反転して描画）。
    ctx.save();
    ctx.translate(x + textWidth + padding * 2, tagY);
    ctx.scale(-1, 1);
    ctx.fillText(label, padding, tagH - 6);
    ctx.restore();
  } else {
    ctx.fillText(label, x + padding, tagY + tagH - 6);
  }
}

function drawStatusBadge(ctx: CanvasRenderingContext2D, w: number, state: HudState): void {
  const text = state.name ? `${state.status} — ${state.name}` : state.status;
  ctx.font = "700 13px sans-serif";
  ctx.textAlign = "center";
  const color = state.locked ? GREEN : CYAN;
  const textWidth = ctx.measureText(text).width;
  const padX = 12;
  const badgeW = textWidth + padX * 2;
  const badgeH = 26;
  const x = w / 2 - badgeW / 2;
  const y = 54;

  ctx.fillStyle = "#000000b3";
  ctx.strokeStyle = color;
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.roundRect(x, y, badgeW, badgeH, 13);
  ctx.fill();
  ctx.stroke();

  ctx.fillStyle = color;
  ctx.fillText(text, w / 2, y + badgeH / 2 + 4);
  ctx.textAlign = "left";

  if (state.subInfo) {
    ctx.font = "400 12px sans-serif";
    ctx.fillStyle = "#ffffffcc";
    ctx.textAlign = "center";
    ctx.fillText(state.subInfo, w / 2, y + badgeH + 16);
    ctx.textAlign = "left";
  }
}
