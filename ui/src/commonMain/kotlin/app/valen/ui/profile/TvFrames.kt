/* SPDX-License-Identifier: GPL-3.0-only */
package app.valen.ui.profile

import org.jetbrains.compose.resources.DrawableResource
import valen.ui.generated.resources.Res
import valen.ui.generated.resources.tv_body_00
import valen.ui.generated.resources.tv_body_01
import valen.ui.generated.resources.tv_body_02
import valen.ui.generated.resources.tv_body_03
import valen.ui.generated.resources.tv_body_04
import valen.ui.generated.resources.tv_body_05
import valen.ui.generated.resources.tv_body_06
import valen.ui.generated.resources.tv_body_07
import valen.ui.generated.resources.tv_body_08
import valen.ui.generated.resources.tv_body_09
import valen.ui.generated.resources.tv_body_10
import valen.ui.generated.resources.tv_body_11
import valen.ui.generated.resources.tv_body_12
import valen.ui.generated.resources.tv_body_13
import valen.ui.generated.resources.tv_body_14
import valen.ui.generated.resources.tv_body_15
import valen.ui.generated.resources.tv_body_16
import valen.ui.generated.resources.tv_body_17
import valen.ui.generated.resources.tv_body_18
import valen.ui.generated.resources.tv_body_19
import valen.ui.generated.resources.tv_body_20
import valen.ui.generated.resources.tv_body_21
import valen.ui.generated.resources.tv_body_22
import valen.ui.generated.resources.tv_body_23
import valen.ui.generated.resources.tv_body_24
import valen.ui.generated.resources.tv_detail_00
import valen.ui.generated.resources.tv_detail_01
import valen.ui.generated.resources.tv_detail_02
import valen.ui.generated.resources.tv_detail_03
import valen.ui.generated.resources.tv_detail_04
import valen.ui.generated.resources.tv_detail_05
import valen.ui.generated.resources.tv_detail_06
import valen.ui.generated.resources.tv_detail_07
import valen.ui.generated.resources.tv_detail_08
import valen.ui.generated.resources.tv_detail_09
import valen.ui.generated.resources.tv_detail_10
import valen.ui.generated.resources.tv_detail_11
import valen.ui.generated.resources.tv_detail_12
import valen.ui.generated.resources.tv_detail_13
import valen.ui.generated.resources.tv_detail_14
import valen.ui.generated.resources.tv_detail_15
import valen.ui.generated.resources.tv_detail_16
import valen.ui.generated.resources.tv_detail_17
import valen.ui.generated.resources.tv_detail_18
import valen.ui.generated.resources.tv_detail_19
import valen.ui.generated.resources.tv_detail_20
import valen.ui.generated.resources.tv_detail_21
import valen.ui.generated.resources.tv_detail_22
import valen.ui.generated.resources.tv_detail_23
import valen.ui.generated.resources.tv_detail_24

/**
 * The television, rendered in 3D (a raymarched model: rounded cabinet with its tube hump, curved
 * glass in a recessed bezel, knurled knobs, speaker slots, chrome rabbit ears and feet; key light
 * with soft shadows, fill, rim, ambient occlusion and glossy reflections) as a turntable of
 * 25 angles from -36° to 36°.
 *
 * Each angle is two layers: [body] is the shading of the plastic in grey, tinted at run time with
 * the scheme's colour; [detail] is everything else, with the gloss on the plastic and the glass
 * over the tube. [quads] is the tube's corners in each frame's pixels (top left, top right,
 * bottom right, bottom left), where the profile picture is laid in perspective between the two.
 *
 * The frames are made by `tools/tv/render_tv.py` (numpy, not part of the build).
 */
internal object TvFrames {
    const val Width = 640
    const val Height = 665

    val yaws = floatArrayOf(-36f, -33f, -30f, -27f, -24f, -21f, -18f, -15f, -12f, -9f, -6f, -3f, 0f, 3f, 6f, 9f, 12f, 15f, 18f, 21f, 24f, 27f, 30f, 33f, 36f)

    val body: List<DrawableResource> = listOf(
        Res.drawable.tv_body_00,
        Res.drawable.tv_body_01,
        Res.drawable.tv_body_02,
        Res.drawable.tv_body_03,
        Res.drawable.tv_body_04,
        Res.drawable.tv_body_05,
        Res.drawable.tv_body_06,
        Res.drawable.tv_body_07,
        Res.drawable.tv_body_08,
        Res.drawable.tv_body_09,
        Res.drawable.tv_body_10,
        Res.drawable.tv_body_11,
        Res.drawable.tv_body_12,
        Res.drawable.tv_body_13,
        Res.drawable.tv_body_14,
        Res.drawable.tv_body_15,
        Res.drawable.tv_body_16,
        Res.drawable.tv_body_17,
        Res.drawable.tv_body_18,
        Res.drawable.tv_body_19,
        Res.drawable.tv_body_20,
        Res.drawable.tv_body_21,
        Res.drawable.tv_body_22,
        Res.drawable.tv_body_23,
        Res.drawable.tv_body_24,
    )

