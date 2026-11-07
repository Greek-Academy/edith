/**
 * グラスHUDに表示する状態。Recognize→Remember→Assist の段階(PRDのHUD表示レベル)を
 * 素朴に表現する。edith/native/edith_glass の HudState(Dart) と同じ形。
 */
export interface HudState {
  /** 上部に出すシステム状態（例: SCANNING / DETECTED / TARGET LOCKED）。 */
  status: string;
  /** 認識できた人物名（Level 1: 名前のみ）。未認識ならundefined。 */
  name?: string;
  /** 補足情報（Level 2相当、例: 「前回：Honoについて話した」）。 */
  subInfo?: string;
  /** 人物をロック（認識確定）したか。 */
  locked: boolean;
}

export const idleHudState: HudState = { status: "SCANNING", locked: false };
