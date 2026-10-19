/** Vetor 2D imutável. Unidade fica a cargo de quem usa (no modelo, sempre mm). */
export class Vector2 {
  static readonly ZERO = new Vector2(0, 0);

  constructor(
    readonly x: number,
    readonly y: number,
  ) {}

  add(v: Vector2): Vector2 {
    return new Vector2(this.x + v.x, this.y + v.y);
  }

  sub(v: Vector2): Vector2 {
    return new Vector2(this.x - v.x, this.y - v.y);
  }

  scale(k: number): Vector2 {
    return new Vector2(this.x * k, this.y * k);
  }

  length(): number {
    return Math.hypot(this.x, this.y);
  }

  /** Rotaciona em torno da origem. Graus, sentido horário (eixo Y para baixo, como na tela). */
  rotate(degrees: number): Vector2 {
    const rad = (degrees * Math.PI) / 180;
    const cos = Math.cos(rad);
    const sin = Math.sin(rad);
    return new Vector2(this.x * cos - this.y * sin, this.x * sin + this.y * cos);
  }

  equals(v: Vector2, epsilon = 1e-9): boolean {
    return Math.abs(this.x - v.x) <= epsilon && Math.abs(this.y - v.y) <= epsilon;
  }
}