    val detail: List<DrawableResource> = listOf(
        Res.drawable.tv_detail_00,
        Res.drawable.tv_detail_01,
        Res.drawable.tv_detail_02,
        Res.drawable.tv_detail_03,
        Res.drawable.tv_detail_04,
        Res.drawable.tv_detail_05,
        Res.drawable.tv_detail_06,
        Res.drawable.tv_detail_07,
        Res.drawable.tv_detail_08,
        Res.drawable.tv_detail_09,
        Res.drawable.tv_detail_10,
        Res.drawable.tv_detail_11,
        Res.drawable.tv_detail_12,
        Res.drawable.tv_detail_13,
        Res.drawable.tv_detail_14,
        Res.drawable.tv_detail_15,
        Res.drawable.tv_detail_16,
        Res.drawable.tv_detail_17,
        Res.drawable.tv_detail_18,
        Res.drawable.tv_detail_19,
        Res.drawable.tv_detail_20,
        Res.drawable.tv_detail_21,
        Res.drawable.tv_detail_22,
        Res.drawable.tv_detail_23,
        Res.drawable.tv_detail_24,
    )

    val quads: List<FloatArray> = listOf(
        floatArrayOf(112.28f, 302.57f, 297.04f, 314.67f, 297.34f, 509.42f, 114.73f, 480.39f),
        floatArrayOf(110.71f, 303.46f, 304.46f, 314.76f, 304.66f, 509.63f, 113.20f, 482.53f),
        floatArrayOf(109.71f, 304.36f, 311.93f, 314.82f, 312.03f, 509.77f, 112.23f, 484.70f),
        floatArrayOf(109.27f, 305.27f, 319.43f, 314.84f, 319.43f, 509.82f, 111.81f, 486.89f),
        floatArrayOf(109.42f, 306.19f, 326.93f, 314.83f, 326.84f, 509.78f, 111.98f, 489.09f),
        floatArrayOf(110.16f, 307.12f, 334.41f, 314.78f, 334.22f, 509.66f, 112.73f, 491.31f),
        floatArrayOf(111.52f, 308.04f, 341.83f, 314.69f, 341.55f, 509.46f, 114.09f, 493.53f),
        floatArrayOf(113.49f, 308.97f, 349.19f, 314.57f, 348.81f, 509.17f, 116.05f, 495.75f),
        floatArrayOf(116.09f, 309.89f, 356.44f, 314.42f, 355.96f, 508.81f, 118.64f, 497.96f),
        floatArrayOf(119.31f, 310.81f, 363.56f, 314.23f, 363.00f, 508.36f, 121.84f, 500.15f),
        floatArrayOf(123.17f, 311.71f, 370.53f, 314.01f, 369.88f, 507.83f, 125.67f, 502.32f),
        floatArrayOf(127.67f, 312.61f, 377.33f, 313.76f, 376.59f, 507.23f, 130.12f, 504.46f),
        floatArrayOf(132.79f, 313.48f, 383.93f, 313.48f, 383.11f, 506.56f, 135.19f, 506.56f),
        floatArrayOf(138.53f, 314.34f, 390.31f, 313.17f, 389.41f, 505.82f, 140.88f, 508.61f),
        floatArrayOf(144.90f, 315.17f, 396.45f, 312.83f, 395.48f, 505.01f, 147.17f, 510.61f),
        floatArrayOf(151.87f, 315.98f, 402.34f, 312.47f, 401.29f, 504.13f, 154.07f, 512.54f),
        floatArrayOf(159.43f, 316.76f, 407.95f, 312.08f, 406.83f, 503.19f, 161.54f, 514.40f),
        floatArrayOf(167.57f, 317.50f, 413.27f, 311.66f, 412.09f, 502.20f, 169.58f, 516.18f),
        floatArrayOf(176.26f, 318.21f, 418.29f, 311.22f, 417.05f, 501.15f, 178.16f, 517.88f),
        floatArrayOf(185.47f, 318.88f, 422.98f, 310.77f, 421.69f, 500.06f, 187.27f, 519.48f),
        floatArrayOf(195.20f, 319.50f, 427.35f, 310.29f, 426.00f, 498.92f, 196.87f, 520.98f),
        floatArrayOf(205.39f, 320.08f, 431.37f, 309.80f, 429.98f, 497.73f, 206.93f, 522.37f),
        floatArrayOf(216.02f, 320.62f, 435.04f, 309.29f, 433.61f, 496.51f, 217.42f, 523.64f),
        floatArrayOf(227.05f, 321.10f, 438.35f, 308.76f, 436.89f, 495.26f, 228.31f, 524.79f),
        floatArrayOf(238.45f, 321.52f, 441.30f, 308.23f, 439.80f, 493.98f, 239.56f, 525.81f),
    )
}
