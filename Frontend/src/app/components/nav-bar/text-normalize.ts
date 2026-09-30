/** Lowercase, accent-free words, for matching recent questions. */
export class TextNormalize {
  static words(s: string): string[] {
    return (s ?? '')
      .normalize('NFD').replace(/\p{M}+/gu, '')
      .toLowerCase()
      .split(/[^\p{L}\p{N}]+/u)
      .filter(w => w.length > 0);
  }
}
