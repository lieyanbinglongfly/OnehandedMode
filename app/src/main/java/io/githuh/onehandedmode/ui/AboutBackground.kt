// Adapted from YuKongA/Mishka BgEffect and OS3BgFrag. GPL-3.0-only.
// https://github.com/YuKongA/Mishka/tree/main/app/src/main/kotlin/top/yukonga/mishka/ui/component/effect
package io.githuh.onehandedmode.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.blur.RuntimeShader
import top.yukonga.miuix.kmp.blur.asBrush
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun AboutBackground(modifier: Modifier = Modifier, alpha: () -> Float) {
    val surface = MiuixTheme.colorScheme.surface
    if (Build.VERSION.SDK_INT < 33) {
        Spacer(modifier.fillMaxSize().drawWithCache { onDrawBehind { drawRect(surface) } })
        return
    }
    val dark = isSystemInDarkTheme()
    val palette = remember(dark) { if (dark) DarkColors else LightColors }
    val points = remember { floatArrayOf(.8f,.2f,1f, .8f,.9f,1f, .2f,.9f,1f, .2f,.2f,1f) }
    val shader = remember(dark) {
        RuntimeShader(AboutShader).apply {
            setFloatUniform("uPoints", points)
            setFloatUniform("uLightOffset", if (dark) 0f else .1f)
            setFloatUniform("uSaturateOffset", if (dark) .17f else .2f)
        }
    }
    val brush = remember(shader) { shader.asBrush() }
    var time by remember { mutableFloatStateOf(0f) }
    val stage = remember { Animatable(0f) }
    val currentAlpha by rememberUpdatedState(alpha)
    LaunchedEffect(dark) {
        val start = withFrameNanos { it }
        var lastFrame = start
        while (isActive) {
            snapshotFlow { currentAlpha() > 0f }.first { it }
            withFrameNanos {
                if (it - lastFrame >= 1_000_000_000L / 60) {
                    time = (it - start) / 1_000_000_000f
                    lastFrame = it
                }
            }
        }
    }
    LaunchedEffect(dark) {
        var target = 1f
        while (isActive) {
            snapshotFlow { currentAlpha() > 0f }.first { it }
            delay(if (dark) 4000 else 2500)
            stage.animateTo(target, spring(dampingRatio = .9f, stiffness = 35f))
            target += 1f
        }
    }
    val colors = remember { FloatArray(16) }
    val animatedPoints = remember { FloatArray(8) }
    Spacer(modifier.fillMaxSize().drawWithCache {
        shader.setFloatUniform("uResolution", size.width, size.height)
        if (size.width <= size.height) shader.setFloatUniform("uBound", 0f, .2f, 1f, .8f)
        else shader.setFloatUniform("uBound", 0f, .6f - size.width / size.height / 2, 1f, size.width / size.height)
        onDrawBehind {
            drawRect(surface)
            val opacity = currentAlpha()
            if (opacity > 0f) {
                val elapsed = time
                val base = stage.value.toInt()
                val fraction = stage.value - base
                fun colorAt(index: Int) = palette[when (index % 4) { 1 -> 0; 3 -> 2; else -> 1 }]
                val start = colorAt(base)
                val end = colorAt(base + 1)
                for (i in colors.indices) colors[i] = start[i] + (end[i] - start[i]) * fraction
                val offset = if (dark) .4f else .2f
                for (i in 0..3) {
                    val x = points[i * 3] + sin(elapsed + points[i * 3 + 1]) * offset
                    animatedPoints[i * 2] = x
                    animatedPoints[i * 2 + 1] = points[i * 3 + 1] + cos(elapsed + x) * offset
                }
                shader.setFloatUniform("uAnimTime", elapsed)
                shader.setFloatUniform("uColors", colors)
                shader.setFloatUniform("uPointsAnim", animatedPoints)
                drawRect(brush, alpha = opacity)
            }
        }
    })
}

