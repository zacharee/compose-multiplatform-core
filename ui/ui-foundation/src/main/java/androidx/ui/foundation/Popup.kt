/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.ui.foundation

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.PopupWindow
import androidx.compose.Composable
import androidx.compose.Compose
import androidx.compose.Context
import androidx.compose.Immutable
import androidx.compose.ambient
import androidx.compose.composer
import androidx.compose.disposeComposition
import androidx.compose.memo
import androidx.compose.onCommit
import androidx.compose.onDispose
import androidx.compose.unaryPlus
import androidx.ui.core.AndroidComposeView
import androidx.ui.core.AndroidComposeViewAmbient
import androidx.ui.core.ContextAmbient
import androidx.ui.core.IntPx
import androidx.ui.core.IntPxPosition
import androidx.ui.core.IntPxSize
import androidx.ui.core.OnChildPositioned
import androidx.ui.core.OnPositioned
import androidx.ui.core.PxPosition
import androidx.ui.core.PxSize
import androidx.ui.core.round
import androidx.ui.core.setContent
import androidx.ui.layout.Alignment

/**
 * Opens a popup with the given content.
 *
 * The popup is positioned relative to its parent, using the [alignment] and [offset].
 * The popup is visible as long as it is part of the composition hierarchy.
 *
 * @sample androidx.ui.foundation.samples.PopupSample
 *
 * @param alignment The alignment relative to the parent.
 * @param offset An offset from the original aligned position of the popup.
 * @param popupProperties Provides extended set of properties to configure the popup.
 * @param children The content to be displayed inside the popup.
 */
@Composable
fun Popup(
    alignment: Alignment,
    offset: IntPxPosition = IntPxPosition(IntPx.Zero, IntPx.Zero),
    popupProperties: PopupProperties = PopupProperties(),
    children: @Composable() () -> Unit
) {
    val context = +ambient(ContextAmbient)
    // TODO(b/139866476): Decide if we want to expose the AndroidComposeView
    val composeView = +ambient(AndroidComposeViewAmbient)

    val popupPositionProperties = +memo {
        PopupPositionProperties(
            alignment = alignment,
            offset = offset,
            composeView = composeView
        )
    }
    popupPositionProperties.alignment = alignment
    popupPositionProperties.offset = offset

    val frameLayout = +memo { FrameLayout(context) }
    val popup = +memo {
        PopupWrapper(context).apply {
            contentView = frameLayout
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    // Set popup properties
    popup.isFocusable = popupProperties.isFocusable
    popup.onDismissRequest = popupProperties.onDismissRequest

    // Get the parent's global position and size
    OnPositioned { coordinates ->
        // Get the global position of the parent
        val layoutPosition = coordinates.localToGlobal(PxPosition.Origin)
        val layoutSize = coordinates.size

        popupPositionProperties.parentPosition = layoutPosition
        popupPositionProperties.parentSize = layoutSize

        updatePopup(popup, popupPositionProperties)
    }

    +onCommit {
        frameLayout.setContent {
            OnChildPositioned({
                popupPositionProperties.childrenSize = it.size
                updatePopup(popup, popupPositionProperties)
            }) {
                children()
            }
        }
    }

    +onDispose {
        frameLayout.disposeComposition()
        // Call the dismiss method of the PopupWindow which actually dismisses the popup
        popup.dismissWithoutNotifying()
    }
}

private class PopupWrapper(context: Context) : PopupWindow(context) {
    var onDismissRequest: (() -> Unit)? = null

    override fun dismiss() {
        // Invoke the onDismissRequest and prevent the PopupWindow from dismissing itself
        onDismissRequest?.invoke()
    }

    fun dismissWithoutNotifying() {
        // Dismiss the PopupWindow
        super.dismiss()
    }
}

// TODO(b/139800142): Add other PopupWindow properties which may be needed
@Immutable
data class PopupProperties(
    /**
     * Indicates if the popup can grab the focus.
     */
    val isFocusable: Boolean = false,
    /**
     * Executes when the popup tries to dismiss itself.
     * This happens when the popup is focusable and the user clicks outside.
     */
    val onDismissRequest: (() -> Unit)? = null
)

private fun updatePopup(popup: PopupWindow, popupPositionProperties: PopupPositionProperties) {
    val popupGlobalPosition = calculatePopupGlobalPosition(
        popupPositionProperties.parentPosition,
        popupPositionProperties.alignment,
        popupPositionProperties.offset,
        popupPositionProperties.parentSize,
        popupPositionProperties.childrenSize
    )

    if (!popup.isShowing) {
        popup.showAtLocation(
            popupPositionProperties.composeView,
            Gravity.NO_GRAVITY,
            popupGlobalPosition.x.value,
            popupGlobalPosition.y.value
        )
    } else {
        popup.update(
            popupGlobalPosition.x.value,
            popupGlobalPosition.y.value,
            /*updateIgnoreWidth = */ -1,
            /*updateIgnoreHeight = */ -1
        )
    }
}

private data class PopupPositionProperties(
    var alignment: Alignment,
    var offset: IntPxPosition,
    val composeView: View
) {
    var parentPosition = PxPosition.Origin
    var parentSize = PxSize.Zero
    var childrenSize = PxSize.Zero
}

internal fun calculatePopupGlobalPosition(
    parentPos: PxPosition,
    alignment: Alignment,
    offset: IntPxPosition,
    parentSize: PxSize,
    popupSize: PxSize
): IntPxPosition {
    // TODO: Decide which is the best way to round to result without reimplementing Alignment.align
    var popupGlobalPosition = IntPxPosition(IntPx.Zero, IntPx.Zero)

    // Get the aligned point inside the parent
    val parentAlignmentPoint = alignment.align(
        IntPxSize(parentSize.width.round(), parentSize.height.round())
    )
    // Get the aligned point inside the child
    val relativePopupPos = alignment.align(
        IntPxSize(popupSize.width.round(), popupSize.height.round())
    )

    // Add the global position of the parent
    popupGlobalPosition += IntPxPosition(parentPos.x.round(), parentPos.y.round())

    // Add the distance between the parent's top left corner and the alignment point
    popupGlobalPosition += parentAlignmentPoint

    // Subtract the distance between the children's top left corner and the alignment point
    popupGlobalPosition -= IntPxPosition(relativePopupPos.x, relativePopupPos.y)

    // Add the user offset
    popupGlobalPosition += offset

    return popupGlobalPosition
}

// TODO(b/140396932): Remove once Activity.disposeComposition() is working properly
/**
 * Disposes the root view of the Activity.
 */
fun disposeActivityComposition(activity: Activity) {
    val composeView = activity.window.decorView
        .findViewById<ViewGroup>(android.R.id.content)
        .getChildAt(0) as? AndroidComposeView
        ?: error("No root view found")

    Compose.disposeComposition(composeView.root, activity, null)
}