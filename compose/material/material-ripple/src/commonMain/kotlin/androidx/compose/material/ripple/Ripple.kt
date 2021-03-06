/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.ripple

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isUnspecified
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Creates and [remember]s a Ripple using values provided by [RippleTheme].
 *
 * A Ripple is a Material implementation of [Indication] that expresses different [Interaction]s
 * by drawing ripple animations and state layers.
 *
 * A Ripple responds to [PressInteraction.Press] by starting a new [RippleAnimation], and
 * responds to other [Interaction]s by showing a fixed [StateLayer] with varying alpha values
 * depending on the [Interaction].
 *
 * If you are using MaterialTheme in your hierarchy, a Ripple will be used as the default
 * [Indication] inside components such as [androidx.compose.foundation.clickable] and
 * [androidx.compose.foundation.indication]. You can also manually provide Ripples through
 * [androidx.compose.foundation.LocalIndication] for the same effect if you are not using
 * MaterialTheme.
 *
 * You can also explicitly create a Ripple and provide it to components in order to change the
 * parameters from the default, such as to create an unbounded ripple with a fixed size.
 *
 * @param bounded If true, ripples are clipped by the bounds of the target layout. Unbounded
 * ripples always animate from the target layout center, bounded ripples animate from the touch
 * position.
 * @param radius the radius for the ripple. If [Dp.Unspecified] is provided then the size will be
 * calculated based on the target layout size.
 * @param color the color of the ripple. This color is usually the same color used by the text or
 * iconography in the component. This color will then have [RippleTheme.rippleAlpha] applied to
 * calculate the final color used to draw the ripple. If [Color.Unspecified] is provided the color
 * used will be [RippleTheme.defaultColor] instead.
 */
@Composable
public fun rememberRipple(
    bounded: Boolean = true,
    radius: Dp = Dp.Unspecified,
    color: Color = Color.Unspecified
): Indication {
    val colorState = rememberUpdatedState(color)
    return remember(bounded, radius) {
        Ripple(bounded, radius, colorState)
    }
}

/**
 * A Ripple is a Material implementation of [Indication] that expresses different [Interaction]s
 * by drawing ripple animations and state layers.
 *
 * A Ripple responds to [PressInteraction.Press] by starting a new [RippleAnimation], and
 * responds to other [Interaction]s by showing a fixed [StateLayer] with varying alpha values
 * depending on the [Interaction].
 *
 * If you are using MaterialTheme in your hierarchy, a Ripple will be used as the default
 * [Indication] inside components such as [androidx.compose.foundation.clickable] and
 * [androidx.compose.foundation.indication]. You can also manually provide Ripples through
 * [androidx.compose.foundation.LocalIndication] for the same effect if you are not using
 * MaterialTheme.
 *
 * You can also explicitly create a Ripple and provide it to components in order to change the
 * parameters from the default, such as to create an unbounded ripple with a fixed size.
 */
@Stable
private class Ripple(
    private val bounded: Boolean,
    private val radius: Dp,
    private val color: State<Color>,
) : Indication {
    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance {
        val theme = LocalRippleTheme.current
        val color = rememberUpdatedState(
            if (color.value.isSpecified) {
                color.value
            } else {
                theme.defaultColor()
            }
        )
        val rippleAlpha = rememberUpdatedState(theme.rippleAlpha())
        val instance = remember(interactionSource, this) {
            RippleIndicationInstance(bounded, radius, color, rippleAlpha)
        }
        LaunchedEffect(interactionSource, instance) {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        launch {
                            instance.addRipple(interaction)
                        }
                    }
                    is PressInteraction.Release -> {
                        instance.removeRipple(interaction.press)
                    }
                    is PressInteraction.Cancel -> {
                        instance.removeRipple(interaction.press)
                    }
                    else -> instance.updateStateLayer(interaction)
                }
            }
        }
        return instance
    }

    // to force stability on this indication we need equals and hashcode, there's no value in
    // making this class to be "data class"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ripple) return false

        if (bounded != other.bounded) return false
        if (radius != other.radius) return false
        if (color != other.color) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bounded.hashCode()
        result = 31 * result + radius.hashCode()
        result = 31 * result + color.hashCode()
        return result
    }
}

