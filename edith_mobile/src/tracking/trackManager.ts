import { centerX, centerY, labeled, lerpFaceBox, type FaceBox } from "../hud/faceBox";

/** 追跡中の1つの顔（トラック）。edith/native/edith_glass の _Track(home_page.dart)を移植。
 * 同一トラックである限り人物を固定（sticky）し、見失うまで「不明」に落とさない。 */
export class Track {
  box: FaceBox;
  shown: FaceBox;
  name?: string;
  marker?: string;
  lastSeen: number;
  lastId?: number;
  identifying = false;

  constructor(
    public readonly id: number,
    box: FaceBox,
  ) {
    this.box = box;
    this.shown = box;
    this.lastSeen = Date.now();
  }
}

export interface TrackManagerOptions {
  /** トラック対応付けの中心距離しきい値（正規化）。 */
  matchDist?: number;
  /** これ以上見失ったらトラック終了（ms）。 */
  lostGraceMs?: number;
}

/** 複数人の顔トラックを管理する。home_page.dart の _ingestBoxes/_onTick/_assignMarker 相当。 */
export class TrackManager {
  private readonly matchDist: number;
  private readonly lostGraceMs: number;
  private _tracks: Track[] = [];
  private nextId = 1;

  constructor(opts: TrackManagerOptions = {}) {
    this.matchDist = opts.matchDist ?? 0.2;
    this.lostGraceMs = opts.lostGraceMs ?? 1200;
  }

  get tracks(): readonly Track[] {
    return this._tracks;
  }

  clear(): void {
    this._tracks = [];
  }

  /** 検出した生の枠を既存トラックへ対応付け（最近傍）、新規/消失を反映する。 */
  ingest(raw: FaceBox[]): void {
    const now = Date.now();
    const usedRaw = new Set<number>();

    for (const t of this._tracks) {
      let best = -1;
      let bestD = this.matchDist;
      for (let i = 0; i < raw.length; i++) {
        if (usedRaw.has(i)) continue;
        const d = Math.abs(centerX(t.box) - centerX(raw[i])) + Math.abs(centerY(t.box) - centerY(raw[i]));
        if (d < bestD) {
          bestD = d;
          best = i;
        }
      }
      if (best >= 0) {
        t.box = raw[best];
        t.lastSeen = now;
        usedRaw.add(best);
      }
    }

    for (let i = 0; i < raw.length; i++) {
      if (!usedRaw.has(i)) {
        const track = new Track(this.nextId++, raw[i]);
        track.marker = this.assignMarker();
        this._tracks.push(track);
      }
    }

    this._tracks = this._tracks.filter((t) => now - t.lastSeen <= this.lostGraceMs);
  }

  /** 現在使われていない最小の英字を識別子として割り当てる（A, B, ...）。 */
  private assignMarker(): string {
    const used = new Set(this._tracks.map((t) => t.marker).filter((m): m is string => !!m));
    for (let i = 0; i < 26; i++) {
      const c = String.fromCharCode(65 + i);
      if (!used.has(c)) return c;
    }
    return `#${this.nextId}`;
  }

  /** 毎フレーム：各トラックの表示枠を補間して返す（name/markerをラベル付き）。 */
  tick(): FaceBox[] {
    return this._tracks.map((t) => {
      t.shown = lerpFaceBox(t.shown, t.box, 0.3);
      return labeled(t.shown, t.name, t.name === undefined ? t.marker : undefined);
    });
  }

  findByMarker(marker: string): Track | undefined {
    const m = marker.trim().toUpperCase();
    return this._tracks.find((t) => (t.marker ?? "").toUpperCase() === m);
  }

  unnamedTracks(): Track[] {
    return this._tracks.filter((t) => t.name === undefined);
  }
}
