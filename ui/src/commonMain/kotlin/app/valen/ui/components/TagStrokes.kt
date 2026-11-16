/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.components

/**
 * Valen's tag, as the strokes of the marker that wrote it: traced from the owner's own drawing
 * (centre lines of the ink, in the order a hand would write them, left to right), so the app can
 * write its name rather than show a picture of it. Each stroke is x, y pairs as a share of the
 * tag's width; the tag is [TagAspect] times as tall as it is wide.
 */
internal object TagStrokes {
    val strokes: List<FloatArray> = listOf(
        floatArrayOf(0.0220f, 0.4431f, 0.0459f, 0.4930f, 0.0539f, 0.5010f, 0.0758f, 0.5090f, 0.1297f, 0.5768f, 0.1577f, 0.5868f),
        floatArrayOf(0.0479f, 0.4990f, 0.0519f, 0.5409f, 0.0639f, 0.5749f),
        floatArrayOf(0.0858f, 0.4112f, 0.1198f, 0.4591f, 0.1417f, 0.4651f, 0.1637f, 0.4870f, 0.2016f, 0.5130f, 0.2475f, 0.5369f, 0.2754f, 0.5569f, 0.2954f, 0.5569f, 0.3054f, 0.5509f, 0.3174f, 0.5808f, 0.3453f, 0.6307f, 0.3513f, 0.6647f),
        floatArrayOf(0.1178f, 0.4611f, 0.1198f, 0.4890f, 0.1537f, 0.5569f, 0.1577f, 0.5828f),
        floatArrayOf(0.1597f, 0.5908f, 0.2335f, 0.6926f, 0.2575f, 0.7186f),
        floatArrayOf(0.2255f, 0.3194f, 0.2515f, 0.3733f, 0.2774f, 0.3852f, 0.2874f, 0.3952f, 0.3573f, 0.4850f, 0.3693f, 0.4930f, 0.3932f, 0.4691f),
        floatArrayOf(0.2495f, 0.3772f, 0.2595f, 0.4291f, 0.2954f, 0.5289f, 0.3034f, 0.5389f, 0.3034f, 0.5489f),
        floatArrayOf(0.2555f, 0.6547f, 0.3014f, 0.6547f, 0.3214f, 0.6607f, 0.3473f, 0.6627f),
        floatArrayOf(0.2954f, 0.2415f, 0.3234f, 0.2794f, 0.3393f, 0.3134f, 0.3433f, 0.3313f, 0.3373f, 0.3373f, 0.3373f, 0.3473f),
        floatArrayOf(0.3054f, 0.5369f, 0.3214f, 0.5329f, 0.3473f, 0.5190f, 0.3673f, 0.4990f, 0.3673f, 0.4950f),
        floatArrayOf(0.3473f, 0.3313f, 0.3653f, 0.3393f, 0.4511f, 0.4611f, 0.4970f, 0.5110f, 0.5190f, 0.5170f, 0.5449f, 0.5090f, 0.6228f, 0.4511f, 0.6267f, 0.4271f, 0.6188f, 0.4212f, 0.6168f, 0.4132f, 0.6168f, 0.3832f, 0.6267f, 0.3433f, 0.6447f, 0.3014f, 0.6427f, 0.2974f, 0.6068f, 0.2974f, 0.5828f, 0.2834f, 0.5709f, 0.2715f, 0.5709f, 0.2435f, 0.5868f, 0.2275f, 0.6208f, 0.2076f, 0.6367f, 0.2016f, 0.6707f, 0.1976f, 0.6826f, 0.1896f, 0.6747f, 0.1557f, 0.6647f, 0.1457f),
        floatArrayOf(0.3713f, 0.5010f, 0.4311f, 0.5549f, 0.4910f, 0.5948f),
        floatArrayOf(0.4770f, 0.1557f, 0.4890f, 0.1737f, 0.4950f, 0.1936f, 0.4970f, 0.2635f),
        floatArrayOf(0.6307f, 0.4251f, 0.6427f, 0.4212f, 0.6627f, 0.4032f, 0.7405f, 0.3533f, 0.7764f, 0.3393f, 0.8343f, 0.3034f, 0.8363f, 0.2954f, 0.8263f, 0.2794f, 0.7705f, 0.2495f),
        floatArrayOf(0.7705f, 0.1158f, 0.8224f, 0.1437f, 0.8283f, 0.1497f, 0.8343f, 0.1677f, 0.8982f, 0.2255f, 0.9381f, 0.2794f, 0.9441f, 0.2954f, 0.9421f, 0.3214f, 0.9281f, 0.3313f, 0.8723f, 0.3473f),
        floatArrayOf(0.8283f, 0.1457f, 0.8583f, 0.1497f, 0.9261f, 0.1756f, 0.9561f, 0.1756f, 0.9741f, 0.1876f),
        floatArrayOf(0.8383f, 0.0200f, 0.8703f, 0.0479f, 0.8743f, 0.0679f, 0.9481f, 0.1557f, 0.9541f, 0.1737f),
        floatArrayOf(0.8703f, 0.0439f, 0.8962f, 0.0479f, 0.9461f, 0.0758f),
    )

    /** Height over width. */
    const val TagAspect = 0.7405f

    /** The marker's width, as a share of the tag's width. */
    const val Nib = 0.0293f

    /** The strokes that make the V, for a signature that only has room for the initial. */
    const val InitialStrokes = 5
}