private class RippleIndicationInstance constructor(
    private val bounded: Boolean,
    private val radius: Dp,
    private val color: State<Color>,
    private val rippleAlpha: State<RippleAlpha>
) : RememberObserver, IndicationInstance {

    private val stateLayer = StateLayer(bounded, rippleAlpha)

    private val ripples = mutableStateMapOf<PressInteraction.Press, RippleAnimation>()

    override fun ContentDrawScope.drawIndication() {
        val color = color.value
        drawContent()
        with(stateLayer) {
            drawStateLayer(radius, color)
        }
        drawRipples(color)
    }

    suspend fun addRipple(interaction: PressInteraction.Press) {
        // Finish existing ripples
        ripples.forEach { (_, ripple) -> ripple.finish() }
        val origin = if (bounded) interaction.pressPosition else null
        val rippleAnimation = RippleAnimation(
            origin = origin,
            radius = radius,
            bounded = bounded
        )
        ripples[interaction] = rippleAnimation
        rippleAnimation.animate()
        ripples.remove(interaction)
    }

    suspend fun updateStateLayer(interaction: Interaction) {
        stateLayer.handleInteraction(interaction)
    }

    fun removeRipple(interaction: PressInteraction.Press) {
        ripples[interaction]?.finish()
    }

    private fun DrawScope.drawRipples(color: Color) {
        ripples.forEach { (_, ripple) ->
            with(ripple) {
                val alpha = rippleAlpha.value.pressedAlpha
                if (alpha != 0f) {
                    draw(color.copy(alpha = alpha))
                }
            }
        }
    }

    override fun onRemembered() {}

    override fun onForgotten() {
        ripples.clear()
    }

    override fun onAbandoned() {
        ripples.clear()
    }
}

/**
 * Represents the layer underneath the press ripple, that displays an overlay for states such as
 * [DragInteraction.Start].
 *
 * Typically, there should be both an 'incoming' and an 'outgoing' layer, so that when
 * transitioning between two states, the incoming of the new state, and the outgoing of the old
 * state can be displayed. However, because:
 *
 * a) the duration of these outgoing transitions are so short (mostly 15ms, which is less than 1
 * frame at 60fps), and hence are barely noticeable if they happen at the same time as an
 * incoming transition
 * b) two layers cause a lot of extra work, and related performance concerns
 *
 * We skip managing two layers, and instead only show one layer. The details for the
 * [AnimationSpec]s used are as follows:
 *
 * No state -> a state = incoming transition for the new state
 * A state -> a different state = incoming transition for the new state
 * A state -> no state = outgoing transition for the old state
 *
 * @see incomingStateLayerAnimationSpecFor
 * @see outgoingStateLayerAnimationSpecFor
 */
private class StateLayer(
    private val bounded: Boolean,
    // TODO: consider dynamically updating the alpha for existing interactions when rippleAlpha
    // changes
    private val rippleAlpha: State<RippleAlpha>
) {
    private val animatedAlpha = Animatable(0f)

    private val interactions: MutableList<Interaction> = mutableListOf()
    private var currentInteraction: Interaction? = null

    suspend fun handleInteraction(interaction: Interaction) {
        // TODO: handle hover / focus states
        when (interaction) {
            is DragInteraction.Start -> {
                interactions.add(interaction)
            }
            is DragInteraction.Stop -> {
                interactions.remove(interaction.start)
            }
            is DragInteraction.Cancel -> {
                interactions.remove(interaction.start)
            }
            else -> return
        }

        // The most recent interaction is the one we want to show
        val newInteraction = interactions.lastOrNull()

        if (currentInteraction != newInteraction) {
            if (newInteraction != null) {
                val targetAlpha = when (interaction) {
                    is DragInteraction.Start -> rippleAlpha.value.draggedAlpha
                    else -> 0f
                }
                val incomingAnimationSpec = incomingStateLayerAnimationSpecFor(newInteraction)

                animatedAlpha.animateTo(targetAlpha, incomingAnimationSpec)
            } else {
                val outgoingAnimationSpec = outgoingStateLayerAnimationSpecFor(currentInteraction)

                animatedAlpha.animateTo(0f, outgoingAnimationSpec)
            }
            currentInteraction = newInteraction
        }
    }

    fun DrawScope.drawStateLayer(radius: Dp, color: Color) {
        val targetRadius = if (radius.isUnspecified) {
            getRippleEndRadius(bounded, size)
        } else {
            radius.toPx()
        }

        val alpha = animatedAlpha.value

        if (alpha > 0f) {
            val modulatedColor = color.copy(alpha = alpha)

            if (bounded) {
                clipRect {
                    drawCircle(modulatedColor, targetRadius)
                }
            } else {
                drawCircle(modulatedColor, targetRadius)
            }
        }
    }
}

/**
 * @return the [AnimationSpec] used when transitioning to [interaction], either from a previous
 * state, or no state.
 *
 * TODO: handle hover / focus states
 */
private fun incomingStateLayerAnimationSpecFor(interaction: Interaction): AnimationSpec<Float> {
    return if (interaction is DragInteraction.Start) {
        TweenSpec(durationMillis = 45, easing = LinearEasing)
    } else {
        DefaultTweenSpec
    }
}

/**
 * @return the [AnimationSpec] used when transitioning away from [interaction], to no state.
 *
 * TODO: handle hover / focus states
 */
private fun outgoingStateLayerAnimationSpecFor(interaction: Interaction?): AnimationSpec<Float> {
    return if (interaction is DragInteraction.Start) {
        TweenSpec(durationMillis = 150, easing = LinearEasing)
    } else {
        DefaultTweenSpec
    }
}

/**
 * Default / fallback [AnimationSpec].
 */
private val DefaultTweenSpec = TweenSpec<Float>(durationMillis = 15, easing = LinearEasing)