private val LightColors = arrayOf(
    floatArrayOf(1f,.9f,.94f,1f, 1f,.84f,.89f,1f, .97f,.73f,.82f,1f, .64f,.65f,.98f,1f),
    floatArrayOf(.58f,.74f,1f,1f, 1f,.9f,.93f,1f, .74f,.76f,1f,1f, .97f,.77f,.84f,1f),
    floatArrayOf(.98f,.86f,.9f,1f, .6f,.73f,.98f,1f, .92f,.93f,1f,1f, .56f,.69f,1f,1f),
)
private val DarkColors = arrayOf(
    floatArrayOf(.2f,.06f,.88f,.4f, .3f,.14f,.55f,.5f, 0f,.64f,.96f,.5f, .11f,.16f,.83f,.4f),
    floatArrayOf(.07f,.15f,.79f,.5f, .62f,.21f,.67f,.5f, .06f,.25f,.84f,.5f, 0f,.2f,.78f,.5f),
    floatArrayOf(.58f,.3f,.74f,.4f, .27f,.18f,.6f,.5f, .66f,.26f,.62f,.5f, .12f,.16f,.7f,.6f),
)
private const val AboutShader = """
    uniform vec2 uResolution;
    uniform float uAnimTime;
    uniform vec4 uBound;
    uniform vec3 uPoints[4];
    uniform vec2 uPointsAnim[4];
    uniform vec4 uColors[4];
    uniform float uSaturateOffset;
    uniform float uLightOffset;
    vec3 rgb2hsv(vec3 c) {
        vec4 K = vec4(0., -1./3., 2./3., -1.);
        vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b,c.g));
        vec4 q = mix(vec4(p.xyw,c.r), vec4(c.r,p.yzx), step(p.x,c.r));
        float d = q.x - min(q.w,q.y);
        return vec3(abs(q.z+(q.w-q.y)/(6.*d+1.e-10)), d/(q.x+1.e-10), q.x);
    }
    vec3 hsv2rgb(vec3 c) {
        vec4 K = vec4(1.,2./3.,1./3.,3.);
        vec3 p = abs(fract(c.xxx+K.xyz)*6.-K.www);
        return c.z*mix(K.xxx,clamp(p-K.xxx,0.,1.),c.y);
    }
    float randomNoise(vec2 p) {
        vec3 p3 = fract(vec3(p.xyx)*.13);
        p3 += dot(p3,p3.yzx+3.333);
        return fract((p3.x+p3.y)*p3.z);
    }
    float perlin(vec2 x) {
        vec2 i=floor(x), f=fract(x);
        float a=randomNoise(i), b=randomNoise(i+vec2(1.,0.));
        float c=randomNoise(i+vec2(0.,1.)), d=randomNoise(i+vec2(1.,1.));
        vec2 u=f*f*(3.-2.*f);
        return mix(a,b,u.x)+(c-a)*u.y*(1.-u.x)+(d-b)*u.x*u.y;
    }
    vec4 main(vec2 fragCoord) {
        vec2 vUv=fragCoord/uResolution;
        vUv.y=1.-vUv.y;
        vec2 uv=(vUv-uBound.xy)/uBound.zw;
        vec4 color=vec4(0.);
        float noiseValue=perlin(vUv*1.5+vec2(-uAnimTime));
        for (int i=0;i<4;i++) {
            vec4 c=uColors[i]; c.rgb*=c.a;
            float pct=smoothstep(uPoints[i].z,0.,distance(uv,uPointsAnim[i]));
            color.rgb=mix(color.rgb,c.rgb,pct);
            color.a=mix(color.a,c.a,pct);
        }
        float oppositeNoise=smoothstep(0.,1.,noiseValue);
        color.rgb/=color.a;
        vec3 hsv=rgb2hsv(color.rgb);
        hsv.y=mix(hsv.y,0.,oppositeNoise*uSaturateOffset);
        color.rgb=hsv2rgb(hsv)+oppositeNoise*uLightOffset;
        color.a=clamp(color.a,0.,1.);
        float grain=fract(52.9829189*fract(dot(fragCoord,vec2(.06711056,.00583715))));
        color+=(10./255.)*grain-(5./255.);
        return vec4(color.rgb*color.a,color.a);
    }
"""
