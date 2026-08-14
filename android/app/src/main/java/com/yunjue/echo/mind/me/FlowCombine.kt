package com.yunjue.echo.mind.me

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * ERA 13.1 — 多流 combine 助手（kotlinx.coroutines 内置 combine 最多 5 参）。
 */

internal data class Combine7<A, B, C, D, E, F, G>(
    val a: A, val b: B, val c: C, val d: D, val e: E, val f: F, val g: G,
)

internal data class Combine8<A, B, C, D, E, F, G, H>(
    val a: A, val b: B, val c: C, val d: D, val e: E, val f: F, val g: G, val h: H,
)

private data class Combine5<A, B, C, D, E>(
    val a: A, val b: B, val c: C, val d: D, val e: E,
)

internal fun <A, B, C, D, E, F, G> combine7(
    fa: Flow<A>, fb: Flow<B>, fc: Flow<C>, fd: Flow<D>, fe: Flow<E>, ff: Flow<F>, fg: Flow<G>,
): Flow<Combine7<A, B, C, D, E, F, G>> {
    val five: Flow<Combine5<A, B, C, D, E>> =
        combine(fa, fb, fc, fd, fe) { a: A, b: B, c: C, d: D, e: E -> Combine5(a, b, c, d, e) }
    return combine(five, ff, fg) { p5: Combine5<A, B, C, D, E>, f: F, g: G ->
        Combine7(p5.a, p5.b, p5.c, p5.d, p5.e, f, g)
    }
}

internal fun <A, B, C, D, E, F, G, H> combine8(
    fa: Flow<A>, fb: Flow<B>, fc: Flow<C>, fd: Flow<D>,
    fe: Flow<E>, ff: Flow<F>, fg: Flow<G>, fh: Flow<H>,
): Flow<Combine8<A, B, C, D, E, F, G, H>> {
    val five: Flow<Combine5<A, B, C, D, E>> =
        combine(fa, fb, fc, fd, fe) { a: A, b: B, c: C, d: D, e: E -> Combine5(a, b, c, d, e) }
    return combine(five, ff, fg, fh) { p5: Combine5<A, B, C, D, E>, f: F, g: G, h: H ->
        Combine8(p5.a, p5.b, p5.c, p5.d, p5.e, f, g, h)
    }
}
