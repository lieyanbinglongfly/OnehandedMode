package io.githuh.onehandedmode.ui

import android.content.Context
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal class MiuixSettingsButton(
    context: Context,
    private val windowRoot: View,
    description: String,
    onClick: () -> Unit,
) {
    private val owner = ButtonOwner()

    init {
        // WindowRecomposer resolves owners from the window content root, above ComposeView.
        windowRoot.setViewTreeLifecycleOwner(owner)
        windowRoot.setViewTreeSavedStateRegistryOwner(owner)
    }

    val view = ComposeView(context).apply {
        id = View.generateViewId()
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                IconButton(
                    onClick = onClick,
                    modifier = Modifier.size(35.dp),
                    minHeight = 35.dp,
                    minWidth = 35.dp,
                    backgroundColor = MiuixTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Settings,
                        contentDescription = description,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }

    fun start() {
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
    }

    fun dispose() {
        view.disposeComposition()
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        windowRoot.setViewTreeLifecycleOwner(null)
        windowRoot.setViewTreeSavedStateRegistryOwner(null)
    }

    private class ButtonOwner : SavedStateRegistryOwner {
        override val lifecycle = LifecycleRegistry(this)
        private val controller = SavedStateRegistryController.create(this)
        override val savedStateRegistry get() = controller.savedStateRegistry

        init {
            controller.performAttach()
            controller.performRestore(null)
            lifecycle.currentState = Lifecycle.State.CREATED
        }
    }
}
