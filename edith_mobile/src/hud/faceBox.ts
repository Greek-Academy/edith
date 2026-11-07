/**
 * 顔の枠。座標は入力画像に対する正規化値[0,1]（x,y=左上, w,h=幅高）。
 * edith/native/edith_glass の FaceBox(Dart) と同じ形。
 */
export interface FaceBox {
  x: number;
  y: number;
  w: number;
  h: number;
  score: number;
  /** 識別できた人物名（未識別ならundefined）。 */
  name?: string;
  /** 未識別の人に付ける簡易識別子（例: A, B）。 */
  marker?: string;
}

export function centerX(b: FaceBox): number {
  return b.x + b.w / 2;
}

export function centerY(b: FaceBox): number {
  return b.y + b.h / 2;
}

export function labeled(b: FaceBox, name?: string, marker?: string): FaceBox {
  return { x: b.x, y: b.y, w: b.w, h: b.h, score: b.score, name, marker };
}

/** 追従を滑らかにするための線形補間（ラベルはbを引き継ぐ）。 */
export function lerpFaceBox(a: FaceBox, b: FaceBox, t: number): FaceBox {
  return {
    x: a.x + (b.x - a.x) * t,
    y: a.y + (b.y - a.y) * t,
    w: a.w + (b.w - a.w) * t,
    h: a.h + (b.h - a.h) * t,
    score: b.score,
    name: b.name,
    marker: b.marker,
  };
}
