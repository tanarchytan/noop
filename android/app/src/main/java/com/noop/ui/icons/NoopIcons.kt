/*
 * Copyright 2025 The Android Open Source Project
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

// The Material icons this app uses that live in material-icons-extended (AOSP, Apache-2.0), copied
// verbatim so the ~11,000-icon library does not ship. Extension properties keep the call sites as
// `Icons.Filled.X`; only the import changed. Core-set icons still come from material-icons-core.
// To add an icon: copy its file from the material-icons-extended sources jar (same builder code).
@file:Suppress("ObjectPropertyName", "unused")

package com.noop.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.AutoMirrored.Filled.Chat: ImageVector
    get() {
        if (_automirrored_filled_Chat != null) {
            return _automirrored_filled_Chat!!
        }
        _automirrored_filled_Chat = materialIcon(name = "AutoMirrored.Filled.Chat", autoMirror = true) {
            materialPath {
                moveTo(20.0f, 2.0f)
                lineTo(4.0f, 2.0f)
                curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                lineTo(2.0f, 22.0f)
                lineToRelative(4.0f, -4.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 4.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(6.0f, 9.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(2.0f)
                lineTo(6.0f, 11.0f)
                lineTo(6.0f, 9.0f)
                close()
                moveTo(14.0f, 14.0f)
                lineTo(6.0f, 14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(18.0f, 8.0f)
                lineTo(6.0f, 8.0f)
                lineTo(6.0f, 6.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _automirrored_filled_Chat!!
    }

private var _automirrored_filled_Chat: ImageVector? = null

public val Icons.AutoMirrored.Filled.CompareArrows: ImageVector
    get() {
        if (_automirrored_filled_CompareArrows != null) {
            return _automirrored_filled_CompareArrows!!
        }
        _automirrored_filled_CompareArrows = materialIcon(name = "AutoMirrored.Filled.CompareArrows", autoMirror =
                true) {
            materialPath {
                moveTo(9.01f, 14.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(7.01f)
                verticalLineToRelative(3.0f)
                lineTo(13.0f, 15.0f)
                lineToRelative(-3.99f, -4.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(14.99f, 13.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(22.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(-7.01f)
                verticalLineTo(5.0f)
                lineTo(11.0f, 9.0f)
                lineTo(14.99f, 13.0f)
                close()
            }
        }
        return _automirrored_filled_CompareArrows!!
    }

private var _automirrored_filled_CompareArrows: ImageVector? = null

public val Icons.AutoMirrored.Filled.DirectionsBike: ImageVector
    get() {
        if (_automirrored_filled_DirectionsBike != null) {
            return _automirrored_filled_DirectionsBike!!
        }
        _automirrored_filled_DirectionsBike = materialIcon(name = "AutoMirrored.Filled.DirectionsBike", autoMirror =
                true) {
            materialPath {
                moveTo(15.5f, 5.5f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(5.0f, 12.0f)
                curveToRelative(-2.8f, 0.0f, -5.0f, 2.2f, -5.0f, 5.0f)
                reflectiveCurveToRelative(2.2f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.2f, 5.0f, -5.0f)
                reflectiveCurveToRelative(-2.2f, -5.0f, -5.0f, -5.0f)
                close()
                moveTo(5.0f, 20.5f)
                curveToRelative(-1.9f, 0.0f, -3.5f, -1.6f, -3.5f, -3.5f)
                reflectiveCurveToRelative(1.6f, -3.5f, 3.5f, -3.5f)
                reflectiveCurveToRelative(3.5f, 1.6f, 3.5f, 3.5f)
                reflectiveCurveToRelative(-1.6f, 3.5f, -3.5f, 3.5f)
                close()
                moveTo(10.8f, 10.5f)
                lineToRelative(2.4f, -2.4f)
                lineToRelative(0.8f, 0.8f)
                curveToRelative(1.3f, 1.3f, 3.0f, 2.1f, 5.1f, 2.1f)
                lineTo(19.1f, 9.0f)
                curveToRelative(-1.5f, 0.0f, -2.7f, -0.6f, -3.6f, -1.5f)
                lineToRelative(-1.9f, -1.9f)
                curveToRelative(-0.5f, -0.4f, -1.0f, -0.6f, -1.6f, -0.6f)
                reflectiveCurveToRelative(-1.1f, 0.2f, -1.4f, 0.6f)
                lineTo(7.8f, 8.4f)
                curveToRelative(-0.4f, 0.4f, -0.6f, 0.9f, -0.6f, 1.4f)
                curveToRelative(0.0f, 0.6f, 0.2f, 1.1f, 0.6f, 1.4f)
                lineTo(11.0f, 14.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-6.2f)
                lineToRelative(-2.2f, -2.3f)
                close()
                moveTo(19.0f, 12.0f)
                curveToRelative(-2.8f, 0.0f, -5.0f, 2.2f, -5.0f, 5.0f)
                reflectiveCurveToRelative(2.2f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.2f, 5.0f, -5.0f)
                reflectiveCurveToRelative(-2.2f, -5.0f, -5.0f, -5.0f)
                close()
                moveTo(19.0f, 20.5f)
                curveToRelative(-1.9f, 0.0f, -3.5f, -1.6f, -3.5f, -3.5f)
                reflectiveCurveToRelative(1.6f, -3.5f, 3.5f, -3.5f)
                reflectiveCurveToRelative(3.5f, 1.6f, 3.5f, 3.5f)
                reflectiveCurveToRelative(-1.6f, 3.5f, -3.5f, 3.5f)
                close()
            }
        }
        return _automirrored_filled_DirectionsBike!!
    }

private var _automirrored_filled_DirectionsBike: ImageVector? = null

public val Icons.AutoMirrored.Filled.DirectionsRun: ImageVector
    get() {
        if (_automirrored_filled_DirectionsRun != null) {
            return _automirrored_filled_DirectionsRun!!
        }
        _automirrored_filled_DirectionsRun = materialIcon(name = "AutoMirrored.Filled.DirectionsRun", autoMirror =
                true) {
            materialPath {
                moveTo(13.49f, 5.48f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(9.89f, 19.38f)
                lineToRelative(1.0f, -4.4f)
                lineToRelative(2.1f, 2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-7.5f)
                lineToRelative(-2.1f, -2.0f)
                lineToRelative(0.6f, -3.0f)
                curveToRelative(1.3f, 1.5f, 3.3f, 2.5f, 5.5f, 2.5f)
                verticalLineToRelative(-2.0f)
                curveToRelative(-1.9f, 0.0f, -3.5f, -1.0f, -4.3f, -2.4f)
                lineToRelative(-1.0f, -1.6f)
                curveToRelative(-0.4f, -0.6f, -1.0f, -1.0f, -1.7f, -1.0f)
                curveToRelative(-0.3f, 0.0f, -0.5f, 0.1f, -0.8f, 0.1f)
                lineToRelative(-5.2f, 2.2f)
                verticalLineToRelative(4.7f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-3.4f)
                lineToRelative(1.8f, -0.7f)
                lineToRelative(-1.6f, 8.1f)
                lineToRelative(-4.9f, -1.0f)
                lineToRelative(-0.4f, 2.0f)
                lineToRelative(7.0f, 1.4f)
                close()
            }
        }
        return _automirrored_filled_DirectionsRun!!
    }

private var _automirrored_filled_DirectionsRun: ImageVector? = null

public val Icons.AutoMirrored.Filled.DirectionsWalk: ImageVector
    get() {
        if (_automirrored_filled_DirectionsWalk != null) {
            return _automirrored_filled_DirectionsWalk!!
        }
        _automirrored_filled_DirectionsWalk = materialIcon(name = "AutoMirrored.Filled.DirectionsWalk", autoMirror =
                true) {
            materialPath {
                moveTo(13.5f, 5.5f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(9.8f, 8.9f)
                lineTo(7.0f, 23.0f)
                horizontalLineToRelative(2.1f)
                lineToRelative(1.8f, -8.0f)
                lineToRelative(2.1f, 2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-7.5f)
                lineToRelative(-2.1f, -2.0f)
                lineToRelative(0.6f, -3.0f)
                curveTo(14.8f, 12.0f, 16.8f, 13.0f, 19.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(-1.9f, 0.0f, -3.5f, -1.0f, -4.3f, -2.4f)
                lineToRelative(-1.0f, -1.6f)
                curveToRelative(-0.4f, -0.6f, -1.0f, -1.0f, -1.7f, -1.0f)
                curveToRelative(-0.3f, 0.0f, -0.5f, 0.1f, -0.8f, 0.1f)
                lineTo(6.0f, 8.3f)
                verticalLineTo(13.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(9.6f)
                lineToRelative(1.8f, -0.7f)
            }
        }
        return _automirrored_filled_DirectionsWalk!!
    }

private var _automirrored_filled_DirectionsWalk: ImageVector? = null

public val Icons.AutoMirrored.Filled.MergeType: ImageVector
    get() {
        if (_automirrored_filled_MergeType != null) {
            return _automirrored_filled_MergeType!!
        }
        _automirrored_filled_MergeType = materialIcon(name = "AutoMirrored.Filled.MergeType", autoMirror = true) {
            materialPath {
                moveTo(17.0f, 20.41f)
                lineTo(18.41f, 19.0f)
                lineTo(15.0f, 15.59f)
                lineTo(13.59f, 17.0f)
                lineTo(17.0f, 20.41f)
                close()
                moveTo(7.5f, 8.0f)
                horizontalLineTo(11.0f)
                verticalLineToRelative(5.59f)
                lineTo(5.59f, 19.0f)
                lineTo(7.0f, 20.41f)
                lineToRelative(6.0f, -6.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(3.5f)
                lineTo(12.0f, 3.5f)
                lineTo(7.5f, 8.0f)
                close()
            }
        }
        return _automirrored_filled_MergeType!!
    }

private var _automirrored_filled_MergeType: ImageVector? = null

public val Icons.AutoMirrored.Filled.OpenInNew: ImageVector
    get() {
        if (_automirrored_filled_OpenInNew != null) {
            return _automirrored_filled_OpenInNew!!
        }
        _automirrored_filled_OpenInNew = materialIcon(name = "AutoMirrored.Filled.OpenInNew", autoMirror = true) {
            materialPath {
                moveTo(19.0f, 19.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(7.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(5.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(7.0f)
                close()
                moveTo(14.0f, 3.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.59f)
                lineToRelative(-9.83f, 9.83f)
                lineToRelative(1.41f, 1.41f)
                lineTo(19.0f, 6.41f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(3.0f)
                horizontalLineToRelative(-7.0f)
                close()
            }
        }
        return _automirrored_filled_OpenInNew!!
    }

private var _automirrored_filled_OpenInNew: ImageVector? = null

public val Icons.AutoMirrored.Filled.ShowChart: ImageVector
    get() {
        if (_automirrored_filled_ShowChart != null) {
            return _automirrored_filled_ShowChart!!
        }
        _automirrored_filled_ShowChart = materialIcon(name = "AutoMirrored.Filled.ShowChart", autoMirror = true) {
            materialPath {
                moveTo(3.5f, 18.49f)
                lineToRelative(6.0f, -6.01f)
                lineToRelative(4.0f, 4.0f)
                lineTo(22.0f, 6.92f)
                lineToRelative(-1.41f, -1.41f)
                lineToRelative(-7.09f, 7.97f)
                lineToRelative(-4.0f, -4.0f)
                lineTo(2.0f, 16.99f)
                close()
            }
        }
        return _automirrored_filled_ShowChart!!
    }

private var _automirrored_filled_ShowChart: ImageVector? = null

public val Icons.AutoMirrored.Filled.TrendingUp: ImageVector
    get() {
        if (_automirrored_filled_TrendingUp != null) {
            return _automirrored_filled_TrendingUp!!
        }
        _automirrored_filled_TrendingUp = materialIcon(name = "AutoMirrored.Filled.TrendingUp", autoMirror = true) {
            materialPath {
                moveTo(16.0f, 6.0f)
                lineToRelative(2.29f, 2.29f)
                lineToRelative(-4.88f, 4.88f)
                lineToRelative(-4.0f, -4.0f)
                lineTo(2.0f, 16.59f)
                lineTo(3.41f, 18.0f)
                lineToRelative(6.0f, -6.0f)
                lineToRelative(4.0f, 4.0f)
                lineToRelative(6.3f, -6.29f)
                lineTo(22.0f, 12.0f)
                verticalLineTo(6.0f)
                close()
            }
        }
        return _automirrored_filled_TrendingUp!!
    }

private var _automirrored_filled_TrendingUp: ImageVector? = null

public val Icons.AutoMirrored.Filled.Undo: ImageVector
    get() {
        if (_automirrored_filled_Undo != null) {
            return _automirrored_filled_Undo!!
        }
        _automirrored_filled_Undo = materialIcon(name = "AutoMirrored.Filled.Undo", autoMirror = true) {
            materialPath {
                moveTo(12.5f, 8.0f)
                curveToRelative(-2.65f, 0.0f, -5.05f, 0.99f, -6.9f, 2.6f)
                lineTo(2.0f, 7.0f)
                verticalLineToRelative(9.0f)
                horizontalLineToRelative(9.0f)
                lineToRelative(-3.62f, -3.62f)
                curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
                curveToRelative(3.54f, 0.0f, 6.55f, 2.31f, 7.6f, 5.5f)
                lineToRelative(2.37f, -0.78f)
                curveTo(21.08f, 11.03f, 17.15f, 8.0f, 12.5f, 8.0f)
                close()
            }
        }
        return _automirrored_filled_Undo!!
    }

private var _automirrored_filled_Undo: ImageVector? = null

public val Icons.AutoMirrored.Filled.VolumeOff: ImageVector
    get() {
        if (_automirrored_filled_VolumeOff != null) {
            return _automirrored_filled_VolumeOff!!
        }
        _automirrored_filled_VolumeOff = materialIcon(name = "AutoMirrored.Filled.VolumeOff", autoMirror = true) {
            materialPath {
                moveTo(16.5f, 12.0f)
                curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(2.21f)
                lineToRelative(2.45f, 2.45f)
                curveToRelative(0.03f, -0.2f, 0.05f, -0.41f, 0.05f, -0.63f)
                close()
                moveTo(19.0f, 12.0f)
                curveToRelative(0.0f, 0.94f, -0.2f, 1.82f, -0.54f, 2.64f)
                lineToRelative(1.51f, 1.51f)
                curveTo(20.63f, 14.91f, 21.0f, 13.5f, 21.0f, 12.0f)
                curveToRelative(0.0f, -4.28f, -2.99f, -7.86f, -7.0f, -8.77f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
                close()
                moveTo(4.27f, 3.0f)
                lineTo(3.0f, 4.27f)
                lineTo(7.73f, 9.0f)
                lineTo(3.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(5.0f, 5.0f)
                verticalLineToRelative(-6.73f)
                lineToRelative(4.25f, 4.25f)
                curveToRelative(-0.67f, 0.52f, -1.42f, 0.93f, -2.25f, 1.18f)
                verticalLineToRelative(2.06f)
                curveToRelative(1.38f, -0.31f, 2.63f, -0.95f, 3.69f, -1.81f)
                lineTo(19.73f, 21.0f)
                lineTo(21.0f, 19.73f)
                lineToRelative(-9.0f, -9.0f)
                lineTo(4.27f, 3.0f)
                close()
                moveTo(12.0f, 4.0f)
                lineTo(9.91f, 6.09f)
                lineTo(12.0f, 8.18f)
                lineTo(12.0f, 4.0f)
                close()
            }
        }
        return _automirrored_filled_VolumeOff!!
    }

private var _automirrored_filled_VolumeOff: ImageVector? = null

public val Icons.AutoMirrored.Filled.VolumeUp: ImageVector
    get() {
        if (_automirrored_filled_VolumeUp != null) {
            return _automirrored_filled_VolumeUp!!
        }
        _automirrored_filled_VolumeUp = materialIcon(name = "AutoMirrored.Filled.VolumeUp", autoMirror = true) {
            materialPath {
                moveTo(3.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(5.0f, 5.0f)
                lineTo(12.0f, 4.0f)
                lineTo(7.0f, 9.0f)
                lineTo(3.0f, 9.0f)
                close()
                moveTo(16.5f, 12.0f)
                curveToRelative(0.0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                close()
                moveTo(14.0f, 3.23f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5.0f, 3.54f, 5.0f, 6.71f)
                reflectiveCurveToRelative(-2.11f, 5.85f, -5.0f, 6.71f)
                verticalLineToRelative(2.06f)
                curveToRelative(4.01f, -0.91f, 7.0f, -4.49f, 7.0f, -8.77f)
                reflectiveCurveToRelative(-2.99f, -7.86f, -7.0f, -8.77f)
                close()
            }
        }
        return _automirrored_filled_VolumeUp!!
    }

private var _automirrored_filled_VolumeUp: ImageVector? = null

public val Icons.AutoMirrored.Outlined.Undo: ImageVector
    get() {
        if (_automirrored_outlined_Undo != null) {
            return _automirrored_outlined_Undo!!
        }
        _automirrored_outlined_Undo = materialIcon(name = "AutoMirrored.Outlined.Undo", autoMirror = true) {
            materialPath {
                moveTo(12.5f, 8.0f)
                curveToRelative(-2.65f, 0.0f, -5.05f, 0.99f, -6.9f, 2.6f)
                lineTo(2.0f, 7.0f)
                verticalLineToRelative(9.0f)
                horizontalLineToRelative(9.0f)
                lineToRelative(-3.62f, -3.62f)
                curveToRelative(1.39f, -1.16f, 3.16f, -1.88f, 5.12f, -1.88f)
                curveToRelative(3.54f, 0.0f, 6.55f, 2.31f, 7.6f, 5.5f)
                lineToRelative(2.37f, -0.78f)
                curveTo(21.08f, 11.03f, 17.15f, 8.0f, 12.5f, 8.0f)
                close()
            }
        }
        return _automirrored_outlined_Undo!!
    }

private var _automirrored_outlined_Undo: ImageVector? = null

public val Icons.Filled.Air: ImageVector
    get() {
        if (_filled_Air != null) {
            return _filled_Air!!
        }
        _filled_Air = materialIcon(name = "Filled.Air") {
            materialPath {
                moveTo(14.5f, 17.0f)
                curveToRelative(0.0f, 1.65f, -1.35f, 3.0f, -3.0f, 3.0f)
                reflectiveCurveToRelative(-3.0f, -1.35f, -3.0f, -3.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                reflectiveCurveToRelative(1.0f, -0.45f, 1.0f, -1.0f)
                reflectiveCurveToRelative(-0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(9.5f)
                curveTo(13.15f, 14.0f, 14.5f, 15.35f, 14.5f, 17.0f)
                close()
                moveTo(19.0f, 6.5f)
                curveTo(19.0f, 4.57f, 17.43f, 3.0f, 15.5f, 3.0f)
                reflectiveCurveTo(12.0f, 4.57f, 12.0f, 6.5f)
                horizontalLineToRelative(2.0f)
                curveTo(14.0f, 5.67f, 14.67f, 5.0f, 15.5f, 5.0f)
                reflectiveCurveTo(17.0f, 5.67f, 17.0f, 6.5f)
                reflectiveCurveTo(16.33f, 8.0f, 15.5f, 8.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(13.5f)
                curveTo(17.43f, 10.0f, 19.0f, 8.43f, 19.0f, 6.5f)
                close()
                moveTo(18.5f, 11.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(16.5f)
                curveToRelative(0.83f, 0.0f, 1.5f, 0.67f, 1.5f, 1.5f)
                reflectiveCurveTo(19.33f, 16.0f, 18.5f, 16.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(1.93f, 0.0f, 3.5f, -1.57f, 3.5f, -3.5f)
                reflectiveCurveTo(20.43f, 11.0f, 18.5f, 11.0f)
                close()
            }
        }
        return _filled_Air!!
    }

private var _filled_Air: ImageVector? = null

public val Icons.Filled.Alarm: ImageVector
    get() {
        if (_filled_Alarm != null) {
            return _filled_Alarm!!
        }
        _filled_Alarm = materialIcon(name = "Filled.Alarm") {
            materialPath {
                moveTo(22.0f, 5.72f)
                lineToRelative(-4.6f, -3.86f)
                lineToRelative(-1.29f, 1.53f)
                lineToRelative(4.6f, 3.86f)
                lineTo(22.0f, 5.72f)
                close()
                moveTo(7.88f, 3.39f)
                lineTo(6.6f, 1.86f)
                lineTo(2.0f, 5.71f)
                lineToRelative(1.29f, 1.53f)
                lineToRelative(4.59f, -3.85f)
                close()
                moveTo(12.5f, 8.0f)
                lineTo(11.0f, 8.0f)
                verticalLineToRelative(6.0f)
                lineToRelative(4.75f, 2.85f)
                lineToRelative(0.75f, -1.23f)
                lineToRelative(-4.0f, -2.37f)
                lineTo(12.5f, 8.0f)
                close()
                moveTo(12.0f, 4.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                reflectiveCurveToRelative(4.02f, 9.0f, 9.0f, 9.0f)
                curveToRelative(4.97f, 0.0f, 9.0f, -4.03f, 9.0f, -9.0f)
                reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-3.87f, 0.0f, -7.0f, -3.13f, -7.0f, -7.0f)
                reflectiveCurveToRelative(3.13f, -7.0f, 7.0f, -7.0f)
                reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
                reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
                close()
            }
        }
        return _filled_Alarm!!
    }

private var _filled_Alarm: ImageVector? = null

public val Icons.Filled.ArrowDownward: ImageVector
    get() {
        if (_filled_ArrowDownward != null) {
            return _filled_ArrowDownward!!
        }
        _filled_ArrowDownward = materialIcon(name = "Filled.ArrowDownward") {
            materialPath {
                moveTo(20.0f, 12.0f)
                lineToRelative(-1.41f, -1.41f)
                lineTo(13.0f, 16.17f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(12.17f)
                lineToRelative(-5.58f, -5.59f)
                lineTo(4.0f, 12.0f)
                lineToRelative(8.0f, 8.0f)
                lineToRelative(8.0f, -8.0f)
                close()
            }
        }
        return _filled_ArrowDownward!!
    }

private var _filled_ArrowDownward: ImageVector? = null

public val Icons.Filled.ArrowDropUp: ImageVector
    get() {
        if (_filled_ArrowDropUp != null) {
            return _filled_ArrowDropUp!!
        }
        _filled_ArrowDropUp = materialIcon(name = "Filled.ArrowDropUp") {
            materialPath {
                moveTo(7.0f, 14.0f)
                lineToRelative(5.0f, -5.0f)
                lineToRelative(5.0f, 5.0f)
                close()
            }
        }
        return _filled_ArrowDropUp!!
    }

private var _filled_ArrowDropUp: ImageVector? = null

public val Icons.Filled.ArrowUpward: ImageVector
    get() {
        if (_filled_ArrowUpward != null) {
            return _filled_ArrowUpward!!
        }
        _filled_ArrowUpward = materialIcon(name = "Filled.ArrowUpward") {
            materialPath {
                moveTo(4.0f, 12.0f)
                lineToRelative(1.41f, 1.41f)
                lineTo(11.0f, 7.83f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(7.83f)
                lineToRelative(5.58f, 5.59f)
                lineTo(20.0f, 12.0f)
                lineToRelative(-8.0f, -8.0f)
                lineToRelative(-8.0f, 8.0f)
                close()
            }
        }
        return _filled_ArrowUpward!!
    }

private var _filled_ArrowUpward: ImageVector? = null

public val Icons.Filled.AutoAwesome: ImageVector
    get() {
        if (_filled_AutoAwesome != null) {
            return _filled_AutoAwesome!!
        }
        _filled_AutoAwesome = materialIcon(name = "Filled.AutoAwesome") {
            materialPath {
                moveTo(19.0f, 9.0f)
                lineToRelative(1.25f, -2.75f)
                lineTo(23.0f, 5.0f)
                lineToRelative(-2.75f, -1.25f)
                lineTo(19.0f, 1.0f)
                lineToRelative(-1.25f, 2.75f)
                lineTo(15.0f, 5.0f)
                lineToRelative(2.75f, 1.25f)
                lineTo(19.0f, 9.0f)
                close()
                moveTo(11.5f, 9.5f)
                lineTo(9.0f, 4.0f)
                lineTo(6.5f, 9.5f)
                lineTo(1.0f, 12.0f)
                lineToRelative(5.5f, 2.5f)
                lineTo(9.0f, 20.0f)
                lineToRelative(2.5f, -5.5f)
                lineTo(17.0f, 12.0f)
                lineToRelative(-5.5f, -2.5f)
                close()
                moveTo(19.0f, 15.0f)
                lineToRelative(-1.25f, 2.75f)
                lineTo(15.0f, 19.0f)
                lineToRelative(2.75f, 1.25f)
                lineTo(19.0f, 23.0f)
                lineToRelative(1.25f, -2.75f)
                lineTo(23.0f, 19.0f)
                lineToRelative(-2.75f, -1.25f)
                lineTo(19.0f, 15.0f)
                close()
            }
        }
        return _filled_AutoAwesome!!
    }

private var _filled_AutoAwesome: ImageVector? = null

public val Icons.Filled.AutoGraph: ImageVector
    get() {
        if (_filled_AutoGraph != null) {
            return _filled_AutoGraph!!
        }
        _filled_AutoGraph = materialIcon(name = "Filled.AutoGraph") {
            materialPath {
                moveTo(14.06f, 9.94f)
                lineTo(12.0f, 9.0f)
                lineToRelative(2.06f, -0.94f)
                lineTo(15.0f, 6.0f)
                lineToRelative(0.94f, 2.06f)
                lineTo(18.0f, 9.0f)
                lineToRelative(-2.06f, 0.94f)
                lineTo(15.0f, 12.0f)
                lineTo(14.06f, 9.94f)
                close()
                moveTo(4.0f, 14.0f)
                lineToRelative(0.94f, -2.06f)
                lineTo(7.0f, 11.0f)
                lineToRelative(-2.06f, -0.94f)
                lineTo(4.0f, 8.0f)
                lineToRelative(-0.94f, 2.06f)
                lineTo(1.0f, 11.0f)
                lineToRelative(2.06f, 0.94f)
                lineTo(4.0f, 14.0f)
                close()
                moveTo(8.5f, 9.0f)
                lineToRelative(1.09f, -2.41f)
                lineTo(12.0f, 5.5f)
                lineTo(9.59f, 4.41f)
                lineTo(8.5f, 2.0f)
                lineTo(7.41f, 4.41f)
                lineTo(5.0f, 5.5f)
                lineToRelative(2.41f, 1.09f)
                lineTo(8.5f, 9.0f)
                close()
                moveTo(4.5f, 20.5f)
                lineToRelative(6.0f, -6.01f)
                lineToRelative(4.0f, 4.0f)
                lineTo(23.0f, 8.93f)
                lineToRelative(-1.41f, -1.41f)
                lineToRelative(-7.09f, 7.97f)
                lineToRelative(-4.0f, -4.0f)
                lineTo(3.0f, 19.0f)
                lineTo(4.5f, 20.5f)
                close()
            }
        }
        return _filled_AutoGraph!!
    }

private var _filled_AutoGraph: ImageVector? = null

public val Icons.Filled.Autorenew: ImageVector
    get() {
        if (_filled_Autorenew != null) {
            return _filled_Autorenew!!
        }
        _filled_Autorenew = materialIcon(name = "Filled.Autorenew") {
            materialPath {
                moveTo(12.0f, 6.0f)
                verticalLineToRelative(3.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                verticalLineToRelative(3.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, 3.58f, -8.0f, 8.0f)
                curveToRelative(0.0f, 1.57f, 0.46f, 3.03f, 1.24f, 4.26f)
                lineTo(6.7f, 14.8f)
                curveToRelative(-0.45f, -0.83f, -0.7f, -1.79f, -0.7f, -2.8f)
                curveToRelative(0.0f, -3.31f, 2.69f, -6.0f, 6.0f, -6.0f)
                close()
                moveTo(18.76f, 7.74f)
                lineTo(17.3f, 9.2f)
                curveToRelative(0.44f, 0.84f, 0.7f, 1.79f, 0.7f, 2.8f)
                curveToRelative(0.0f, 3.31f, -2.69f, 6.0f, -6.0f, 6.0f)
                verticalLineToRelative(-3.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(4.0f, 4.0f)
                verticalLineToRelative(-3.0f)
                curveToRelative(4.42f, 0.0f, 8.0f, -3.58f, 8.0f, -8.0f)
                curveToRelative(0.0f, -1.57f, -0.46f, -3.03f, -1.24f, -4.26f)
                close()
            }
        }
        return _filled_Autorenew!!
    }

private var _filled_Autorenew: ImageVector? = null

public val Icons.Filled.BatteryStd: ImageVector
    get() {
        if (_filled_BatteryStd != null) {
            return _filled_BatteryStd!!
        }
        _filled_BatteryStd = materialIcon(name = "Filled.BatteryStd") {
            materialPath {
                moveTo(15.67f, 4.0f)
                horizontalLineTo(14.0f)
                verticalLineTo(2.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(8.33f)
                curveTo(7.6f, 4.0f, 7.0f, 4.6f, 7.0f, 5.33f)
                verticalLineToRelative(15.33f)
                curveTo(7.0f, 21.4f, 7.6f, 22.0f, 8.33f, 22.0f)
                horizontalLineToRelative(7.33f)
                curveToRelative(0.74f, 0.0f, 1.34f, -0.6f, 1.34f, -1.33f)
                verticalLineTo(5.33f)
                curveTo(17.0f, 4.6f, 16.4f, 4.0f, 15.67f, 4.0f)
                close()
            }
        }
        return _filled_BatteryStd!!
    }

private var _filled_BatteryStd: ImageVector? = null

public val Icons.Filled.Bedtime: ImageVector
    get() {
        if (_filled_Bedtime != null) {
            return _filled_Bedtime!!
        }
        _filled_Bedtime = materialIcon(name = "Filled.Bedtime") {
            materialPath {
                moveTo(12.34f, 2.02f)
                curveTo(6.59f, 1.82f, 2.0f, 6.42f, 2.0f, 12.0f)
                curveToRelative(0.0f, 5.52f, 4.48f, 10.0f, 10.0f, 10.0f)
                curveToRelative(3.71f, 0.0f, 6.93f, -2.02f, 8.66f, -5.02f)
                curveTo(13.15f, 16.73f, 8.57f, 8.55f, 12.34f, 2.02f)
                close()
            }
        }
        return _filled_Bedtime!!
    }

private var _filled_Bedtime: ImageVector? = null

public val Icons.Filled.Bluetooth: ImageVector
    get() {
        if (_filled_Bluetooth != null) {
            return _filled_Bluetooth!!
        }
        _filled_Bluetooth = materialIcon(name = "Filled.Bluetooth") {
            materialPath {
                moveTo(17.71f, 7.71f)
                lineTo(12.0f, 2.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(7.59f)
                lineTo(6.41f, 5.0f)
                lineTo(5.0f, 6.41f)
                lineTo(10.59f, 12.0f)
                lineTo(5.0f, 17.59f)
                lineTo(6.41f, 19.0f)
                lineTo(11.0f, 14.41f)
                lineTo(11.0f, 22.0f)
                horizontalLineToRelative(1.0f)
                lineToRelative(5.71f, -5.71f)
                lineToRelative(-4.3f, -4.29f)
                lineToRelative(4.3f, -4.29f)
                close()
                moveTo(13.0f, 5.83f)
                lineToRelative(1.88f, 1.88f)
                lineTo(13.0f, 9.59f)
                lineTo(13.0f, 5.83f)
                close()
                moveTo(14.88f, 16.29f)
                lineTo(13.0f, 18.17f)
                verticalLineToRelative(-3.76f)
                lineToRelative(1.88f, 1.88f)
                close()
            }
        }
        return _filled_Bluetooth!!
    }

private var _filled_Bluetooth: ImageVector? = null

public val Icons.Filled.Bolt: ImageVector
    get() {
        if (_filled_Bolt != null) {
            return _filled_Bolt!!
        }
        _filled_Bolt = materialIcon(name = "Filled.Bolt") {
            materialPath {
                moveTo(11.0f, 21.0f)
                horizontalLineToRelative(-1.0f)
                lineToRelative(1.0f, -7.0f)
                horizontalLineTo(7.5f)
                curveToRelative(-0.58f, 0.0f, -0.57f, -0.32f, -0.38f, -0.66f)
                curveToRelative(0.19f, -0.34f, 0.05f, -0.08f, 0.07f, -0.12f)
                curveTo(8.48f, 10.94f, 10.42f, 7.54f, 13.0f, 3.0f)
                horizontalLineToRelative(1.0f)
                lineToRelative(-1.0f, 7.0f)
                horizontalLineToRelative(3.5f)
                curveToRelative(0.49f, 0.0f, 0.56f, 0.33f, 0.47f, 0.51f)
                lineToRelative(-0.07f, 0.15f)
                curveTo(12.96f, 17.55f, 11.0f, 21.0f, 11.0f, 21.0f)
                close()
            }
        }
        return _filled_Bolt!!
    }

private var _filled_Bolt: ImageVector? = null

public val Icons.Filled.Brightness6: ImageVector
    get() {
        if (_filled_Brightness6 != null) {
            return _filled_Brightness6!!
        }
        _filled_Brightness6 = materialIcon(name = "Filled.Brightness6") {
            materialPath {
                moveTo(20.0f, 15.31f)
                lineTo(23.31f, 12.0f)
                lineTo(20.0f, 8.69f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-4.69f)
                lineTo(12.0f, 0.69f)
                lineTo(8.69f, 4.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(4.69f)
                lineTo(0.69f, 12.0f)
                lineTo(4.0f, 15.31f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(4.69f)
                lineTo(12.0f, 23.31f)
                lineTo(15.31f, 20.0f)
                horizontalLineTo(20.0f)
                verticalLineToRelative(-4.69f)
                close()
                moveTo(12.0f, 18.0f)
                verticalLineTo(6.0f)
                curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
                close()
            }
        }
        return _filled_Brightness6!!
    }

private var _filled_Brightness6: ImageVector? = null

public val Icons.Filled.BugReport: ImageVector
    get() {
        if (_filled_BugReport != null) {
            return _filled_BugReport!!
        }
        _filled_BugReport = materialIcon(name = "Filled.BugReport") {
            materialPath {
                moveTo(20.0f, 8.0f)
                horizontalLineToRelative(-2.81f)
                curveToRelative(-0.45f, -0.78f, -1.07f, -1.45f, -1.82f, -1.96f)
                lineTo(17.0f, 4.41f)
                lineTo(15.59f, 3.0f)
                lineToRelative(-2.17f, 2.17f)
                curveTo(12.96f, 5.06f, 12.49f, 5.0f, 12.0f, 5.0f)
                curveToRelative(-0.49f, 0.0f, -0.96f, 0.06f, -1.41f, 0.17f)
                lineTo(8.41f, 3.0f)
                lineTo(7.0f, 4.41f)
                lineToRelative(1.62f, 1.63f)
                curveTo(7.88f, 6.55f, 7.26f, 7.22f, 6.81f, 8.0f)
                lineTo(4.0f, 8.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.09f)
                curveToRelative(-0.05f, 0.33f, -0.09f, 0.66f, -0.09f, 1.0f)
                verticalLineToRelative(1.0f)
                lineTo(4.0f, 12.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(1.0f)
                curveToRelative(0.0f, 0.34f, 0.04f, 0.67f, 0.09f, 1.0f)
                lineTo(4.0f, 16.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.81f)
                curveToRelative(1.04f, 1.79f, 2.97f, 3.0f, 5.19f, 3.0f)
                reflectiveCurveToRelative(4.15f, -1.21f, 5.19f, -3.0f)
                lineTo(20.0f, 18.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.09f)
                curveToRelative(0.05f, -0.33f, 0.09f, -0.66f, 0.09f, -1.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -0.34f, -0.04f, -0.67f, -0.09f, -1.0f)
                lineTo(20.0f, 10.0f)
                lineTo(20.0f, 8.0f)
                close()
                moveTo(14.0f, 16.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(14.0f, 12.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _filled_BugReport!!
    }

private var _filled_BugReport: ImageVector? = null

public val Icons.Filled.CalendarMonth: ImageVector
    get() {
        if (_filled_CalendarMonth != null) {
            return _filled_CalendarMonth!!
        }
        _filled_CalendarMonth = materialIcon(name = "Filled.CalendarMonth") {
            materialPath {
                moveTo(19.0f, 4.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineTo(2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(2.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(5.0f)
                curveTo(3.89f, 4.0f, 3.01f, 4.9f, 3.01f, 6.0f)
                lineTo(3.0f, 20.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(6.0f)
                curveTo(21.0f, 4.9f, 20.1f, 4.0f, 19.0f, 4.0f)
                close()
                moveTo(19.0f, 20.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(14.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(9.0f, 14.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(13.0f, 14.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(17.0f, 14.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
                moveTo(9.0f, 18.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
                moveTo(13.0f, 18.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
                moveTo(17.0f, 18.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
            }
        }
        return _filled_CalendarMonth!!
    }

private var _filled_CalendarMonth: ImageVector? = null

public val Icons.Filled.CheckBox: ImageVector
    get() {
        if (_filled_CheckBox != null) {
            return _filled_CheckBox!!
        }
        _filled_CheckBox = materialIcon(name = "Filled.CheckBox") {
            materialPath {
                moveTo(19.0f, 3.0f)
                lineTo(5.0f, 3.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.11f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(21.0f, 5.0f)
                curveToRelative(0.0f, -1.1f, -0.89f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(10.0f, 17.0f)
                lineToRelative(-5.0f, -5.0f)
                lineToRelative(1.41f, -1.41f)
                lineTo(10.0f, 14.17f)
                lineToRelative(7.59f, -7.59f)
                lineTo(19.0f, 8.0f)
                lineToRelative(-9.0f, 9.0f)
                close()
            }
        }
        return _filled_CheckBox!!
    }

private var _filled_CheckBox: ImageVector? = null

public val Icons.Filled.CheckBoxOutlineBlank: ImageVector
    get() {
        if (_filled_CheckBoxOutlineBlank != null) {
            return _filled_CheckBoxOutlineBlank!!
        }
        _filled_CheckBoxOutlineBlank = materialIcon(name = "Filled.CheckBoxOutlineBlank") {
            materialPath {
                moveTo(19.0f, 5.0f)
                verticalLineToRelative(14.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(14.0f)
                moveToRelative(0.0f, -2.0f)
                horizontalLineTo(5.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(14.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(14.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
            }
        }
        return _filled_CheckBoxOutlineBlank!!
    }

private var _filled_CheckBoxOutlineBlank: ImageVector? = null

public val Icons.Filled.ChevronLeft: ImageVector
    get() {
        if (_filled_ChevronLeft != null) {
            return _filled_ChevronLeft!!
        }
        _filled_ChevronLeft = materialIcon(name = "Filled.ChevronLeft") {
            materialPath {
                moveTo(15.41f, 7.41f)
                lineTo(14.0f, 6.0f)
                lineToRelative(-6.0f, 6.0f)
                lineToRelative(6.0f, 6.0f)
                lineToRelative(1.41f, -1.41f)
                lineTo(10.83f, 12.0f)
                close()
            }
        }
        return _filled_ChevronLeft!!
    }

private var _filled_ChevronLeft: ImageVector? = null

public val Icons.Filled.ChevronRight: ImageVector
    get() {
        if (_filled_ChevronRight != null) {
            return _filled_ChevronRight!!
        }
        _filled_ChevronRight = materialIcon(name = "Filled.ChevronRight") {
            materialPath {
                moveTo(10.0f, 6.0f)
                lineTo(8.59f, 7.41f)
                lineTo(13.17f, 12.0f)
                lineToRelative(-4.58f, 4.59f)
                lineTo(10.0f, 18.0f)
                lineToRelative(6.0f, -6.0f)
                close()
            }
        }
        return _filled_ChevronRight!!
    }

private var _filled_ChevronRight: ImageVector? = null

public val Icons.Filled.Circle: ImageVector
    get() {
        if (_filled_Circle != null) {
            return _filled_Circle!!
        }
        _filled_Circle = materialIcon(name = "Filled.Circle") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.47f, 2.0f, 2.0f, 6.47f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.47f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.47f, 10.0f, -10.0f)
                reflectiveCurveTo(17.53f, 2.0f, 12.0f, 2.0f)
                close()
            }
        }
        return _filled_Circle!!
    }

private var _filled_Circle: ImageVector? = null

public val Icons.Filled.CloudSync: ImageVector
    get() {
        if (_filled_CloudSync != null) {
            return _filled_CloudSync!!
        }
        _filled_CloudSync = materialIcon(name = "Filled.CloudSync") {
            materialPath {
                moveTo(21.5f, 14.98f)
                curveToRelative(-0.02f, 0.0f, -0.03f, 0.0f, -0.05f, 0.01f)
                curveTo(21.2f, 13.3f, 19.76f, 12.0f, 18.0f, 12.0f)
                curveToRelative(-1.4f, 0.0f, -2.6f, 0.83f, -3.16f, 2.02f)
                curveTo(13.26f, 14.1f, 12.0f, 15.4f, 12.0f, 17.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                lineToRelative(6.5f, -0.02f)
                curveToRelative(1.38f, 0.0f, 2.5f, -1.12f, 2.5f, -2.5f)
                reflectiveCurveTo(22.88f, 14.98f, 21.5f, 14.98f)
                close()
                moveTo(10.0f, 4.26f)
                verticalLineToRelative(2.09f)
                curveTo(7.67f, 7.18f, 6.0f, 9.39f, 6.0f, 12.0f)
                curveToRelative(0.0f, 1.77f, 0.78f, 3.34f, 2.0f, 4.44f)
                verticalLineTo(14.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.73f)
                curveTo(5.06f, 16.54f, 4.0f, 14.4f, 4.0f, 12.0f)
                curveTo(4.0f, 8.27f, 6.55f, 5.15f, 10.0f, 4.26f)
                close()
                moveTo(20.0f, 6.0f)
                horizontalLineToRelative(-2.73f)
                curveToRelative(1.43f, 1.26f, 2.41f, 3.01f, 2.66f, 5.0f)
                lineToRelative(-2.02f, 0.0f)
                curveTo(17.68f, 9.64f, 16.98f, 8.45f, 16.0f, 7.56f)
                verticalLineTo(10.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(6.0f)
                close()
            }
        }
        return _filled_CloudSync!!
    }

private var _filled_CloudSync: ImageVector? = null

public val Icons.Filled.CloudUpload: ImageVector
    get() {
        if (_filled_CloudUpload != null) {
            return _filled_CloudUpload!!
        }
        _filled_CloudUpload = materialIcon(name = "Filled.CloudUpload") {
            materialPath {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4.0f, 12.0f, 4.0f)
                curveTo(9.11f, 4.0f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0.0f, 10.91f, 0.0f, 14.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                horizontalLineToRelative(13.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, -2.24f, 5.0f, -5.0f)
                curveToRelative(0.0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(14.0f, 13.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineTo(7.0f)
                lineToRelative(5.0f, -5.0f)
                lineToRelative(5.0f, 5.0f)
                horizontalLineToRelative(-3.0f)
                close()
            }
        }
        return _filled_CloudUpload!!
    }

private var _filled_CloudUpload: ImageVector? = null

public val Icons.Filled.Coffee: ImageVector
    get() {
        if (_filled_Coffee != null) {
            return _filled_Coffee!!
        }
        _filled_Coffee = materialIcon(name = "Filled.Coffee") {
            materialPath {
                moveTo(18.5f, 3.0f)
                horizontalLineTo(6.0f)
                curveTo(4.9f, 3.0f, 4.0f, 3.9f, 4.0f, 5.0f)
                verticalLineToRelative(5.71f)
                curveToRelative(0.0f, 3.83f, 2.95f, 7.18f, 6.78f, 7.29f)
                curveToRelative(3.96f, 0.12f, 7.22f, -3.06f, 7.22f, -7.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(0.5f)
                curveToRelative(1.93f, 0.0f, 3.5f, -1.57f, 3.5f, -3.5f)
                reflectiveCurveTo(20.43f, 3.0f, 18.5f, 3.0f)
                close()
                moveTo(16.0f, 5.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(6.0f)
                verticalLineTo(5.0f)
                horizontalLineTo(16.0f)
                close()
                moveTo(18.5f, 8.0f)
                horizontalLineTo(18.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(0.5f)
                curveTo(19.33f, 5.0f, 20.0f, 5.67f, 20.0f, 6.5f)
                reflectiveCurveTo(19.33f, 8.0f, 18.5f, 8.0f)
                close()
                moveTo(4.0f, 19.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(2.0f)
                horizontalLineTo(4.0f)
                verticalLineTo(19.0f)
                close()
            }
        }
        return _filled_Coffee!!
    }

private var _filled_Coffee: ImageVector? = null

public val Icons.Filled.DeleteOutline: ImageVector
    get() {
        if (_filled_DeleteOutline != null) {
            return _filled_DeleteOutline!!
        }
        _filled_DeleteOutline = materialIcon(name = "Filled.DeleteOutline") {
            materialPath {
                moveTo(6.0f, 19.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(8.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(18.0f, 7.0f)
                lineTo(6.0f, 7.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(8.0f, 9.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(10.0f)
                lineTo(8.0f, 19.0f)
                lineTo(8.0f, 9.0f)
                close()
                moveTo(15.5f, 4.0f)
                lineToRelative(-1.0f, -1.0f)
                horizontalLineToRelative(-5.0f)
                lineToRelative(-1.0f, 1.0f)
                lineTo(5.0f, 4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(14.0f)
                lineTo(19.0f, 4.0f)
                close()
            }
        }
        return _filled_DeleteOutline!!
    }

private var _filled_DeleteOutline: ImageVector? = null

public val Icons.Filled.DownhillSkiing: ImageVector
    get() {
        if (_filled_DownhillSkiing != null) {
            return _filled_DownhillSkiing!!
        }
        _filled_DownhillSkiing = materialIcon(name = "Filled.DownhillSkiing") {
            materialPath {
                moveTo(18.5f, 4.5f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveTo(18.5f, 3.4f, 18.5f, 4.5f)
                close()
                moveTo(15.78f, 20.9f)
                lineToRelative(0.76f, 0.27f)
                curveToRelative(0.62f, 0.21f, 1.27f, 0.33f, 1.96f, 0.33f)
                curveToRelative(0.84f, 0.0f, 1.65f, -0.18f, 2.38f, -0.5f)
                lineTo(22.0f, 22.13f)
                curveTo(20.95f, 22.68f, 19.76f, 23.0f, 18.5f, 23.0f)
                curveToRelative(-0.86f, 0.0f, -1.68f, -0.14f, -2.45f, -0.41f)
                lineTo(2.0f, 17.47f)
                lineToRelative(0.5f, -1.41f)
                lineToRelative(6.9f, 2.51f)
                lineToRelative(1.72f, -4.44f)
                lineTo(7.55f, 10.4f)
                curveTo(6.66f, 9.46f, 6.88f, 7.93f, 8.0f, 7.28f)
                lineToRelative(3.48f, -2.01f)
                curveToRelative(1.1f, -0.64f, 2.52f, -0.1f, 2.91f, 1.11f)
                lineToRelative(0.33f, 1.08f)
                curveToRelative(0.44f, 1.42f, 1.48f, 2.57f, 2.83f, 3.14f)
                lineTo(18.07f, 9.0f)
                lineToRelative(1.43f, 0.46f)
                lineToRelative(-1.12f, 3.45f)
                curveToRelative(-2.45f, -0.4f, -4.48f, -2.07f, -5.38f, -4.32f)
                lineToRelative(-2.53f, 1.45f)
                lineToRelative(3.03f, 3.46f)
                lineToRelative(-2.22f, 5.76f)
                lineToRelative(3.09f, 1.12f)
                lineToRelative(2.1f, -6.44f)
                horizontalLineToRelative(0.0f)
                lineToRelative(0.0f, 0.0f)
                curveToRelative(0.46f, 0.18f, 0.94f, 0.31f, 1.44f, 0.41f)
                lineTo(15.78f, 20.9f)
                close()
            }
        }
        return _filled_DownhillSkiing!!
    }

private var _filled_DownhillSkiing: ImageVector? = null

public val Icons.Filled.Download: ImageVector
    get() {
        if (_filled_Download != null) {
            return _filled_Download!!
        }
        _filled_Download = materialIcon(name = "Filled.Download") {
            materialPath {
                moveTo(5.0f, 20.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineTo(5.0f)
                lineToRelative(7.0f, 7.0f)
                lineTo(19.0f, 9.0f)
                close()
            }
        }
        return _filled_Download!!
    }

private var _filled_Download: ImageVector? = null

public val Icons.Filled.Explore: ImageVector
    get() {
        if (_filled_Explore != null) {
            return _filled_Explore!!
        }
        _filled_Explore = materialIcon(name = "Filled.Explore") {
            materialPath {
                moveTo(12.0f, 10.9f)
                curveToRelative(-0.61f, 0.0f, -1.1f, 0.49f, -1.1f, 1.1f)
                reflectiveCurveToRelative(0.49f, 1.1f, 1.1f, 1.1f)
                curveToRelative(0.61f, 0.0f, 1.1f, -0.49f, 1.1f, -1.1f)
                reflectiveCurveToRelative(-0.49f, -1.1f, -1.1f, -1.1f)
                close()
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(14.19f, 14.19f)
                lineTo(6.0f, 18.0f)
                lineToRelative(3.81f, -8.19f)
                lineTo(18.0f, 6.0f)
                lineToRelative(-3.81f, 8.19f)
                close()
            }
        }
        return _filled_Explore!!
    }

private var _filled_Explore: ImageVector? = null

public val Icons.Filled.FileDownload: ImageVector
    get() {
        if (_filled_FileDownload != null) {
            return _filled_FileDownload!!
        }
        _filled_FileDownload = materialIcon(name = "Filled.FileDownload") {
            materialPath {
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineTo(5.0f)
                lineToRelative(7.0f, 7.0f)
                lineToRelative(7.0f, -7.0f)
                close()
                moveTo(5.0f, 18.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(5.0f)
                close()
            }
        }
        return _filled_FileDownload!!
    }

private var _filled_FileDownload: ImageVector? = null

public val Icons.Filled.FileUpload: ImageVector
    get() {
        if (_filled_FileUpload != null) {
            return _filled_FileUpload!!
        }
        _filled_FileUpload = materialIcon(name = "Filled.FileUpload") {
            materialPath {
                moveTo(9.0f, 16.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(-7.0f, -7.0f)
                lineToRelative(-7.0f, 7.0f)
                horizontalLineToRelative(4.0f)
                close()
                moveTo(5.0f, 18.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(2.0f)
                lineTo(5.0f, 20.0f)
                close()
            }
        }
        return _filled_FileUpload!!
    }

private var _filled_FileUpload: ImageVector? = null

public val Icons.Filled.FitnessCenter: ImageVector
    get() {
        if (_filled_FitnessCenter != null) {
            return _filled_FitnessCenter!!
        }
        _filled_FitnessCenter = materialIcon(name = "Filled.FitnessCenter") {
            materialPath {
                moveTo(20.57f, 14.86f)
                lineTo(22.0f, 13.43f)
                lineTo(20.57f, 12.0f)
                lineTo(17.0f, 15.57f)
                lineTo(8.43f, 7.0f)
                lineTo(12.0f, 3.43f)
                lineTo(10.57f, 2.0f)
                lineTo(9.14f, 3.43f)
                lineTo(7.71f, 2.0f)
                lineTo(5.57f, 4.14f)
                lineTo(4.14f, 2.71f)
                lineTo(2.71f, 4.14f)
                lineToRelative(1.43f, 1.43f)
                lineTo(2.0f, 7.71f)
                lineToRelative(1.43f, 1.43f)
                lineTo(2.0f, 10.57f)
                lineTo(3.43f, 12.0f)
                lineTo(7.0f, 8.43f)
                lineTo(15.57f, 17.0f)
                lineTo(12.0f, 20.57f)
                lineTo(13.43f, 22.0f)
                lineToRelative(1.43f, -1.43f)
                lineTo(16.29f, 22.0f)
                lineToRelative(2.14f, -2.14f)
                lineToRelative(1.43f, 1.43f)
                lineToRelative(1.43f, -1.43f)
                lineToRelative(-1.43f, -1.43f)
                lineTo(22.0f, 16.29f)
                close()
            }
        }
        return _filled_FitnessCenter!!
    }

private var _filled_FitnessCenter: ImageVector? = null

public val Icons.Filled.FolderOpen: ImageVector
    get() {
        if (_filled_FolderOpen != null) {
            return _filled_FolderOpen!!
        }
        _filled_FolderOpen = materialIcon(name = "Filled.FolderOpen") {
            materialPath {
                moveTo(20.0f, 6.0f)
                horizontalLineToRelative(-8.0f)
                lineToRelative(-2.0f, -2.0f)
                lineTo(4.0f, 4.0f)
                curveToRelative(-1.1f, 0.0f, -1.99f, 0.9f, -1.99f, 2.0f)
                lineTo(2.0f, 18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(22.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(20.0f, 18.0f)
                lineTo(4.0f, 18.0f)
                lineTo(4.0f, 8.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(10.0f)
                close()
            }
        }
        return _filled_FolderOpen!!
    }

private var _filled_FolderOpen: ImageVector? = null

public val Icons.Filled.GraphicEq: ImageVector
    get() {
        if (_filled_GraphicEq != null) {
            return _filled_GraphicEq!!
        }
        _filled_GraphicEq = materialIcon(name = "Filled.GraphicEq") {
            materialPath {
                moveTo(7.0f, 18.0f)
                horizontalLineToRelative(2.0f)
                lineTo(9.0f, 6.0f)
                lineTo(7.0f, 6.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(11.0f, 22.0f)
                horizontalLineToRelative(2.0f)
                lineTo(13.0f, 2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(20.0f)
                close()
                moveTo(3.0f, 14.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-4.0f)
                lineTo(3.0f, 10.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(15.0f, 18.0f)
                horizontalLineToRelative(2.0f)
                lineTo(17.0f, 6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(12.0f)
                close()
                moveTo(19.0f, 10.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(-2.0f)
                close()
            }
        }
        return _filled_GraphicEq!!
    }

private var _filled_GraphicEq: ImageVector? = null

public val Icons.Filled.HealthAndSafety: ImageVector
    get() {
        if (_filled_HealthAndSafety != null) {
            return _filled_HealthAndSafety!!
        }
        _filled_HealthAndSafety = materialIcon(name = "Filled.HealthAndSafety") {
            materialPath {
                moveTo(10.5f, 13.0f)
                horizontalLineTo(8.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(2.5f)
                verticalLineTo(7.5f)
                horizontalLineToRelative(3.0f)
                verticalLineTo(10.0f)
                horizontalLineTo(16.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(-2.5f)
                verticalLineToRelative(2.5f)
                horizontalLineToRelative(-3.0f)
                verticalLineTo(13.0f)
                close()
                moveTo(12.0f, 2.0f)
                lineTo(4.0f, 5.0f)
                verticalLineToRelative(6.09f)
                curveToRelative(0.0f, 5.05f, 3.41f, 9.76f, 8.0f, 10.91f)
                curveToRelative(4.59f, -1.15f, 8.0f, -5.86f, 8.0f, -10.91f)
                verticalLineTo(5.0f)
                lineTo(12.0f, 2.0f)
                close()
            }
        }
        return _filled_HealthAndSafety!!
    }

private var _filled_HealthAndSafety: ImageVector? = null

public val Icons.Filled.Hexagon: ImageVector
    get() {
        if (_filled_Hexagon != null) {
            return _filled_Hexagon!!
        }
        _filled_Hexagon = materialIcon(name = "Filled.Hexagon") {
            materialPath {
                moveTo(17.2f, 3.0f)
                lineToRelative(-10.4f, 0.0f)
                lineToRelative(-5.2f, 9.0f)
                lineToRelative(5.2f, 9.0f)
                lineToRelative(10.4f, 0.0f)
                lineToRelative(5.2f, -9.0f)
                close()
            }
        }
        return _filled_Hexagon!!
    }

private var _filled_Hexagon: ImageVector? = null

public val Icons.Filled.History: ImageVector
    get() {
        if (_filled_History != null) {
            return _filled_History!!
        }
        _filled_History = materialIcon(name = "Filled.History") {
            materialPath {
                moveTo(13.0f, 3.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                lineTo(1.0f, 12.0f)
                lineToRelative(3.89f, 3.89f)
                lineToRelative(0.07f, 0.14f)
                lineTo(9.0f, 12.0f)
                lineTo(6.0f, 12.0f)
                curveToRelative(0.0f, -3.87f, 3.13f, -7.0f, 7.0f, -7.0f)
                reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
                reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
                curveToRelative(-1.93f, 0.0f, -3.68f, -0.79f, -4.94f, -2.06f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(8.27f, 19.99f, 10.51f, 21.0f, 13.0f, 21.0f)
                curveToRelative(4.97f, 0.0f, 9.0f, -4.03f, 9.0f, -9.0f)
                reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
                close()
                moveTo(12.0f, 8.0f)
                verticalLineToRelative(5.0f)
                lineToRelative(4.28f, 2.54f)
                lineToRelative(0.72f, -1.21f)
                lineToRelative(-3.5f, -2.08f)
                lineTo(13.5f, 8.0f)
                lineTo(12.0f, 8.0f)
                close()
            }
        }
        return _filled_History!!
    }

private var _filled_History: ImageVector? = null

public val Icons.Filled.Insights: ImageVector
    get() {
        if (_filled_Insights != null) {
            return _filled_Insights!!
        }
        _filled_Insights = materialIcon(name = "Filled.Insights") {
            materialPath {
                moveTo(21.0f, 8.0f)
                curveToRelative(-1.45f, 0.0f, -2.26f, 1.44f, -1.93f, 2.51f)
                lineToRelative(-3.55f, 3.56f)
                curveToRelative(-0.3f, -0.09f, -0.74f, -0.09f, -1.04f, 0.0f)
                lineToRelative(-2.55f, -2.55f)
                curveTo(12.27f, 10.45f, 11.46f, 9.0f, 10.0f, 9.0f)
                curveToRelative(-1.45f, 0.0f, -2.27f, 1.44f, -1.93f, 2.52f)
                lineToRelative(-4.56f, 4.55f)
                curveTo(2.44f, 15.74f, 1.0f, 16.55f, 1.0f, 18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                curveToRelative(1.45f, 0.0f, 2.26f, -1.44f, 1.93f, -2.51f)
                lineToRelative(4.55f, -4.56f)
                curveToRelative(0.3f, 0.09f, 0.74f, 0.09f, 1.04f, 0.0f)
                lineToRelative(2.55f, 2.55f)
                curveTo(12.73f, 16.55f, 13.54f, 18.0f, 15.0f, 18.0f)
                curveToRelative(1.45f, 0.0f, 2.27f, -1.44f, 1.93f, -2.52f)
                lineToRelative(3.56f, -3.55f)
                curveTo(21.56f, 12.26f, 23.0f, 11.45f, 23.0f, 10.0f)
                curveTo(23.0f, 8.9f, 22.1f, 8.0f, 21.0f, 8.0f)
                close()
            }
            materialPath {
                moveTo(15.0f, 9.0f)
                lineToRelative(0.94f, -2.07f)
                lineToRelative(2.06f, -0.93f)
                lineToRelative(-2.06f, -0.93f)
                lineToRelative(-0.94f, -2.07f)
                lineToRelative(-0.92f, 2.07f)
                lineToRelative(-2.08f, 0.93f)
                lineToRelative(2.08f, 0.93f)
                close()
            }
            materialPath {
                moveTo(3.5f, 11.0f)
                lineToRelative(0.5f, -2.0f)
                lineToRelative(2.0f, -0.5f)
                lineToRelative(-2.0f, -0.5f)
                lineToRelative(-0.5f, -2.0f)
                lineToRelative(-0.5f, 2.0f)
                lineToRelative(-2.0f, 0.5f)
                lineToRelative(2.0f, 0.5f)
                close()
            }
        }
        return _filled_Insights!!
    }

private var _filled_Insights: ImageVector? = null

public val Icons.Filled.IosShare: ImageVector
    get() {
        if (_filled_IosShare != null) {
            return _filled_IosShare!!
        }
        _filled_IosShare = materialIcon(name = "Filled.IosShare") {
            materialPath {
                moveTo(16.0f, 5.0f)
                lineToRelative(-1.42f, 1.42f)
                lineToRelative(-1.59f, -1.59f)
                lineTo(12.99f, 16.0f)
                horizontalLineToRelative(-1.98f)
                lineTo(11.01f, 4.83f)
                lineTo(9.42f, 6.42f)
                lineTo(8.0f, 5.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(4.0f, 4.0f)
                close()
                moveTo(20.0f, 10.0f)
                verticalLineToRelative(11.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                lineTo(6.0f, 23.0f)
                curveToRelative(-1.11f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                lineTo(4.0f, 10.0f)
                curveToRelative(0.0f, -1.11f, 0.89f, -2.0f, 2.0f, -2.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(2.0f)
                lineTo(6.0f, 10.0f)
                verticalLineToRelative(11.0f)
                horizontalLineToRelative(12.0f)
                lineTo(18.0f, 10.0f)
                horizontalLineToRelative(-3.0f)
                lineTo(15.0f, 8.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.89f, 2.0f, 2.0f)
                close()
            }
        }
        return _filled_IosShare!!
    }

private var _filled_IosShare: ImageVector? = null

public val Icons.Filled.LocalBar: ImageVector
    get() {
        if (_filled_LocalBar != null) {
            return _filled_LocalBar!!
        }
        _filled_LocalBar = materialIcon(name = "Filled.LocalBar") {
            materialPath {
                moveTo(21.0f, 5.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(3.0f)
                verticalLineToRelative(2.0f)
                lineToRelative(8.0f, 9.0f)
                verticalLineToRelative(5.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-5.0f)
                verticalLineToRelative(-5.0f)
                lineToRelative(8.0f, -9.0f)
                close()
                moveTo(7.43f, 7.0f)
                lineTo(5.66f, 5.0f)
                horizontalLineToRelative(12.69f)
                lineToRelative(-1.78f, 2.0f)
                horizontalLineTo(7.43f)
                close()
            }
        }
        return _filled_LocalBar!!
    }

private var _filled_LocalBar: ImageVector? = null

public val Icons.Filled.LocalDrink: ImageVector
    get() {
        if (_filled_LocalDrink != null) {
            return _filled_LocalDrink!!
        }
        _filled_LocalDrink = materialIcon(name = "Filled.LocalDrink") {
            materialPath {
                moveTo(3.0f, 2.0f)
                lineToRelative(2.01f, 18.23f)
                curveTo(5.13f, 21.23f, 5.97f, 22.0f, 7.0f, 22.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(1.03f, 0.0f, 1.87f, -0.77f, 1.99f, -1.77f)
                lineTo(21.0f, 2.0f)
                lineTo(3.0f, 2.0f)
                close()
                moveTo(12.0f, 19.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
                curveToRelative(0.0f, -2.0f, 3.0f, -5.4f, 3.0f, -5.4f)
                reflectiveCurveToRelative(3.0f, 3.4f, 3.0f, 5.4f)
                curveToRelative(0.0f, 1.66f, -1.34f, 3.0f, -3.0f, 3.0f)
                close()
                moveTo(18.33f, 8.0f)
                lineTo(5.67f, 8.0f)
                lineToRelative(-0.44f, -4.0f)
                horizontalLineToRelative(13.53f)
                lineToRelative(-0.43f, 4.0f)
                close()
            }
        }
        return _filled_LocalDrink!!
    }

private var _filled_LocalDrink: ImageVector? = null

public val Icons.Filled.LocalFireDepartment: ImageVector
    get() {
        if (_filled_LocalFireDepartment != null) {
            return _filled_LocalFireDepartment!!
        }
        _filled_LocalFireDepartment = materialIcon(name = "Filled.LocalFireDepartment") {
            materialPath {
                moveTo(12.0f, 12.9f)
                lineToRelative(-2.13f, 2.09f)
                curveTo(9.31f, 15.55f, 9.0f, 16.28f, 9.0f, 17.06f)
                curveTo(9.0f, 18.68f, 10.35f, 20.0f, 12.0f, 20.0f)
                reflectiveCurveToRelative(3.0f, -1.32f, 3.0f, -2.94f)
                curveToRelative(0.0f, -0.78f, -0.31f, -1.52f, -0.87f, -2.07f)
                lineTo(12.0f, 12.9f)
                close()
            }
            materialPath {
                moveTo(16.0f, 6.0f)
                lineToRelative(-0.44f, 0.55f)
                curveTo(14.38f, 8.02f, 12.0f, 7.19f, 12.0f, 5.3f)
                verticalLineTo(2.0f)
                curveToRelative(0.0f, 0.0f, -8.0f, 4.0f, -8.0f, 11.0f)
                curveToRelative(0.0f, 2.92f, 1.56f, 5.47f, 3.89f, 6.86f)
                curveTo(7.33f, 19.07f, 7.0f, 18.1f, 7.0f, 17.06f)
                curveToRelative(0.0f, -1.32f, 0.52f, -2.56f, 1.47f, -3.5f)
                lineTo(12.0f, 10.1f)
                lineToRelative(3.53f, 3.47f)
                curveToRelative(0.95f, 0.93f, 1.47f, 2.17f, 1.47f, 3.5f)
                curveToRelative(0.0f, 1.02f, -0.31f, 1.96f, -0.85f, 2.75f)
                curveToRelative(1.89f, -1.15f, 3.29f, -3.06f, 3.71f, -5.3f)
                curveTo(20.52f, 10.97f, 18.79f, 7.62f, 16.0f, 6.0f)
                close()
            }
        }
        return _filled_LocalFireDepartment!!
    }

private var _filled_LocalFireDepartment: ImageVector? = null

public val Icons.Filled.LockOpen: ImageVector
    get() {
        if (_filled_LockOpen != null) {
            return _filled_LockOpen!!
        }
        _filled_LockOpen = materialIcon(name = "Filled.LockOpen") {
            materialPath {
                moveTo(12.0f, 17.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                reflectiveCurveToRelative(-2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(18.0f, 8.0f)
                horizontalLineToRelative(-1.0f)
                lineTo(17.0f, 6.0f)
                curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
                reflectiveCurveTo(7.0f, 3.24f, 7.0f, 6.0f)
                horizontalLineToRelative(1.9f)
                curveToRelative(0.0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                curveToRelative(1.71f, 0.0f, 3.1f, 1.39f, 3.1f, 3.1f)
                verticalLineToRelative(2.0f)
                lineTo(6.0f, 8.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(10.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(20.0f, 10.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(18.0f, 20.0f)
                lineTo(6.0f, 20.0f)
                lineTo(6.0f, 10.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(10.0f)
                close()
            }
        }
        return _filled_LockOpen!!
    }

private var _filled_LockOpen: ImageVector? = null

public val Icons.Filled.MonitorHeart: ImageVector
    get() {
        if (_filled_MonitorHeart != null) {
            return _filled_MonitorHeart!!
        }
        _filled_MonitorHeart = materialIcon(name = "Filled.MonitorHeart") {
            materialPath {
                moveTo(15.11f, 12.45f)
                lineTo(14.0f, 10.24f)
                lineToRelative(-3.11f, 6.21f)
                curveTo(10.73f, 16.79f, 10.38f, 17.0f, 10.0f, 17.0f)
                reflectiveCurveToRelative(-0.73f, -0.21f, -0.89f, -0.55f)
                lineTo(7.38f, 13.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(5.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-5.0f)
                horizontalLineToRelative(-6.0f)
                curveTo(15.62f, 13.0f, 15.27f, 12.79f, 15.11f, 12.45f)
                close()
            }
            materialPath {
                moveTo(20.0f, 4.0f)
                horizontalLineTo(4.0f)
                curveTo(2.9f, 4.0f, 2.0f, 4.9f, 2.0f, 6.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.38f, 0.0f, 0.73f, 0.21f, 0.89f, 0.55f)
                lineTo(10.0f, 13.76f)
                lineToRelative(3.11f, -6.21f)
                curveToRelative(0.34f, -0.68f, 1.45f, -0.68f, 1.79f, 0.0f)
                lineTo(16.62f, 11.0f)
                horizontalLineTo(22.0f)
                verticalLineTo(6.0f)
                curveTo(22.0f, 4.9f, 21.1f, 4.0f, 20.0f, 4.0f)
                close()
            }
        }
        return _filled_MonitorHeart!!
    }

private var _filled_MonitorHeart: ImageVector? = null

public val Icons.Filled.MoreHoriz: ImageVector
    get() {
        if (_filled_MoreHoriz != null) {
            return _filled_MoreHoriz!!
        }
        _filled_MoreHoriz = materialIcon(name = "Filled.MoreHoriz") {
            materialPath {
                moveTo(6.0f, 10.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(18.0f, 10.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(12.0f, 10.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveToRelative(-0.9f, -2.0f, -2.0f, -2.0f)
                close()
            }
        }
        return _filled_MoreHoriz!!
    }

private var _filled_MoreHoriz: ImageVector? = null

public val Icons.Filled.NightsStay: ImageVector
    get() {
        if (_filled_NightsStay != null) {
            return _filled_NightsStay!!
        }
        _filled_NightsStay = materialIcon(name = "Filled.NightsStay") {
            materialPath {
                moveTo(11.1f, 12.08f)
                curveTo(8.77f, 7.57f, 10.6f, 3.6f, 11.63f, 2.01f)
                curveTo(6.27f, 2.2f, 1.98f, 6.59f, 1.98f, 12.0f)
                curveToRelative(0.0f, 0.14f, 0.02f, 0.28f, 0.02f, 0.42f)
                curveTo(2.62f, 12.15f, 3.29f, 12.0f, 4.0f, 12.0f)
                curveToRelative(1.66f, 0.0f, 3.18f, 0.83f, 4.1f, 2.15f)
                curveTo(9.77f, 14.63f, 11.0f, 16.17f, 11.0f, 18.0f)
                curveToRelative(0.0f, 1.52f, -0.87f, 2.83f, -2.12f, 3.51f)
                curveToRelative(0.98f, 0.32f, 2.03f, 0.5f, 3.11f, 0.5f)
                curveToRelative(3.5f, 0.0f, 6.58f, -1.8f, 8.37f, -4.52f)
                curveTo(18.0f, 17.72f, 13.38f, 16.52f, 11.1f, 12.08f)
                close()
            }
            materialPath {
                moveTo(7.0f, 16.0f)
                lineToRelative(-0.18f, 0.0f)
                curveTo(6.4f, 14.84f, 5.3f, 14.0f, 4.0f, 14.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, 1.34f, -3.0f, 3.0f)
                reflectiveCurveToRelative(1.34f, 3.0f, 3.0f, 3.0f)
                curveToRelative(0.62f, 0.0f, 2.49f, 0.0f, 3.0f, 0.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                curveTo(9.0f, 16.9f, 8.1f, 16.0f, 7.0f, 16.0f)
                close()
            }
        }
        return _filled_NightsStay!!
    }

private var _filled_NightsStay: ImageVector? = null

public val Icons.Filled.NotificationsActive: ImageVector
    get() {
        if (_filled_NotificationsActive != null) {
            return _filled_NotificationsActive!!
        }
        _filled_NotificationsActive = materialIcon(name = "Filled.NotificationsActive") {
            materialPath {
                moveTo(7.58f, 4.08f)
                lineTo(6.15f, 2.65f)
                curveTo(3.75f, 4.48f, 2.17f, 7.3f, 2.03f, 10.5f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.15f, -2.65f, 1.51f, -4.97f, 3.55f, -6.42f)
                close()
                moveTo(19.97f, 10.5f)
                horizontalLineToRelative(2.0f)
                curveToRelative(-0.15f, -3.2f, -1.73f, -6.02f, -4.12f, -7.85f)
                lineToRelative(-1.42f, 1.43f)
                curveToRelative(2.02f, 1.45f, 3.39f, 3.77f, 3.54f, 6.42f)
                close()
                moveTo(18.0f, 11.0f)
                curveToRelative(0.0f, -3.07f, -1.64f, -5.64f, -4.5f, -6.32f)
                lineTo(13.5f, 4.0f)
                curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
                verticalLineToRelative(0.68f)
                curveTo(7.63f, 5.36f, 6.0f, 7.92f, 6.0f, 11.0f)
                verticalLineToRelative(5.0f)
                lineToRelative(-2.0f, 2.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(-1.0f)
                lineToRelative(-2.0f, -2.0f)
                verticalLineToRelative(-5.0f)
                close()
                moveTo(12.0f, 22.0f)
                curveToRelative(0.14f, 0.0f, 0.27f, -0.01f, 0.4f, -0.04f)
                curveToRelative(0.65f, -0.14f, 1.18f, -0.58f, 1.44f, -1.18f)
                curveToRelative(0.1f, -0.24f, 0.15f, -0.5f, 0.15f, -0.78f)
                horizontalLineToRelative(-4.0f)
                curveToRelative(0.01f, 1.1f, 0.9f, 2.0f, 2.01f, 2.0f)
                close()
            }
        }
        return _filled_NotificationsActive!!
    }

private var _filled_NotificationsActive: ImageVector? = null

public val Icons.Filled.Palette: ImageVector
    get() {
        if (_filled_Palette != null) {
            return _filled_Palette!!
        }
        _filled_Palette = materialIcon(name = "Filled.Palette") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.49f, 2.0f, 2.0f, 6.49f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.49f, 10.0f, 10.0f, 10.0f)
                curveToRelative(1.38f, 0.0f, 2.5f, -1.12f, 2.5f, -2.5f)
                curveToRelative(0.0f, -0.61f, -0.23f, -1.2f, -0.64f, -1.67f)
                curveToRelative(-0.08f, -0.1f, -0.13f, -0.21f, -0.13f, -0.33f)
                curveToRelative(0.0f, -0.28f, 0.22f, -0.5f, 0.5f, -0.5f)
                horizontalLineTo(16.0f)
                curveToRelative(3.31f, 0.0f, 6.0f, -2.69f, 6.0f, -6.0f)
                curveTo(22.0f, 6.04f, 17.51f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(17.5f, 13.0f)
                curveToRelative(-0.83f, 0.0f, -1.5f, -0.67f, -1.5f, -1.5f)
                curveToRelative(0.0f, -0.83f, 0.67f, -1.5f, 1.5f, -1.5f)
                reflectiveCurveToRelative(1.5f, 0.67f, 1.5f, 1.5f)
                curveTo(19.0f, 12.33f, 18.33f, 13.0f, 17.5f, 13.0f)
                close()
                moveTo(14.5f, 9.0f)
                curveTo(13.67f, 9.0f, 13.0f, 8.33f, 13.0f, 7.5f)
                curveTo(13.0f, 6.67f, 13.67f, 6.0f, 14.5f, 6.0f)
                reflectiveCurveTo(16.0f, 6.67f, 16.0f, 7.5f)
                curveTo(16.0f, 8.33f, 15.33f, 9.0f, 14.5f, 9.0f)
                close()
                moveTo(5.0f, 11.5f)
                curveTo(5.0f, 10.67f, 5.67f, 10.0f, 6.5f, 10.0f)
                reflectiveCurveTo(8.0f, 10.67f, 8.0f, 11.5f)
                curveTo(8.0f, 12.33f, 7.33f, 13.0f, 6.5f, 13.0f)
                reflectiveCurveTo(5.0f, 12.33f, 5.0f, 11.5f)
                close()
                moveTo(11.0f, 7.5f)
                curveTo(11.0f, 8.33f, 10.33f, 9.0f, 9.5f, 9.0f)
                reflectiveCurveTo(8.0f, 8.33f, 8.0f, 7.5f)
                curveTo(8.0f, 6.67f, 8.67f, 6.0f, 9.5f, 6.0f)
                reflectiveCurveTo(11.0f, 6.67f, 11.0f, 7.5f)
                close()
            }
        }
        return _filled_Palette!!
    }

private var _filled_Palette: ImageVector? = null

public val Icons.Filled.Pause: ImageVector
    get() {
        if (_filled_Pause != null) {
            return _filled_Pause!!
        }
        _filled_Pause = materialIcon(name = "Filled.Pause") {
            materialPath {
                moveTo(6.0f, 19.0f)
                horizontalLineToRelative(4.0f)
                lineTo(10.0f, 5.0f)
                lineTo(6.0f, 5.0f)
                verticalLineToRelative(14.0f)
                close()
                moveTo(14.0f, 5.0f)
                verticalLineToRelative(14.0f)
                horizontalLineToRelative(4.0f)
                lineTo(18.0f, 5.0f)
                horizontalLineToRelative(-4.0f)
                close()
            }
        }
        return _filled_Pause!!
    }

private var _filled_Pause: ImageVector? = null

public val Icons.Filled.PhonelinkErase: ImageVector
    get() {
        if (_filled_PhonelinkErase != null) {
            return _filled_PhonelinkErase!!
        }
        _filled_PhonelinkErase = materialIcon(name = "Filled.PhonelinkErase") {
            materialPath {
                moveTo(13.0f, 8.2f)
                lineToRelative(-1.0f, -1.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(-4.0f, -4.0f)
                lineToRelative(-1.0f, 1.0f)
                lineToRelative(4.0f, 4.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(1.0f, 1.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(4.0f, 4.0f)
                lineToRelative(1.0f, -1.0f)
                lineToRelative(-4.0f, -4.0f)
                lineToRelative(4.0f, -4.0f)
                close()
                moveTo(19.0f, 1.0f)
                horizontalLineTo(9.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(16.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(3.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(10.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(3.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
            }
        }
        return _filled_PhonelinkErase!!
    }

private var _filled_PhonelinkErase: ImageVector? = null

public val Icons.Filled.Pool: ImageVector
    get() {
        if (_filled_Pool != null) {
            return _filled_Pool!!
        }
        _filled_Pool = materialIcon(name = "Filled.Pool") {
            materialPath {
                moveTo(22.0f, 21.0f)
                curveToRelative(-1.11f, 0.0f, -1.73f, -0.37f, -2.18f, -0.64f)
                curveToRelative(-0.37f, -0.22f, -0.6f, -0.36f, -1.15f, -0.36f)
                curveToRelative(-0.56f, 0.0f, -0.78f, 0.13f, -1.15f, 0.36f)
                curveToRelative(-0.46f, 0.27f, -1.07f, 0.64f, -2.18f, 0.64f)
                reflectiveCurveToRelative(-1.73f, -0.37f, -2.18f, -0.64f)
                curveToRelative(-0.37f, -0.22f, -0.6f, -0.36f, -1.15f, -0.36f)
                curveToRelative(-0.56f, 0.0f, -0.78f, 0.13f, -1.15f, 0.36f)
                curveToRelative(-0.46f, 0.27f, -1.08f, 0.64f, -2.19f, 0.64f)
                curveToRelative(-1.11f, 0.0f, -1.73f, -0.37f, -2.18f, -0.64f)
                curveToRelative(-0.37f, -0.23f, -0.6f, -0.36f, -1.15f, -0.36f)
                reflectiveCurveToRelative(-0.78f, 0.13f, -1.15f, 0.36f)
                curveToRelative(-0.46f, 0.27f, -1.08f, 0.64f, -2.19f, 0.64f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.56f, 0.0f, 0.78f, -0.13f, 1.15f, -0.36f)
                curveToRelative(0.46f, -0.27f, 1.08f, -0.64f, 2.19f, -0.64f)
                reflectiveCurveToRelative(1.73f, 0.37f, 2.18f, 0.64f)
                curveToRelative(0.37f, 0.23f, 0.59f, 0.36f, 1.15f, 0.36f)
                curveToRelative(0.56f, 0.0f, 0.78f, -0.13f, 1.15f, -0.36f)
                curveToRelative(0.46f, -0.27f, 1.08f, -0.64f, 2.19f, -0.64f)
                curveToRelative(1.11f, 0.0f, 1.73f, 0.37f, 2.18f, 0.64f)
                curveToRelative(0.37f, 0.22f, 0.6f, 0.36f, 1.15f, 0.36f)
                reflectiveCurveToRelative(0.78f, -0.13f, 1.15f, -0.36f)
                curveToRelative(0.45f, -0.27f, 1.07f, -0.64f, 2.18f, -0.64f)
                reflectiveCurveToRelative(1.73f, 0.37f, 2.18f, 0.64f)
                curveToRelative(0.37f, 0.23f, 0.59f, 0.36f, 1.15f, 0.36f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(22.0f, 16.5f)
                curveToRelative(-1.11f, 0.0f, -1.73f, -0.37f, -2.18f, -0.64f)
                curveToRelative(-0.37f, -0.22f, -0.6f, -0.36f, -1.15f, -0.36f)
                curveToRelative(-0.56f, 0.0f, -0.78f, 0.13f, -1.15f, 0.36f)
                curveToRelative(-0.45f, 0.27f, -1.07f, 0.64f, -2.18f, 0.64f)
                reflectiveCurveToRelative(-1.73f, -0.37f, -2.18f, -0.64f)
                curveToRelative(-0.37f, -0.22f, -0.6f, -0.36f, -1.15f, -0.36f)
                curveToRelative(-0.56f, 0.0f, -0.78f, 0.13f, -1.15f, 0.36f)
                curveToRelative(-0.45f, 0.27f, -1.07f, 0.64f, -2.18f, 0.64f)
                reflectiveCurveToRelative(-1.73f, -0.37f, -2.18f, -0.64f)
                curveToRelative(-0.37f, -0.22f, -0.6f, -0.36f, -1.15f, -0.36f)
                reflectiveCurveToRelative(-0.78f, 0.13f, -1.15f, 0.36f)
                curveToRelative(-0.47f, 0.27f, -1.09f, 0.64f, -2.2f, 0.64f)
                verticalLineToRelative(-2.0f)
                curveToRelative(0.56f, 0.0f, 0.78f, -0.13f, 1.15f, -0.36f)
                curveToRelative(0.45f, -0.27f, 1.07f, -0.64f, 2.18f, -0.64f)
                reflectiveCurveToRelative(1.73f, 0.37f, 2.18f, 0.64f)
                curveToRelative(0.37f, 0.22f, 0.6f, 0.36f, 1.15f, 0.36f)
                curveToRelative(0.56f, 0.0f, 0.78f, -0.13f, 1.15f, -0.36f)
                curveToRelative(0.45f, -0.27f, 1.07f, -0.64f, 2.18f, -0.64f)
                reflectiveCurveToRelative(1.73f, 0.37f, 2.18f, 0.64f)
                curveToRelative(0.37f, 0.22f, 0.6f, 0.36f, 1.15f, 0.36f)
                reflectiveCurveToRelative(0.78f, -0.13f, 1.15f, -0.36f)
                curveToRelative(0.45f, -0.27f, 1.07f, -0.64f, 2.18f, -0.64f)
                reflectiveCurveToRelative(1.73f, 0.37f, 2.18f, 0.64f)
                curveToRelative(0.37f, 0.22f, 0.6f, 0.36f, 1.15f, 0.36f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(8.67f, 12.0f)
                curveToRelative(0.56f, 0.0f, 0.78f, -0.13f, 1.15f, -0.36f)
                curveToRelative(0.46f, -0.27f, 1.08f, -0.64f, 2.19f, -0.64f)
                curveToRelative(1.11f, 0.0f, 1.73f, 0.37f, 2.18f, 0.64f)
                curveToRelative(0.37f, 0.22f, 0.6f, 0.36f, 1.15f, 0.36f)
                reflectiveCurveToRelative(0.78f, -0.13f, 1.15f, -0.36f)
                curveToRelative(0.12f, -0.07f, 0.26f, -0.15f, 0.41f, -0.23f)
                lineTo(10.48f, 5.0f)
                curveTo(8.93f, 3.45f, 7.5f, 2.99f, 5.0f, 3.0f)
                verticalLineToRelative(2.5f)
                curveToRelative(1.82f, -0.01f, 2.89f, 0.39f, 4.0f, 1.5f)
                lineToRelative(1.0f, 1.0f)
                lineToRelative(-3.25f, 3.25f)
                curveToRelative(0.31f, 0.12f, 0.56f, 0.27f, 0.77f, 0.39f)
                curveToRelative(0.37f, 0.23f, 0.59f, 0.36f, 1.15f, 0.36f)
                close()
            }
            materialPath {
                moveTo(16.5f, 5.5f)
                moveToRelative(-2.5f, 0.0f)
                arcToRelative(2.5f, 2.5f, 0.0f, true, true, 5.0f, 0.0f)
                arcToRelative(2.5f, 2.5f, 0.0f, true, true, -5.0f, 0.0f)
            }
        }
        return _filled_Pool!!
    }

private var _filled_Pool: ImageVector? = null

public val Icons.Filled.Psychology: ImageVector
    get() {
        if (_filled_Psychology != null) {
            return _filled_Psychology!!
        }
        _filled_Psychology = materialIcon(name = "Filled.Psychology") {
            materialPath {
                moveTo(13.0f, 8.57f)
                curveToRelative(-0.79f, 0.0f, -1.43f, 0.64f, -1.43f, 1.43f)
                reflectiveCurveToRelative(0.64f, 1.43f, 1.43f, 1.43f)
                reflectiveCurveToRelative(1.43f, -0.64f, 1.43f, -1.43f)
                reflectiveCurveTo(13.79f, 8.57f, 13.0f, 8.57f)
                close()
            }
            materialPath {
                moveTo(13.0f, 3.0f)
                curveTo(9.25f, 3.0f, 6.2f, 5.94f, 6.02f, 9.64f)
                lineTo(4.1f, 12.2f)
                curveTo(3.85f, 12.53f, 4.09f, 13.0f, 4.5f, 13.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(3.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(-4.68f)
                curveToRelative(2.36f, -1.12f, 4.0f, -3.53f, 4.0f, -6.32f)
                curveTo(20.0f, 6.13f, 16.87f, 3.0f, 13.0f, 3.0f)
                close()
                moveTo(16.0f, 10.0f)
                curveToRelative(0.0f, 0.13f, -0.01f, 0.26f, -0.02f, 0.39f)
                lineToRelative(0.83f, 0.66f)
                curveToRelative(0.08f, 0.06f, 0.1f, 0.16f, 0.05f, 0.25f)
                lineToRelative(-0.8f, 1.39f)
                curveToRelative(-0.05f, 0.09f, -0.16f, 0.12f, -0.24f, 0.09f)
                lineToRelative(-0.99f, -0.4f)
                curveToRelative(-0.21f, 0.16f, -0.43f, 0.29f, -0.67f, 0.39f)
                lineTo(14.0f, 13.83f)
                curveToRelative(-0.01f, 0.1f, -0.1f, 0.17f, -0.2f, 0.17f)
                horizontalLineToRelative(-1.6f)
                curveToRelative(-0.1f, 0.0f, -0.18f, -0.07f, -0.2f, -0.17f)
                lineToRelative(-0.15f, -1.06f)
                curveToRelative(-0.25f, -0.1f, -0.47f, -0.23f, -0.68f, -0.39f)
                lineToRelative(-0.99f, 0.4f)
                curveToRelative(-0.09f, 0.03f, -0.2f, 0.0f, -0.25f, -0.09f)
                lineToRelative(-0.8f, -1.39f)
                curveToRelative(-0.05f, -0.08f, -0.03f, -0.19f, 0.05f, -0.25f)
                lineToRelative(0.84f, -0.66f)
                curveTo(10.01f, 10.26f, 10.0f, 10.13f, 10.0f, 10.0f)
                curveToRelative(0.0f, -0.13f, 0.02f, -0.27f, 0.04f, -0.39f)
                lineTo(9.19f, 8.95f)
                curveToRelative(-0.08f, -0.06f, -0.1f, -0.16f, -0.05f, -0.26f)
                lineToRelative(0.8f, -1.38f)
                curveToRelative(0.05f, -0.09f, 0.15f, -0.12f, 0.24f, -0.09f)
                lineToRelative(1.0f, 0.4f)
                curveToRelative(0.2f, -0.15f, 0.43f, -0.29f, 0.67f, -0.39f)
                lineToRelative(0.15f, -1.06f)
                curveTo(12.02f, 6.07f, 12.1f, 6.0f, 12.2f, 6.0f)
                horizontalLineToRelative(1.6f)
                curveToRelative(0.1f, 0.0f, 0.18f, 0.07f, 0.2f, 0.17f)
                lineToRelative(0.15f, 1.06f)
                curveToRelative(0.24f, 0.1f, 0.46f, 0.23f, 0.67f, 0.39f)
                lineToRelative(1.0f, -0.4f)
                curveToRelative(0.09f, -0.03f, 0.2f, 0.0f, 0.24f, 0.09f)
                lineToRelative(0.8f, 1.38f)
                curveToRelative(0.05f, 0.09f, 0.03f, 0.2f, -0.05f, 0.26f)
                lineToRelative(-0.85f, 0.66f)
                curveTo(15.99f, 9.73f, 16.0f, 9.86f, 16.0f, 10.0f)
                close()
            }
        }
        return _filled_Psychology!!
    }

private var _filled_Psychology: ImageVector? = null

public val Icons.Filled.RadioButtonChecked: ImageVector
    get() {
        if (_filled_RadioButtonChecked != null) {
            return _filled_RadioButtonChecked!!
        }
        _filled_RadioButtonChecked = materialIcon(name = "Filled.RadioButtonChecked") {
            materialPath {
                moveTo(12.0f, 7.0f)
                curveToRelative(-2.76f, 0.0f, -5.0f, 2.24f, -5.0f, 5.0f)
                reflectiveCurveToRelative(2.24f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
                reflectiveCurveToRelative(-2.24f, -5.0f, -5.0f, -5.0f)
                close()
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                close()
            }
        }
        return _filled_RadioButtonChecked!!
    }

private var _filled_RadioButtonChecked: ImageVector? = null

public val Icons.Filled.RadioButtonUnchecked: ImageVector
    get() {
        if (_filled_RadioButtonUnchecked != null) {
            return _filled_RadioButtonUnchecked!!
        }
        _filled_RadioButtonUnchecked = materialIcon(name = "Filled.RadioButtonUnchecked") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.42f, 0.0f, -8.0f, -3.58f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.58f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.58f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.58f, 8.0f, -8.0f, 8.0f)
                close()
            }
        }
        return _filled_RadioButtonUnchecked!!
    }

private var _filled_RadioButtonUnchecked: ImageVector? = null

public val Icons.Filled.Remove: ImageVector
    get() {
        if (_filled_Remove != null) {
            return _filled_Remove!!
        }
        _filled_Remove = materialIcon(name = "Filled.Remove") {
            materialPath {
                moveTo(19.0f, 13.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(2.0f)
                close()
            }
        }
        return _filled_Remove!!
    }

private var _filled_Remove: ImageVector? = null

public val Icons.Filled.RemoveCircleOutline: ImageVector
    get() {
        if (_filled_RemoveCircleOutline != null) {
            return _filled_RemoveCircleOutline!!
        }
        _filled_RemoveCircleOutline = materialIcon(name = "Filled.RemoveCircleOutline") {
            materialPath {
                moveTo(7.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-2.0f)
                lineTo(7.0f, 11.0f)
                close()
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                reflectiveCurveTo(17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(12.0f, 20.0f)
                curveToRelative(-4.41f, 0.0f, -8.0f, -3.59f, -8.0f, -8.0f)
                reflectiveCurveToRelative(3.59f, -8.0f, 8.0f, -8.0f)
                reflectiveCurveToRelative(8.0f, 3.59f, 8.0f, 8.0f)
                reflectiveCurveToRelative(-3.59f, 8.0f, -8.0f, 8.0f)
                close()
            }
        }
        return _filled_RemoveCircleOutline!!
    }

private var _filled_RemoveCircleOutline: ImageVector? = null

public val Icons.Filled.Restore: ImageVector
    get() {
        if (_filled_Restore != null) {
            return _filled_Restore!!
        }
        _filled_Restore = materialIcon(name = "Filled.Restore") {
            materialPath {
                moveTo(13.0f, 3.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                lineTo(1.0f, 12.0f)
                lineToRelative(3.89f, 3.89f)
                lineToRelative(0.07f, 0.14f)
                lineTo(9.0f, 12.0f)
                lineTo(6.0f, 12.0f)
                curveToRelative(0.0f, -3.87f, 3.13f, -7.0f, 7.0f, -7.0f)
                reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
                reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
                curveToRelative(-1.93f, 0.0f, -3.68f, -0.79f, -4.94f, -2.06f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(8.27f, 19.99f, 10.51f, 21.0f, 13.0f, 21.0f)
                curveToRelative(4.97f, 0.0f, 9.0f, -4.03f, 9.0f, -9.0f)
                reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
                close()
                moveTo(12.0f, 8.0f)
                verticalLineToRelative(5.0f)
                lineToRelative(4.28f, 2.54f)
                lineToRelative(0.72f, -1.21f)
                lineToRelative(-3.5f, -2.08f)
                lineTo(13.5f, 8.0f)
                lineTo(12.0f, 8.0f)
                close()
            }
        }
        return _filled_Restore!!
    }

private var _filled_Restore: ImageVector? = null

public val Icons.Filled.Rowing: ImageVector
    get() {
        if (_filled_Rowing != null) {
            return _filled_Rowing!!
        }
        _filled_Rowing = materialIcon(name = "Filled.Rowing") {
            materialPath {
                moveTo(8.5f, 14.5f)
                lineTo(4.0f, 19.0f)
                lineToRelative(1.5f, 1.5f)
                lineTo(9.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                lineTo(8.5f, 14.5f)
                close()
                moveTo(15.0f, 1.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveTo(16.1f, 1.0f, 15.0f, 1.0f)
                close()
                moveTo(21.0f, 21.01f)
                lineTo(18.0f, 24.0f)
                lineToRelative(-2.99f, -3.01f)
                verticalLineTo(19.5f)
                lineToRelative(-7.1f, -7.09f)
                curveTo(7.6f, 12.46f, 7.3f, 12.48f, 7.0f, 12.48f)
                verticalLineToRelative(-2.16f)
                curveToRelative(1.66f, 0.03f, 3.61f, -0.87f, 4.67f, -2.04f)
                lineToRelative(1.4f, -1.55f)
                curveTo(13.42f, 6.34f, 14.06f, 6.0f, 14.72f, 6.0f)
                horizontalLineToRelative(0.03f)
                curveTo(15.99f, 6.01f, 17.0f, 7.02f, 17.0f, 8.26f)
                verticalLineToRelative(5.75f)
                curveToRelative(0.0f, 0.84f, -0.35f, 1.61f, -0.92f, 2.16f)
                lineToRelative(-3.58f, -3.58f)
                verticalLineToRelative(-2.27f)
                curveToRelative(-0.63f, 0.52f, -1.43f, 1.02f, -2.29f, 1.39f)
                lineTo(16.5f, 18.0f)
                horizontalLineTo(18.0f)
                lineTo(21.0f, 21.01f)
                close()
            }
        }
        return _filled_Rowing!!
    }

private var _filled_Rowing: ImageVector? = null

public val Icons.Filled.Science: ImageVector
    get() {
        if (_filled_Science != null) {
            return _filled_Science!!
        }
        _filled_Science = materialIcon(name = "Filled.Science") {
            materialPath {
                moveTo(19.8f, 18.4f)
                lineTo(14.0f, 10.67f)
                verticalLineTo(6.5f)
                lineToRelative(1.35f, -1.69f)
                curveTo(15.61f, 4.48f, 15.38f, 4.0f, 14.96f, 4.0f)
                horizontalLineTo(9.04f)
                curveTo(8.62f, 4.0f, 8.39f, 4.48f, 8.65f, 4.81f)
                lineTo(10.0f, 6.5f)
                verticalLineToRelative(4.17f)
                lineTo(4.2f, 18.4f)
                curveTo(3.71f, 19.06f, 4.18f, 20.0f, 5.0f, 20.0f)
                horizontalLineToRelative(14.0f)
                curveTo(19.82f, 20.0f, 20.29f, 19.06f, 19.8f, 18.4f)
                close()
            }
        }
        return _filled_Science!!
    }

private var _filled_Science: ImageVector? = null

public val Icons.Filled.SelfImprovement: ImageVector
    get() {
        if (_filled_SelfImprovement != null) {
            return _filled_SelfImprovement!!
        }
        _filled_SelfImprovement = materialIcon(name = "Filled.SelfImprovement") {
            materialPath {
                moveTo(12.0f, 6.0f)
                moveToRelative(-2.0f, 0.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, true, true, 4.0f, 0.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, true, true, -4.0f, 0.0f)
            }
            materialPath {
                moveTo(21.0f, 16.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(-2.24f, 0.0f, -4.16f, -0.96f, -5.6f, -2.68f)
                lineToRelative(-1.34f, -1.6f)
                curveTo(13.68f, 9.26f, 13.12f, 9.0f, 12.53f, 9.0f)
                horizontalLineToRelative(-1.05f)
                curveToRelative(-0.59f, 0.0f, -1.15f, 0.26f, -1.53f, 0.72f)
                lineToRelative(-1.34f, 1.6f)
                curveTo(7.16f, 13.04f, 5.24f, 14.0f, 3.0f, 14.0f)
                verticalLineToRelative(2.0f)
                curveToRelative(2.77f, 0.0f, 5.19f, -1.17f, 7.0f, -3.25f)
                verticalLineTo(15.0f)
                lineToRelative(-3.88f, 1.55f)
                curveTo(5.45f, 16.82f, 5.0f, 17.48f, 5.0f, 18.21f)
                curveTo(5.0f, 19.2f, 5.8f, 20.0f, 6.79f, 20.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(-0.5f)
                curveToRelative(0.0f, -1.38f, 1.12f, -2.5f, 2.5f, -2.5f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.28f, 0.0f, 0.5f, 0.22f, 0.5f, 0.5f)
                reflectiveCurveTo(14.78f, 18.0f, 14.5f, 18.0f)
                horizontalLineToRelative(-3.0f)
                curveToRelative(-0.83f, 0.0f, -1.5f, 0.67f, -1.5f, 1.5f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(7.21f)
                curveTo(18.2f, 20.0f, 19.0f, 19.2f, 19.0f, 18.21f)
                curveToRelative(0.0f, -0.73f, -0.45f, -1.39f, -1.12f, -1.66f)
                lineTo(14.0f, 15.0f)
                verticalLineToRelative(-2.25f)
                curveTo(15.81f, 14.83f, 18.23f, 16.0f, 21.0f, 16.0f)
                close()
            }
        }
        return _filled_SelfImprovement!!
    }

private var _filled_SelfImprovement: ImageVector? = null

public val Icons.Filled.Sensors: ImageVector
    get() {
        if (_filled_Sensors != null) {
            return _filled_Sensors!!
        }
        _filled_Sensors = materialIcon(name = "Filled.Sensors") {
            materialPath {
                moveTo(7.76f, 16.24f)
                curveTo(6.67f, 15.16f, 6.0f, 13.66f, 6.0f, 12.0f)
                reflectiveCurveToRelative(0.67f, -3.16f, 1.76f, -4.24f)
                lineToRelative(1.42f, 1.42f)
                curveTo(8.45f, 9.9f, 8.0f, 10.9f, 8.0f, 12.0f)
                curveToRelative(0.0f, 1.1f, 0.45f, 2.1f, 1.17f, 2.83f)
                lineTo(7.76f, 16.24f)
                close()
                moveTo(16.24f, 16.24f)
                curveTo(17.33f, 15.16f, 18.0f, 13.66f, 18.0f, 12.0f)
                reflectiveCurveToRelative(-0.67f, -3.16f, -1.76f, -4.24f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(15.55f, 9.9f, 16.0f, 10.9f, 16.0f, 12.0f)
                curveToRelative(0.0f, 1.1f, -0.45f, 2.1f, -1.17f, 2.83f)
                lineTo(16.24f, 16.24f)
                close()
                moveTo(12.0f, 10.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                reflectiveCurveToRelative(0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                reflectiveCurveTo(13.1f, 10.0f, 12.0f, 10.0f)
                close()
                moveTo(20.0f, 12.0f)
                curveToRelative(0.0f, 2.21f, -0.9f, 4.21f, -2.35f, 5.65f)
                lineToRelative(1.42f, 1.42f)
                curveTo(20.88f, 17.26f, 22.0f, 14.76f, 22.0f, 12.0f)
                reflectiveCurveToRelative(-1.12f, -5.26f, -2.93f, -7.07f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(19.1f, 7.79f, 20.0f, 9.79f, 20.0f, 12.0f)
                close()
                moveTo(6.35f, 6.35f)
                lineTo(4.93f, 4.93f)
                curveTo(3.12f, 6.74f, 2.0f, 9.24f, 2.0f, 12.0f)
                reflectiveCurveToRelative(1.12f, 5.26f, 2.93f, 7.07f)
                lineToRelative(1.42f, -1.42f)
                curveTo(4.9f, 16.21f, 4.0f, 14.21f, 4.0f, 12.0f)
                reflectiveCurveTo(4.9f, 7.79f, 6.35f, 6.35f)
                close()
            }
        }
        return _filled_Sensors!!
    }

private var _filled_Sensors: ImageVector? = null

public val Icons.Filled.Shield: ImageVector
    get() {
        if (_filled_Shield != null) {
            return _filled_Shield!!
        }
        _filled_Shield = materialIcon(name = "Filled.Shield") {
            materialPath {
                moveTo(12.0f, 1.0f)
                lineTo(3.0f, 5.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
                verticalLineTo(5.0f)
                lineToRelative(-9.0f, -4.0f)
                close()
            }
        }
        return _filled_Shield!!
    }

private var _filled_Shield: ImageVector? = null

public val Icons.Filled.Snowboarding: ImageVector
    get() {
        if (_filled_Snowboarding != null) {
            return _filled_Snowboarding!!
        }
        _filled_Snowboarding = materialIcon(name = "Filled.Snowboarding") {
            materialPath {
                moveTo(14.0f, 3.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveTo(14.0f, 4.1f, 14.0f, 3.0f)
                close()
                moveTo(21.4f, 20.09f)
                curveToRelative(-0.23f, -0.05f, -0.46f, 0.02f, -0.64f, 0.17f)
                curveToRelative(-0.69f, 0.6f, -1.64f, 0.88f, -2.6f, 0.67f)
                lineTo(17.0f, 20.69f)
                lineToRelative(-1.0f, -6.19f)
                lineToRelative(-3.32f, -2.67f)
                lineToRelative(1.8f, -2.89f)
                curveTo(15.63f, 10.78f, 17.68f, 12.0f, 20.0f, 12.0f)
                verticalLineToRelative(-2.0f)
                curveToRelative(-1.85f, 0.0f, -3.44f, -1.12f, -4.13f, -2.72f)
                lineToRelative(-0.52f, -1.21f)
                curveTo(15.16f, 5.64f, 14.61f, 5.0f, 13.7f, 5.0f)
                horizontalLineTo(8.0f)
                lineTo(5.5f, 9.0f)
                lineToRelative(1.7f, 1.06f)
                lineTo(9.1f, 7.0f)
                horizontalLineToRelative(2.35f)
                lineToRelative(-2.51f, 3.99f)
                curveToRelative(-0.28f, 0.45f, -0.37f, 1.0f, -0.25f, 1.52f)
                lineTo(9.5f, 16.0f)
                lineTo(6.0f, 18.35f)
                lineToRelative(-0.47f, -0.1f)
                curveToRelative(-0.96f, -0.2f, -1.71f, -0.85f, -2.1f, -1.67f)
                curveToRelative(-0.1f, -0.21f, -0.28f, -0.37f, -0.51f, -0.42f)
                curveToRelative(-0.43f, -0.09f, -0.82f, 0.2f, -0.9f, 0.58f)
                curveTo(1.98f, 16.88f, 2.0f, 17.05f, 2.07f, 17.2f)
                curveToRelative(0.58f, 1.24f, 1.71f, 2.2f, 3.15f, 2.51f)
                lineToRelative(12.63f, 2.69f)
                curveToRelative(1.44f, 0.31f, 2.86f, -0.11f, 3.9f, -1.01f)
                curveToRelative(0.13f, -0.11f, 0.21f, -0.26f, 0.24f, -0.41f)
                curveTo(22.06f, 20.6f, 21.83f, 20.18f, 21.4f, 20.09f)
                close()
                moveTo(8.73f, 18.93f)
                lineToRelative(3.02f, -2.03f)
                lineToRelative(-0.44f, -3.32f)
                lineToRelative(2.84f, 2.02f)
                lineToRelative(0.75f, 4.64f)
                lineTo(8.73f, 18.93f)
                close()
            }
        }
        return _filled_Snowboarding!!
    }

private var _filled_Snowboarding: ImageVector? = null

public val Icons.Filled.Spa: ImageVector
    get() {
        if (_filled_Spa != null) {
            return _filled_Spa!!
        }
        _filled_Spa = materialIcon(name = "Filled.Spa") {
            materialPath {
                moveTo(8.55f, 12.0f)
                curveToRelative(-1.07f, -0.71f, -2.25f, -1.27f, -3.53f, -1.61f)
                curveToRelative(1.28f, 0.34f, 2.46f, 0.9f, 3.53f, 1.61f)
                close()
                moveTo(18.98f, 10.39f)
                curveToRelative(-1.29f, 0.34f, -2.49f, 0.91f, -3.57f, 1.64f)
                curveToRelative(1.08f, -0.73f, 2.28f, -1.3f, 3.57f, -1.64f)
                close()
            }
            materialPath {
                moveTo(15.49f, 9.63f)
                curveToRelative(-0.18f, -2.79f, -1.31f, -5.51f, -3.43f, -7.63f)
                curveToRelative(-2.14f, 2.14f, -3.32f, 4.86f, -3.55f, 7.63f)
                curveToRelative(1.28f, 0.68f, 2.46f, 1.56f, 3.49f, 2.63f)
                curveToRelative(1.03f, -1.06f, 2.21f, -1.94f, 3.49f, -2.63f)
                close()
                moveTo(8.99f, 12.28f)
                curveToRelative(-0.14f, -0.1f, -0.3f, -0.19f, -0.45f, -0.29f)
                curveToRelative(0.15f, 0.11f, 0.31f, 0.19f, 0.45f, 0.29f)
                close()
                moveTo(15.41f, 12.03f)
                curveToRelative(-0.13f, 0.09f, -0.27f, 0.16f, -0.4f, 0.26f)
                curveToRelative(0.13f, -0.1f, 0.27f, -0.17f, 0.4f, -0.26f)
                close()
                moveTo(12.0f, 15.45f)
                curveTo(9.85f, 12.17f, 6.18f, 10.0f, 2.0f, 10.0f)
                curveToRelative(0.0f, 5.32f, 3.36f, 9.82f, 8.03f, 11.49f)
                curveToRelative(0.63f, 0.23f, 1.29f, 0.4f, 1.97f, 0.51f)
                curveToRelative(0.68f, -0.12f, 1.33f, -0.29f, 1.97f, -0.51f)
                curveTo(18.64f, 19.82f, 22.0f, 15.32f, 22.0f, 10.0f)
                curveToRelative(-4.18f, 0.0f, -7.85f, 2.17f, -10.0f, 5.45f)
                close()
            }
        }
        return _filled_Spa!!
    }

private var _filled_Spa: ImageVector? = null

public val Icons.Filled.SportsBaseball: ImageVector
    get() {
        if (_filled_SportsBaseball != null) {
            return _filled_SportsBaseball!!
        }
        _filled_SportsBaseball = materialIcon(name = "Filled.SportsBaseball") {
            materialPath {
                moveTo(3.81f, 6.28f)
                curveTo(2.67f, 7.9f, 2.0f, 9.87f, 2.0f, 12.0f)
                reflectiveCurveToRelative(0.67f, 4.1f, 1.81f, 5.72f)
                curveTo(6.23f, 16.95f, 8.0f, 14.68f, 8.0f, 12.0f)
                reflectiveCurveTo(6.23f, 7.05f, 3.81f, 6.28f)
                close()
            }
            materialPath {
                moveTo(20.19f, 6.28f)
                curveTo(17.77f, 7.05f, 16.0f, 9.32f, 16.0f, 12.0f)
                reflectiveCurveToRelative(1.77f, 4.95f, 4.19f, 5.72f)
                curveTo(21.33f, 16.1f, 22.0f, 14.13f, 22.0f, 12.0f)
                reflectiveCurveTo(21.33f, 7.9f, 20.19f, 6.28f)
                close()
            }
            materialPath {
                moveTo(14.0f, 12.0f)
                curveToRelative(0.0f, -3.28f, 1.97f, -6.09f, 4.79f, -7.33f)
                curveTo(17.01f, 3.02f, 14.63f, 2.0f, 12.0f, 2.0f)
                reflectiveCurveTo(6.99f, 3.02f, 5.21f, 4.67f)
                curveTo(8.03f, 5.91f, 10.0f, 8.72f, 10.0f, 12.0f)
                reflectiveCurveToRelative(-1.97f, 6.09f, -4.79f, 7.33f)
                curveTo(6.99f, 20.98f, 9.37f, 22.0f, 12.0f, 22.0f)
                reflectiveCurveToRelative(5.01f, -1.02f, 6.79f, -2.67f)
                curveTo(15.97f, 18.09f, 14.0f, 15.28f, 14.0f, 12.0f)
                close()
            }
        }
        return _filled_SportsBaseball!!
    }

private var _filled_SportsBaseball: ImageVector? = null

public val Icons.Filled.SportsBasketball: ImageVector
    get() {
        if (_filled_SportsBasketball != null) {
            return _filled_SportsBasketball!!
        }
        _filled_SportsBasketball = materialIcon(name = "Filled.SportsBasketball") {
            materialPath {
                moveTo(17.09f, 11.0f)
                horizontalLineToRelative(4.86f)
                curveToRelative(-0.16f, -1.61f, -0.71f, -3.11f, -1.54f, -4.4f)
                curveTo(18.68f, 7.43f, 17.42f, 9.05f, 17.09f, 11.0f)
                close()
            }
            materialPath {
                moveTo(6.91f, 11.0f)
                curveTo(6.58f, 9.05f, 5.32f, 7.43f, 3.59f, 6.6f)
                curveTo(2.76f, 7.89f, 2.21f, 9.39f, 2.05f, 11.0f)
                horizontalLineTo(6.91f)
                close()
            }
            materialPath {
                moveTo(15.07f, 11.0f)
                curveToRelative(0.32f, -2.59f, 1.88f, -4.79f, 4.06f, -6.0f)
                curveToRelative(-1.6f, -1.63f, -3.74f, -2.71f, -6.13f, -2.95f)
                verticalLineTo(11.0f)
                horizontalLineTo(15.07f)
                close()
            }
            materialPath {
                moveTo(8.93f, 11.0f)
                horizontalLineTo(11.0f)
                verticalLineTo(2.05f)
                curveTo(8.61f, 2.29f, 6.46f, 3.37f, 4.87f, 5.0f)
                curveTo(7.05f, 6.21f, 8.61f, 8.41f, 8.93f, 11.0f)
                close()
            }
            materialPath {
                moveTo(15.07f, 13.0f)
                horizontalLineTo(13.0f)
                verticalLineToRelative(8.95f)
                curveToRelative(2.39f, -0.24f, 4.54f, -1.32f, 6.13f, -2.95f)
                curveTo(16.95f, 17.79f, 15.39f, 15.59f, 15.07f, 13.0f)
                close()
            }
            materialPath {
                moveTo(3.59f, 17.4f)
                curveToRelative(1.72f, -0.83f, 2.99f, -2.46f, 3.32f, -4.4f)
                horizontalLineTo(2.05f)
                curveTo(2.21f, 14.61f, 2.76f, 16.11f, 3.59f, 17.4f)
                close()
            }
            materialPath {
                moveTo(17.09f, 13.0f)
                curveToRelative(0.33f, 1.95f, 1.59f, 3.57f, 3.32f, 4.4f)
                curveToRelative(0.83f, -1.29f, 1.38f, -2.79f, 1.54f, -4.4f)
                horizontalLineTo(17.09f)
                close()
            }
            materialPath {
                moveTo(8.93f, 13.0f)
                curveToRelative(-0.32f, 2.59f, -1.88f, 4.79f, -4.06f, 6.0f)
                curveToRelative(1.6f, 1.63f, 3.74f, 2.71f, 6.13f, 2.95f)
                verticalLineTo(13.0f)
                horizontalLineTo(8.93f)
                close()
            }
        }
        return _filled_SportsBasketball!!
    }

private var _filled_SportsBasketball: ImageVector? = null

public val Icons.Filled.SportsGolf: ImageVector
    get() {
        if (_filled_SportsGolf != null) {
            return _filled_SportsGolf!!
        }
        _filled_SportsGolf = materialIcon(name = "Filled.SportsGolf") {
            materialPath {
                moveTo(12.0f, 16.0f)
                curveToRelative(3.87f, 0.0f, 7.0f, -3.13f, 7.0f, -7.0f)
                curveToRelative(0.0f, -3.87f, -3.13f, -7.0f, -7.0f, -7.0f)
                reflectiveCurveTo(5.0f, 5.13f, 5.0f, 9.0f)
                curveTo(5.0f, 12.87f, 8.13f, 16.0f, 12.0f, 16.0f)
                close()
                moveTo(12.0f, 4.0f)
                curveToRelative(2.76f, 0.0f, 5.0f, 2.24f, 5.0f, 5.0f)
                reflectiveCurveToRelative(-2.24f, 5.0f, -5.0f, 5.0f)
                reflectiveCurveToRelative(-5.0f, -2.24f, -5.0f, -5.0f)
                reflectiveCurveTo(9.24f, 4.0f, 12.0f, 4.0f)
                close()
            }
            materialPath {
                moveTo(10.0f, 8.0f)
                moveToRelative(-1.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, 2.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, -2.0f, 0.0f)
            }
            materialPath {
                moveTo(14.0f, 8.0f)
                moveToRelative(-1.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, 2.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, -2.0f, 0.0f)
            }
            materialPath {
                moveTo(12.0f, 6.0f)
                moveToRelative(-1.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, 2.0f, 0.0f)
                arcToRelative(1.0f, 1.0f, 0.0f, true, true, -2.0f, 0.0f)
            }
            materialPath {
                moveTo(7.0f, 19.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-1.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(7.0f)
                verticalLineTo(19.0f)
                close()
            }
        }
        return _filled_SportsGolf!!
    }

private var _filled_SportsGolf: ImageVector? = null

public val Icons.Filled.SportsGymnastics: ImageVector
    get() {
        if (_filled_SportsGymnastics != null) {
            return _filled_SportsGymnastics!!
        }
        _filled_SportsGymnastics = materialIcon(name = "Filled.SportsGymnastics") {
            materialPath {
                moveTo(4.0f, 6.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveTo(7.1f, 8.0f, 6.0f, 8.0f)
                reflectiveCurveTo(4.0f, 7.1f, 4.0f, 6.0f)
                close()
                moveTo(1.0f, 9.0f)
                horizontalLineToRelative(6.0f)
                lineToRelative(7.0f, -5.0f)
                lineToRelative(1.31f, 1.52f)
                lineTo(11.14f, 8.5f)
                horizontalLineTo(14.0f)
                lineTo(21.8f, 4.0f)
                lineTo(23.0f, 5.4f)
                lineTo(14.5f, 12.0f)
                lineTo(14.0f, 22.0f)
                horizontalLineToRelative(-2.0f)
                lineToRelative(-0.5f, -10.0f)
                lineTo(8.0f, 11.0f)
                horizontalLineTo(1.0f)
                verticalLineTo(9.0f)
                close()
            }
        }
        return _filled_SportsGymnastics!!
    }

private var _filled_SportsGymnastics: ImageVector? = null

public val Icons.Filled.SportsMartialArts: ImageVector
    get() {
        if (_filled_SportsMartialArts != null) {
            return _filled_SportsMartialArts!!
        }
        _filled_SportsMartialArts = materialIcon(name = "Filled.SportsMartialArts") {
            materialPath {
                moveTo(19.8f, 2.0f)
                lineToRelative(-8.2f, 6.7f)
                lineToRelative(-1.21f, -1.04f)
                lineToRelative(3.6f, -2.08f)
                lineToRelative(-4.58f, -4.58f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(2.74f, 2.74f)
                lineToRelative(-5.74f, 3.31f)
                lineToRelative(-1.19f, 4.29f)
                lineToRelative(2.46f, 4.25f)
                lineToRelative(1.73f, -1.0f)
                lineToRelative(-2.03f, -3.52f)
                lineToRelative(0.35f, -1.3f)
                lineToRelative(3.18f, 1.82f)
                lineToRelative(0.5f, 9.0f)
                lineToRelative(2.0f, 0.0f)
                lineToRelative(0.5f, -10.0f)
                lineToRelative(8.5f, -8.6f)
                close()
            }
            materialPath {
                moveTo(5.0f, 5.0f)
                moveToRelative(-2.0f, 0.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, true, true, 4.0f, 0.0f)
                arcToRelative(2.0f, 2.0f, 0.0f, true, true, -4.0f, 0.0f)
            }
        }
        return _filled_SportsMartialArts!!
    }

private var _filled_SportsMartialArts: ImageVector? = null

public val Icons.Filled.SportsSoccer: ImageVector
    get() {
        if (_filled_SportsSoccer != null) {
            return _filled_SportsSoccer!!
        }
        _filled_SportsSoccer = materialIcon(name = "Filled.SportsSoccer") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                curveToRelative(0.0f, 5.52f, 4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                curveTo(22.0f, 6.48f, 17.52f, 2.0f, 12.0f, 2.0f)
                close()
                moveTo(13.0f, 5.3f)
                lineToRelative(1.35f, -0.95f)
                curveToRelative(1.82f, 0.56f, 3.37f, 1.76f, 4.38f, 3.34f)
                lineToRelative(-0.39f, 1.34f)
                lineToRelative(-1.35f, 0.46f)
                lineTo(13.0f, 6.7f)
                verticalLineTo(5.3f)
                close()
                moveTo(9.65f, 4.35f)
                lineTo(11.0f, 5.3f)
                verticalLineToRelative(1.4f)
                lineTo(7.01f, 9.49f)
                lineTo(5.66f, 9.03f)
                lineTo(5.27f, 7.69f)
                curveTo(6.28f, 6.12f, 7.83f, 4.92f, 9.65f, 4.35f)
                close()
                moveTo(7.08f, 17.11f)
                lineToRelative(-1.14f, 0.1f)
                curveTo(4.73f, 15.81f, 4.0f, 13.99f, 4.0f, 12.0f)
                curveToRelative(0.0f, -0.12f, 0.01f, -0.23f, 0.02f, -0.35f)
                lineToRelative(1.0f, -0.73f)
                lineTo(6.4f, 11.4f)
                lineToRelative(1.46f, 4.34f)
                lineTo(7.08f, 17.11f)
                close()
                moveTo(14.5f, 19.59f)
                curveTo(13.71f, 19.85f, 12.87f, 20.0f, 12.0f, 20.0f)
                reflectiveCurveToRelative(-1.71f, -0.15f, -2.5f, -0.41f)
                lineToRelative(-0.69f, -1.49f)
                lineTo(9.45f, 17.0f)
                horizontalLineToRelative(5.11f)
                lineToRelative(0.64f, 1.11f)
                lineTo(14.5f, 19.59f)
                close()
                moveTo(14.27f, 15.0f)
                horizontalLineTo(9.73f)
                lineToRelative(-1.35f, -4.02f)
                lineTo(12.0f, 8.44f)
                lineToRelative(3.63f, 2.54f)
                lineTo(14.27f, 15.0f)
                close()
                moveTo(18.06f, 17.21f)
                lineToRelative(-1.14f, -0.1f)
                lineToRelative(-0.79f, -1.37f)
                lineToRelative(1.46f, -4.34f)
                lineToRelative(1.39f, -0.47f)
                lineToRelative(1.0f, 0.73f)
                curveTo(19.99f, 11.77f, 20.0f, 11.88f, 20.0f, 12.0f)
                curveTo(20.0f, 13.99f, 19.27f, 15.81f, 18.06f, 17.21f)
                close()
            }
        }
        return _filled_SportsSoccer!!
    }

private var _filled_SportsSoccer: ImageVector? = null

public val Icons.Filled.SportsTennis: ImageVector
    get() {
        if (_filled_SportsTennis != null) {
            return _filled_SportsTennis!!
        }
        _filled_SportsTennis = materialIcon(name = "Filled.SportsTennis") {
            materialPath {
                moveTo(19.52f, 2.49f)
                curveToRelative(-2.34f, -2.34f, -6.62f, -1.87f, -9.55f, 1.06f)
                curveToRelative(-1.6f, 1.6f, -2.52f, 3.87f, -2.54f, 5.46f)
                curveToRelative(-0.02f, 1.58f, 0.26f, 3.89f, -1.35f, 5.5f)
                lineToRelative(-4.24f, 4.24f)
                lineToRelative(1.42f, 1.42f)
                lineToRelative(4.24f, -4.24f)
                curveToRelative(1.61f, -1.61f, 3.92f, -1.33f, 5.5f, -1.35f)
                reflectiveCurveToRelative(3.86f, -0.94f, 5.46f, -2.54f)
                curveTo(21.38f, 9.11f, 21.86f, 4.83f, 19.52f, 2.49f)
                close()
                moveTo(10.32f, 11.68f)
                curveToRelative(-1.53f, -1.53f, -1.05f, -4.61f, 1.06f, -6.72f)
                reflectiveCurveToRelative(5.18f, -2.59f, 6.72f, -1.06f)
                curveToRelative(1.53f, 1.53f, 1.05f, 4.61f, -1.06f, 6.72f)
                reflectiveCurveTo(11.86f, 13.21f, 10.32f, 11.68f)
                close()
            }
            materialPath {
                moveTo(18.0f, 17.0f)
                curveToRelative(0.53f, 0.0f, 1.04f, 0.21f, 1.41f, 0.59f)
                curveToRelative(0.78f, 0.78f, 0.78f, 2.05f, 0.0f, 2.83f)
                curveTo(19.04f, 20.79f, 18.53f, 21.0f, 18.0f, 21.0f)
                reflectiveCurveToRelative(-1.04f, -0.21f, -1.41f, -0.59f)
                curveToRelative(-0.78f, -0.78f, -0.78f, -2.05f, 0.0f, -2.83f)
                curveTo(16.96f, 17.21f, 17.47f, 17.0f, 18.0f, 17.0f)
                moveTo(18.0f, 15.0f)
                curveToRelative(-1.02f, 0.0f, -2.05f, 0.39f, -2.83f, 1.17f)
                curveToRelative(-1.56f, 1.56f, -1.56f, 4.09f, 0.0f, 5.66f)
                curveTo(15.95f, 22.61f, 16.98f, 23.0f, 18.0f, 23.0f)
                reflectiveCurveToRelative(2.05f, -0.39f, 2.83f, -1.17f)
                curveToRelative(1.56f, -1.56f, 1.56f, -4.09f, 0.0f, -5.66f)
                curveTo(20.05f, 15.39f, 19.02f, 15.0f, 18.0f, 15.0f)
                lineTo(18.0f, 15.0f)
                close()
            }
        }
        return _filled_SportsTennis!!
    }

private var _filled_SportsTennis: ImageVector? = null

public val Icons.Filled.SportsVolleyball: ImageVector
    get() {
        if (_filled_SportsVolleyball != null) {
            return _filled_SportsVolleyball!!
        }
        _filled_SportsVolleyball = materialIcon(name = "Filled.SportsVolleyball") {
            materialPath {
                moveTo(6.0f, 4.01f)
                curveTo(3.58f, 5.84f, 2.0f, 8.73f, 2.0f, 12.0f)
                curveToRelative(0.0f, 1.46f, 0.32f, 2.85f, 0.89f, 4.11f)
                lineTo(6.0f, 14.31f)
                verticalLineTo(4.01f)
                close()
            }
            materialPath {
                moveTo(11.0f, 11.42f)
                verticalLineTo(2.05f)
                curveTo(9.94f, 2.16f, 8.93f, 2.43f, 8.0f, 2.84f)
                verticalLineToRelative(10.32f)
                lineTo(11.0f, 11.42f)
                close()
            }
            materialPath {
                moveTo(12.0f, 13.15f)
                lineToRelative(-8.11f, 4.68f)
                curveToRelative(0.61f, 0.84f, 1.34f, 1.59f, 2.18f, 2.2f)
                lineTo(15.0f, 14.89f)
                lineTo(12.0f, 13.15f)
                close()
            }
            materialPath {
                moveTo(13.0f, 7.96f)
                verticalLineToRelative(3.46f)
                lineToRelative(8.11f, 4.68f)
                curveToRelative(0.42f, -0.93f, 0.7f, -1.93f, 0.82f, -2.98f)
                lineTo(13.0f, 7.96f)
                close()
            }
            materialPath {
                moveTo(8.07f, 21.2f)
                curveTo(9.28f, 21.71f, 10.6f, 22.0f, 12.0f, 22.0f)
                curveToRelative(3.34f, 0.0f, 6.29f, -1.65f, 8.11f, -4.16f)
                lineTo(17.0f, 16.04f)
                lineTo(8.07f, 21.2f)
                close()
            }
            materialPath {
                moveTo(21.92f, 10.81f)
                curveToRelative(-0.55f, -4.63f, -4.26f, -8.3f, -8.92f, -8.76f)
                verticalLineToRelative(3.6f)
                lineTo(21.92f, 10.81f)
                close()
            }
        }
        return _filled_SportsVolleyball!!
    }

private var _filled_SportsVolleyball: ImageVector? = null

public val Icons.Filled.Stop: ImageVector
    get() {
        if (_filled_Stop != null) {
            return _filled_Stop!!
        }
        _filled_Stop = materialIcon(name = "Filled.Stop") {
            materialPath {
                moveTo(6.0f, 6.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(12.0f)
                horizontalLineTo(6.0f)
                close()
            }
        }
        return _filled_Stop!!
    }

private var _filled_Stop: ImageVector? = null

public val Icons.Filled.Storage: ImageVector
    get() {
        if (_filled_Storage != null) {
            return _filled_Storage!!
        }
        _filled_Storage = materialIcon(name = "Filled.Storage") {
            materialPath {
                moveTo(2.0f, 20.0f)
                horizontalLineToRelative(20.0f)
                verticalLineToRelative(-4.0f)
                lineTo(2.0f, 16.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(4.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 19.0f)
                verticalLineToRelative(-2.0f)
                close()
                moveTo(2.0f, 4.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(20.0f)
                lineTo(22.0f, 4.0f)
                lineTo(2.0f, 4.0f)
                close()
                moveTo(6.0f, 7.0f)
                lineTo(4.0f, 7.0f)
                lineTo(4.0f, 5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(2.0f, 14.0f)
                horizontalLineToRelative(20.0f)
                verticalLineToRelative(-4.0f)
                lineTo(2.0f, 10.0f)
                verticalLineToRelative(4.0f)
                close()
                moveTo(4.0f, 11.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(2.0f)
                lineTo(4.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                close()
            }
        }
        return _filled_Storage!!
    }

private var _filled_Storage: ImageVector? = null

public val Icons.Filled.Straighten: ImageVector
    get() {
        if (_filled_Straighten != null) {
            return _filled_Straighten!!
        }
        _filled_Straighten = materialIcon(name = "Filled.Straighten") {
            materialPath {
                moveTo(21.0f, 6.0f)
                lineTo(3.0f, 6.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(18.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(23.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, -0.9f, -2.0f, -2.0f, -2.0f)
                close()
                moveTo(21.0f, 16.0f)
                lineTo(3.0f, 16.0f)
                lineTo(3.0f, 8.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(2.0f)
                lineTo(7.0f, 8.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(2.0f)
                lineTo(11.0f, 8.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(2.0f)
                lineTo(15.0f, 8.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(4.0f)
                horizontalLineToRelative(2.0f)
                lineTo(19.0f, 8.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(8.0f)
                close()
            }
        }
        return _filled_Straighten!!
    }

private var _filled_Straighten: ImageVector? = null

public val Icons.Filled.SwapHoriz: ImageVector
    get() {
        if (_filled_SwapHoriz != null) {
            return _filled_SwapHoriz!!
        }
        _filled_SwapHoriz = materialIcon(name = "Filled.SwapHoriz") {
            materialPath {
                moveTo(6.99f, 11.0f)
                lineTo(3.0f, 15.0f)
                lineToRelative(3.99f, 4.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(6.99f)
                verticalLineToRelative(-3.0f)
                close()
                moveTo(21.0f, 9.0f)
                lineToRelative(-3.99f, -4.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(10.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(7.01f)
                verticalLineToRelative(3.0f)
                lineTo(21.0f, 9.0f)
                close()
            }
        }
        return _filled_SwapHoriz!!
    }

private var _filled_SwapHoriz: ImageVector? = null

public val Icons.Filled.Sync: ImageVector
    get() {
        if (_filled_Sync != null) {
            return _filled_Sync!!
        }
        _filled_Sync = materialIcon(name = "Filled.Sync") {
            materialPath {
                moveTo(12.0f, 4.0f)
                lineTo(12.0f, 1.0f)
                lineTo(8.0f, 5.0f)
                lineToRelative(4.0f, 4.0f)
                lineTo(12.0f, 6.0f)
                curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                curveToRelative(0.0f, 1.01f, -0.25f, 1.97f, -0.7f, 2.8f)
                lineToRelative(1.46f, 1.46f)
                curveTo(19.54f, 15.03f, 20.0f, 13.57f, 20.0f, 12.0f)
                curveToRelative(0.0f, -4.42f, -3.58f, -8.0f, -8.0f, -8.0f)
                close()
                moveTo(12.0f, 18.0f)
                curveToRelative(-3.31f, 0.0f, -6.0f, -2.69f, -6.0f, -6.0f)
                curveToRelative(0.0f, -1.01f, 0.25f, -1.97f, 0.7f, -2.8f)
                lineTo(5.24f, 7.74f)
                curveTo(4.46f, 8.97f, 4.0f, 10.43f, 4.0f, 12.0f)
                curveToRelative(0.0f, 4.42f, 3.58f, 8.0f, 8.0f, 8.0f)
                verticalLineToRelative(3.0f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(-4.0f, -4.0f)
                verticalLineToRelative(3.0f)
                close()
            }
        }
        return _filled_Sync!!
    }

private var _filled_Sync: ImageVector? = null

public val Icons.Filled.SyncProblem: ImageVector
    get() {
        if (_filled_SyncProblem != null) {
            return _filled_SyncProblem!!
        }
        _filled_SyncProblem = materialIcon(name = "Filled.SyncProblem") {
            materialPath {
                moveTo(3.0f, 12.0f)
                curveToRelative(0.0f, 2.21f, 0.91f, 4.2f, 2.36f, 5.64f)
                lineTo(3.0f, 20.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-6.0f)
                lineToRelative(-2.24f, 2.24f)
                curveTo(5.68f, 15.15f, 5.0f, 13.66f, 5.0f, 12.0f)
                curveToRelative(0.0f, -2.61f, 1.67f, -4.83f, 4.0f, -5.65f)
                lineTo(9.0f, 4.26f)
                curveTo(5.55f, 5.15f, 3.0f, 8.27f, 3.0f, 12.0f)
                close()
                moveTo(11.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.0f)
                close()
                moveTo(21.0f, 4.0f)
                horizontalLineToRelative(-6.0f)
                verticalLineToRelative(6.0f)
                lineToRelative(2.24f, -2.24f)
                curveTo(18.32f, 8.85f, 19.0f, 10.34f, 19.0f, 12.0f)
                curveToRelative(0.0f, 2.61f, -1.67f, 4.83f, -4.0f, 5.65f)
                verticalLineToRelative(2.09f)
                curveToRelative(3.45f, -0.89f, 6.0f, -4.01f, 6.0f, -7.74f)
                curveToRelative(0.0f, -2.21f, -0.91f, -4.2f, -2.36f, -5.64f)
                lineTo(21.0f, 4.0f)
                close()
                moveTo(11.0f, 13.0f)
                horizontalLineToRelative(2.0f)
                lineTo(13.0f, 7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                close()
            }
        }
        return _filled_SyncProblem!!
    }

private var _filled_SyncProblem: ImageVector? = null

public val Icons.Filled.Terrain: ImageVector
    get() {
        if (_filled_Terrain != null) {
            return _filled_Terrain!!
        }
        _filled_Terrain = materialIcon(name = "Filled.Terrain") {
            materialPath {
                moveTo(14.0f, 6.0f)
                lineToRelative(-3.75f, 5.0f)
                lineToRelative(2.85f, 3.8f)
                lineToRelative(-1.6f, 1.2f)
                curveTo(9.81f, 13.75f, 7.0f, 10.0f, 7.0f, 10.0f)
                lineToRelative(-6.0f, 8.0f)
                horizontalLineToRelative(22.0f)
                lineTo(14.0f, 6.0f)
                close()
            }
        }
        return _filled_Terrain!!
    }

private var _filled_Terrain: ImageVector? = null

public val Icons.Filled.Thermostat: ImageVector
    get() {
        if (_filled_Thermostat != null) {
            return _filled_Thermostat!!
        }
        _filled_Thermostat = materialIcon(name = "Filled.Thermostat") {
            materialPath {
                moveTo(15.0f, 13.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -1.66f, -1.34f, -3.0f, -3.0f, -3.0f)
                reflectiveCurveTo(9.0f, 3.34f, 9.0f, 5.0f)
                verticalLineToRelative(8.0f)
                curveToRelative(-1.21f, 0.91f, -2.0f, 2.37f, -2.0f, 4.0f)
                curveToRelative(0.0f, 2.76f, 2.24f, 5.0f, 5.0f, 5.0f)
                reflectiveCurveToRelative(5.0f, -2.24f, 5.0f, -5.0f)
                curveTo(17.0f, 15.37f, 16.21f, 13.91f, 15.0f, 13.0f)
                close()
                moveTo(11.0f, 11.0f)
                verticalLineTo(5.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(-1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineTo(11.0f)
                close()
            }
        }
        return _filled_Thermostat!!
    }

private var _filled_Thermostat: ImageVector? = null

public val Icons.Filled.Timeline: ImageVector
    get() {
        if (_filled_Timeline != null) {
            return _filled_Timeline!!
        }
        _filled_Timeline = materialIcon(name = "Filled.Timeline") {
            materialPath {
                moveTo(23.0f, 8.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                curveToRelative(-0.18f, 0.0f, -0.35f, -0.02f, -0.51f, -0.07f)
                lineToRelative(-3.56f, 3.55f)
                curveTo(16.98f, 13.64f, 17.0f, 13.82f, 17.0f, 14.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                curveToRelative(0.0f, -0.18f, 0.02f, -0.36f, 0.07f, -0.52f)
                lineToRelative(-2.55f, -2.55f)
                curveTo(10.36f, 10.98f, 10.18f, 11.0f, 10.0f, 11.0f)
                reflectiveCurveToRelative(-0.36f, -0.02f, -0.52f, -0.07f)
                lineToRelative(-4.55f, 4.56f)
                curveTo(4.98f, 15.65f, 5.0f, 15.82f, 5.0f, 16.0f)
                curveToRelative(0.0f, 1.1f, -0.9f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveToRelative(-2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                curveToRelative(0.18f, 0.0f, 0.35f, 0.02f, 0.51f, 0.07f)
                lineToRelative(4.56f, -4.55f)
                curveTo(8.02f, 9.36f, 8.0f, 9.18f, 8.0f, 9.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                curveToRelative(0.0f, 0.18f, -0.02f, 0.36f, -0.07f, 0.52f)
                lineToRelative(2.55f, 2.55f)
                curveTo(14.64f, 12.02f, 14.82f, 12.0f, 15.0f, 12.0f)
                reflectiveCurveToRelative(0.36f, 0.02f, 0.52f, 0.07f)
                lineToRelative(3.55f, -3.56f)
                curveTo(19.02f, 8.35f, 19.0f, 8.18f, 19.0f, 8.0f)
                curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveTo(23.0f, 6.9f, 23.0f, 8.0f)
                close()
            }
        }
        return _filled_Timeline!!
    }

private var _filled_Timeline: ImageVector? = null

public val Icons.Filled.Timer: ImageVector
    get() {
        if (_filled_Timer != null) {
            return _filled_Timer!!
        }
        _filled_Timer = materialIcon(name = "Filled.Timer") {
            materialPath {
                moveTo(9.0f, 1.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-6.0f)
                close()
            }
            materialPath {
                moveTo(19.03f, 7.39f)
                lineToRelative(1.42f, -1.42f)
                curveToRelative(-0.43f, -0.51f, -0.9f, -0.99f, -1.41f, -1.41f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(16.07f, 4.74f, 14.12f, 4.0f, 12.0f, 4.0f)
                curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
                curveToRelative(0.0f, 4.97f, 4.02f, 9.0f, 9.0f, 9.0f)
                reflectiveCurveToRelative(9.0f, -4.03f, 9.0f, -9.0f)
                curveTo(21.0f, 10.88f, 20.26f, 8.93f, 19.03f, 7.39f)
                close()
                moveTo(13.0f, 14.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(8.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(14.0f)
                close()
            }
        }
        return _filled_Timer!!
    }

private var _filled_Timer: ImageVector? = null

public val Icons.Filled.TouchApp: ImageVector
    get() {
        if (_filled_TouchApp != null) {
            return _filled_TouchApp!!
        }
        _filled_TouchApp = materialIcon(name = "Filled.TouchApp") {
            materialPath {
                moveTo(9.0f, 11.24f)
                verticalLineTo(7.5f)
                curveTo(9.0f, 6.12f, 10.12f, 5.0f, 11.5f, 5.0f)
                reflectiveCurveTo(14.0f, 6.12f, 14.0f, 7.5f)
                verticalLineToRelative(3.74f)
                curveToRelative(1.21f, -0.81f, 2.0f, -2.18f, 2.0f, -3.74f)
                curveTo(16.0f, 5.01f, 13.99f, 3.0f, 11.5f, 3.0f)
                reflectiveCurveTo(7.0f, 5.01f, 7.0f, 7.5f)
                curveTo(7.0f, 9.06f, 7.79f, 10.43f, 9.0f, 11.24f)
                close()
                moveTo(18.84f, 15.87f)
                lineToRelative(-4.54f, -2.26f)
                curveToRelative(-0.17f, -0.07f, -0.35f, -0.11f, -0.54f, -0.11f)
                horizontalLineTo(13.0f)
                verticalLineToRelative(-6.0f)
                curveTo(13.0f, 6.67f, 12.33f, 6.0f, 11.5f, 6.0f)
                reflectiveCurveTo(10.0f, 6.67f, 10.0f, 7.5f)
                verticalLineToRelative(10.74f)
                curveToRelative(-3.6f, -0.76f, -3.54f, -0.75f, -3.67f, -0.75f)
                curveToRelative(-0.31f, 0.0f, -0.59f, 0.13f, -0.79f, 0.33f)
                lineToRelative(-0.79f, 0.8f)
                lineToRelative(4.94f, 4.94f)
                curveTo(9.96f, 23.83f, 10.34f, 24.0f, 10.75f, 24.0f)
                horizontalLineToRelative(6.79f)
                curveToRelative(0.75f, 0.0f, 1.33f, -0.55f, 1.44f, -1.28f)
                lineToRelative(0.75f, -5.27f)
                curveToRelative(0.01f, -0.07f, 0.02f, -0.14f, 0.02f, -0.2f)
                curveTo(19.75f, 16.63f, 19.37f, 16.09f, 18.84f, 15.87f)
                close()
            }
        }
        return _filled_TouchApp!!
    }

private var _filled_TouchApp: ImageVector? = null

public val Icons.Filled.TrackChanges: ImageVector
    get() {
        if (_filled_TrackChanges != null) {
            return _filled_TrackChanges!!
        }
        _filled_TrackChanges = materialIcon(name = "Filled.TrackChanges") {
            materialPath {
                moveTo(19.07f, 4.93f)
                lineToRelative(-1.41f, 1.41f)
                curveTo(19.1f, 7.79f, 20.0f, 9.79f, 20.0f, 12.0f)
                curveToRelative(0.0f, 4.42f, -3.58f, 8.0f, -8.0f, 8.0f)
                reflectiveCurveToRelative(-8.0f, -3.58f, -8.0f, -8.0f)
                curveToRelative(0.0f, -4.08f, 3.05f, -7.44f, 7.0f, -7.93f)
                verticalLineToRelative(2.02f)
                curveTo(8.16f, 6.57f, 6.0f, 9.03f, 6.0f, 12.0f)
                curveToRelative(0.0f, 3.31f, 2.69f, 6.0f, 6.0f, 6.0f)
                reflectiveCurveToRelative(6.0f, -2.69f, 6.0f, -6.0f)
                curveToRelative(0.0f, -1.66f, -0.67f, -3.16f, -1.76f, -4.24f)
                lineToRelative(-1.41f, 1.41f)
                curveTo(15.55f, 9.9f, 16.0f, 10.9f, 16.0f, 12.0f)
                curveToRelative(0.0f, 2.21f, -1.79f, 4.0f, -4.0f, 4.0f)
                reflectiveCurveToRelative(-4.0f, -1.79f, -4.0f, -4.0f)
                curveToRelative(0.0f, -1.86f, 1.28f, -3.41f, 3.0f, -3.86f)
                verticalLineToRelative(2.14f)
                curveToRelative(-0.6f, 0.35f, -1.0f, 0.98f, -1.0f, 1.72f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                reflectiveCurveToRelative(2.0f, -0.9f, 2.0f, -2.0f)
                curveToRelative(0.0f, -0.74f, -0.4f, -1.38f, -1.0f, -1.72f)
                verticalLineTo(2.0f)
                horizontalLineToRelative(-1.0f)
                curveTo(6.48f, 2.0f, 2.0f, 6.48f, 2.0f, 12.0f)
                reflectiveCurveToRelative(4.48f, 10.0f, 10.0f, 10.0f)
                reflectiveCurveToRelative(10.0f, -4.48f, 10.0f, -10.0f)
                curveToRelative(0.0f, -2.76f, -1.12f, -5.26f, -2.93f, -7.07f)
                close()
            }
        }
        return _filled_TrackChanges!!
    }

private var _filled_TrackChanges: ImageVector? = null

public val Icons.Filled.Tune: ImageVector
    get() {
        if (_filled_Tune != null) {
            return _filled_Tune!!
        }
        _filled_Tune = materialIcon(name = "Filled.Tune") {
            materialPath {
                moveTo(3.0f, 17.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-2.0f)
                lineTo(3.0f, 17.0f)
                close()
                moveTo(3.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                lineTo(13.0f, 5.0f)
                lineTo(3.0f, 5.0f)
                close()
                moveTo(13.0f, 21.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-8.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                close()
                moveTo(7.0f, 9.0f)
                verticalLineToRelative(2.0f)
                lineTo(3.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(2.0f)
                lineTo(9.0f, 9.0f)
                lineTo(7.0f, 9.0f)
                close()
                moveTo(21.0f, 13.0f)
                verticalLineToRelative(-2.0f)
                lineTo(11.0f, 11.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(10.0f)
                close()
                moveTo(15.0f, 9.0f)
                horizontalLineToRelative(2.0f)
                lineTo(17.0f, 7.0f)
                horizontalLineToRelative(4.0f)
                lineTo(21.0f, 5.0f)
                horizontalLineToRelative(-4.0f)
                lineTo(17.0f, 3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(6.0f)
                close()
            }
        }
        return _filled_Tune!!
    }

private var _filled_Tune: ImageVector? = null

public val Icons.Filled.Upload: ImageVector
    get() {
        if (_filled_Upload != null) {
            return _filled_Upload!!
        }
        _filled_Upload = materialIcon(name = "Filled.Upload") {
            materialPath {
                moveTo(5.0f, 20.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(20.0f)
                close()
                moveTo(5.0f, 10.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(4.0f)
                lineToRelative(-7.0f, -7.0f)
                lineTo(5.0f, 10.0f)
                close()
            }
        }
        return _filled_Upload!!
    }

private var _filled_Upload: ImageVector? = null

public val Icons.Filled.Vibration: ImageVector
    get() {
        if (_filled_Vibration != null) {
            return _filled_Vibration!!
        }
        _filled_Vibration = materialIcon(name = "Filled.Vibration") {
            materialPath {
                moveTo(0.0f, 15.0f)
                horizontalLineToRelative(2.0f)
                lineTo(2.0f, 9.0f)
                lineTo(0.0f, 9.0f)
                verticalLineToRelative(6.0f)
                close()
                moveTo(3.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                lineTo(5.0f, 7.0f)
                lineTo(3.0f, 7.0f)
                verticalLineToRelative(10.0f)
                close()
                moveTo(22.0f, 9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(2.0f)
                lineTo(24.0f, 9.0f)
                horizontalLineToRelative(-2.0f)
                close()
                moveTo(19.0f, 17.0f)
                horizontalLineToRelative(2.0f)
                lineTo(21.0f, 7.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(10.0f)
                close()
                moveTo(16.5f, 3.0f)
                horizontalLineToRelative(-9.0f)
                curveTo(6.67f, 3.0f, 6.0f, 3.67f, 6.0f, 4.5f)
                verticalLineToRelative(15.0f)
                curveToRelative(0.0f, 0.83f, 0.67f, 1.5f, 1.5f, 1.5f)
                horizontalLineToRelative(9.0f)
                curveToRelative(0.83f, 0.0f, 1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineToRelative(-15.0f)
                curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                close()
                moveTo(16.0f, 19.0f)
                lineTo(8.0f, 19.0f)
                lineTo(8.0f, 5.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(14.0f)
                close()
            }
        }
        return _filled_Vibration!!
    }

private var _filled_Vibration: ImageVector? = null

public val Icons.Filled.Videocam: ImageVector
    get() {
        if (_filled_Videocam != null) {
            return _filled_Videocam!!
        }
        _filled_Videocam = materialIcon(name = "Filled.Videocam") {
            materialPath {
                moveTo(17.0f, 10.5f)
                verticalLineTo(7.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(10.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                horizontalLineToRelative(12.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(-3.5f)
                lineToRelative(4.0f, 4.0f)
                verticalLineToRelative(-11.0f)
                lineToRelative(-4.0f, 4.0f)
                close()
            }
        }
        return _filled_Videocam!!
    }

private var _filled_Videocam: ImageVector? = null

public val Icons.Filled.WarningAmber: ImageVector
    get() {
        if (_filled_WarningAmber != null) {
            return _filled_WarningAmber!!
        }
        _filled_WarningAmber = materialIcon(name = "Filled.WarningAmber") {
            materialPath {
                moveTo(12.0f, 5.99f)
                lineTo(19.53f, 19.0f)
                horizontalLineTo(4.47f)
                lineTo(12.0f, 5.99f)
                moveTo(12.0f, 2.0f)
                lineTo(1.0f, 21.0f)
                horizontalLineToRelative(22.0f)
                lineTo(12.0f, 2.0f)
                lineTo(12.0f, 2.0f)
                close()
            }
            materialPath {
                moveTo(13.0f, 16.0f)
                lineToRelative(-2.0f, 0.0f)
                lineToRelative(0.0f, 2.0f)
                lineToRelative(2.0f, 0.0f)
                close()
            }
            materialPath {
                moveTo(13.0f, 10.0f)
                lineToRelative(-2.0f, 0.0f)
                lineToRelative(0.0f, 5.0f)
                lineToRelative(2.0f, 0.0f)
                close()
            }
        }
        return _filled_WarningAmber!!
    }

private var _filled_WarningAmber: ImageVector? = null

public val Icons.Filled.Watch: ImageVector
    get() {
        if (_filled_Watch != null) {
            return _filled_Watch!!
        }
        _filled_Watch = materialIcon(name = "Filled.Watch") {
            materialPath {
                moveTo(20.0f, 12.0f)
                curveToRelative(0.0f, -2.54f, -1.19f, -4.81f, -3.04f, -6.27f)
                lineTo(16.0f, 0.0f)
                horizontalLineTo(8.0f)
                lineToRelative(-0.95f, 5.73f)
                curveTo(5.19f, 7.19f, 4.0f, 9.45f, 4.0f, 12.0f)
                reflectiveCurveToRelative(1.19f, 4.81f, 3.05f, 6.27f)
                lineTo(8.0f, 24.0f)
                horizontalLineToRelative(8.0f)
                lineToRelative(0.96f, -5.73f)
                curveTo(18.81f, 16.81f, 20.0f, 14.54f, 20.0f, 12.0f)
                close()
                moveTo(6.0f, 12.0f)
                curveToRelative(0.0f, -3.31f, 2.69f, -6.0f, 6.0f, -6.0f)
                reflectiveCurveToRelative(6.0f, 2.69f, 6.0f, 6.0f)
                reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
                reflectiveCurveToRelative(-6.0f, -2.69f, -6.0f, -6.0f)
                close()
            }
        }
        return _filled_Watch!!
    }

private var _filled_Watch: ImageVector? = null

public val Icons.Filled.WaterDrop: ImageVector
    get() {
        if (_filled_WaterDrop != null) {
            return _filled_WaterDrop!!
        }
        _filled_WaterDrop = materialIcon(name = "Filled.WaterDrop") {
            materialPath {
                moveTo(12.0f, 2.0f)
                curveToRelative(-5.33f, 4.55f, -8.0f, 8.48f, -8.0f, 11.8f)
                curveToRelative(0.0f, 4.98f, 3.8f, 8.2f, 8.0f, 8.2f)
                reflectiveCurveToRelative(8.0f, -3.22f, 8.0f, -8.2f)
                curveTo(20.0f, 10.48f, 17.33f, 6.55f, 12.0f, 2.0f)
                close()
                moveTo(7.83f, 14.0f)
                curveToRelative(0.37f, 0.0f, 0.67f, 0.26f, 0.74f, 0.62f)
                curveToRelative(0.41f, 2.22f, 2.28f, 2.98f, 3.64f, 2.87f)
                curveToRelative(0.43f, -0.02f, 0.79f, 0.32f, 0.79f, 0.75f)
                curveToRelative(0.0f, 0.4f, -0.32f, 0.73f, -0.72f, 0.75f)
                curveToRelative(-2.13f, 0.13f, -4.62f, -1.09f, -5.19f, -4.12f)
                curveTo(7.01f, 14.42f, 7.37f, 14.0f, 7.83f, 14.0f)
                close()
            }
        }
        return _filled_WaterDrop!!
    }

private var _filled_WaterDrop: ImageVector? = null

public val Icons.Filled.WbSunny: ImageVector
    get() {
        if (_filled_WbSunny != null) {
            return _filled_WbSunny!!
        }
        _filled_WbSunny = materialIcon(name = "Filled.WbSunny") {
            materialPath {
                moveTo(6.76f, 4.84f)
                lineToRelative(-1.8f, -1.79f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(1.79f, 1.79f)
                lineToRelative(1.42f, -1.41f)
                close()
                moveTo(4.0f, 10.5f)
                lineTo(1.0f, 10.5f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-2.0f)
                close()
                moveTo(13.0f, 0.55f)
                horizontalLineToRelative(-2.0f)
                lineTo(11.0f, 3.5f)
                horizontalLineToRelative(2.0f)
                lineTo(13.0f, 0.55f)
                close()
                moveTo(20.45f, 4.46f)
                lineToRelative(-1.41f, -1.41f)
                lineToRelative(-1.79f, 1.79f)
                lineToRelative(1.41f, 1.41f)
                lineToRelative(1.79f, -1.79f)
                close()
                moveTo(17.24f, 18.16f)
                lineToRelative(1.79f, 1.8f)
                lineToRelative(1.41f, -1.41f)
                lineToRelative(-1.8f, -1.79f)
                lineToRelative(-1.4f, 1.4f)
                close()
                moveTo(20.0f, 10.5f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(3.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-3.0f)
                close()
                moveTo(12.0f, 5.5f)
                curveToRelative(-3.31f, 0.0f, -6.0f, 2.69f, -6.0f, 6.0f)
                reflectiveCurveToRelative(2.69f, 6.0f, 6.0f, 6.0f)
                reflectiveCurveToRelative(6.0f, -2.69f, 6.0f, -6.0f)
                reflectiveCurveToRelative(-2.69f, -6.0f, -6.0f, -6.0f)
                close()
                moveTo(11.0f, 22.45f)
                horizontalLineToRelative(2.0f)
                lineTo(13.0f, 19.5f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(2.95f)
                close()
                moveTo(3.55f, 18.54f)
                lineToRelative(1.41f, 1.41f)
                lineToRelative(1.79f, -1.8f)
                lineToRelative(-1.41f, -1.41f)
                lineToRelative(-1.79f, 1.8f)
                close()
            }
        }
        return _filled_WbSunny!!
    }

private var _filled_WbSunny: ImageVector? = null

public val Icons.Outlined.AutoAwesome: ImageVector
    get() {
        if (_outlined_AutoAwesome != null) {
            return _outlined_AutoAwesome!!
        }
        _outlined_AutoAwesome = materialIcon(name = "Outlined.AutoAwesome") {
            materialPath {
                moveTo(19.0f, 9.0f)
                lineToRelative(1.25f, -2.75f)
                lineToRelative(2.75f, -1.25f)
                lineToRelative(-2.75f, -1.25f)
                lineToRelative(-1.25f, -2.75f)
                lineToRelative(-1.25f, 2.75f)
                lineToRelative(-2.75f, 1.25f)
                lineToRelative(2.75f, 1.25f)
                close()
            }
            materialPath {
                moveTo(19.0f, 15.0f)
                lineToRelative(-1.25f, 2.75f)
                lineToRelative(-2.75f, 1.25f)
                lineToRelative(2.75f, 1.25f)
                lineToRelative(1.25f, 2.75f)
                lineToRelative(1.25f, -2.75f)
                lineToRelative(2.75f, -1.25f)
                lineToRelative(-2.75f, -1.25f)
                close()
            }
            materialPath {
                moveTo(11.5f, 9.5f)
                lineTo(9.0f, 4.0f)
                lineTo(6.5f, 9.5f)
                lineTo(1.0f, 12.0f)
                lineToRelative(5.5f, 2.5f)
                lineTo(9.0f, 20.0f)
                lineToRelative(2.5f, -5.5f)
                lineTo(17.0f, 12.0f)
                lineTo(11.5f, 9.5f)
                close()
                moveTo(9.99f, 12.99f)
                lineTo(9.0f, 15.17f)
                lineToRelative(-0.99f, -2.18f)
                lineTo(5.83f, 12.0f)
                lineToRelative(2.18f, -0.99f)
                lineTo(9.0f, 8.83f)
                lineToRelative(0.99f, 2.18f)
                lineTo(12.17f, 12.0f)
                lineTo(9.99f, 12.99f)
                close()
            }
        }
        return _outlined_AutoAwesome!!
    }

private var _outlined_AutoAwesome: ImageVector? = null

public val Icons.Outlined.DeleteSweep: ImageVector
    get() {
        if (_outlined_DeleteSweep != null) {
            return _outlined_DeleteSweep!!
        }
        _outlined_DeleteSweep = materialIcon(name = "Outlined.DeleteSweep") {
            materialPath {
                moveTo(15.0f, 16.0f)
                horizontalLineToRelative(4.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-4.0f)
                close()
                moveTo(15.0f, 8.0f)
                horizontalLineToRelative(7.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-7.0f)
                close()
                moveTo(15.0f, 12.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-6.0f)
                close()
                moveTo(3.0f, 18.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineTo(13.0f, 8.0f)
                lineTo(3.0f, 8.0f)
                verticalLineToRelative(10.0f)
                close()
                moveTo(5.0f, 10.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(8.0f)
                lineTo(5.0f, 18.0f)
                verticalLineToRelative(-8.0f)
                close()
                moveTo(10.0f, 4.0f)
                lineTo(6.0f, 4.0f)
                lineTo(5.0f, 5.0f)
                lineTo(2.0f, 5.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(12.0f)
                lineTo(14.0f, 5.0f)
                horizontalLineToRelative(-3.0f)
                close()
            }
        }
        return _outlined_DeleteSweep!!
    }

private var _outlined_DeleteSweep: ImageVector? = null

public val Icons.Outlined.DoneAll: ImageVector
    get() {
        if (_outlined_DoneAll != null) {
            return _outlined_DoneAll!!
        }
        _outlined_DoneAll = materialIcon(name = "Outlined.DoneAll") {
            materialPath {
                moveTo(18.0f, 7.0f)
                lineToRelative(-1.41f, -1.41f)
                lineToRelative(-6.34f, 6.34f)
                lineToRelative(1.41f, 1.41f)
                lineTo(18.0f, 7.0f)
                close()
                moveTo(22.24f, 5.59f)
                lineTo(11.66f, 16.17f)
                lineTo(7.48f, 12.0f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(11.66f, 19.0f)
                lineToRelative(12.0f, -12.0f)
                lineToRelative(-1.42f, -1.41f)
                close()
                moveTo(0.41f, 13.41f)
                lineTo(6.0f, 19.0f)
                lineToRelative(1.41f, -1.41f)
                lineTo(1.83f, 12.0f)
                lineTo(0.41f, 13.41f)
                close()
            }
        }
        return _outlined_DoneAll!!
    }

private var _outlined_DoneAll: ImageVector? = null

public val Icons.Outlined.GridView: ImageVector
    get() {
        if (_outlined_GridView != null) {
            return _outlined_GridView!!
        }
        _outlined_GridView = materialIcon(name = "Outlined.GridView") {
            materialPath {
                moveTo(3.0f, 3.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(3.0f)
                close()
                moveTo(9.0f, 9.0f)
                horizontalLineTo(5.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(9.0f)
                close()
                moveTo(3.0f, 13.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-8.0f)
                horizontalLineTo(3.0f)
                close()
                moveTo(9.0f, 19.0f)
                horizontalLineTo(5.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(19.0f)
                close()
                moveTo(13.0f, 3.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineTo(3.0f)
                horizontalLineTo(13.0f)
                close()
                moveTo(19.0f, 9.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineTo(5.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(9.0f)
                close()
                moveTo(13.0f, 13.0f)
                verticalLineToRelative(8.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(-8.0f)
                horizontalLineTo(13.0f)
                close()
                moveTo(19.0f, 19.0f)
                horizontalLineToRelative(-4.0f)
                verticalLineToRelative(-4.0f)
                horizontalLineToRelative(4.0f)
                verticalLineTo(19.0f)
                close()
            }
        }
        return _outlined_GridView!!
    }

private var _outlined_GridView: ImageVector? = null

public val Icons.Outlined.HourglassEmpty: ImageVector
    get() {
        if (_outlined_HourglassEmpty != null) {
            return _outlined_HourglassEmpty!!
        }
        _outlined_HourglassEmpty = materialIcon(name = "Outlined.HourglassEmpty") {
            materialPath {
                moveTo(6.0f, 2.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(0.01f)
                lineTo(6.0f, 8.01f)
                lineTo(10.0f, 12.0f)
                lineToRelative(-4.0f, 4.0f)
                lineToRelative(0.01f, 0.01f)
                lineTo(6.0f, 16.01f)
                lineTo(6.0f, 22.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(-5.99f)
                horizontalLineToRelative(-0.01f)
                lineTo(18.0f, 16.0f)
                lineToRelative(-4.0f, -4.0f)
                lineToRelative(4.0f, -3.99f)
                lineToRelative(-0.01f, -0.01f)
                lineTo(18.0f, 8.0f)
                lineTo(18.0f, 2.0f)
                lineTo(6.0f, 2.0f)
                close()
                moveTo(16.0f, 16.5f)
                lineTo(16.0f, 20.0f)
                lineTo(8.0f, 20.0f)
                verticalLineToRelative(-3.5f)
                lineToRelative(4.0f, -4.0f)
                lineToRelative(4.0f, 4.0f)
                close()
                moveTo(12.0f, 11.5f)
                lineToRelative(-4.0f, -4.0f)
                lineTo(8.0f, 4.0f)
                horizontalLineToRelative(8.0f)
                verticalLineToRelative(3.5f)
                lineToRelative(-4.0f, 4.0f)
                close()
            }
        }
        return _outlined_HourglassEmpty!!
    }

private var _outlined_HourglassEmpty: ImageVector? = null

public val Icons.Outlined.Layers: ImageVector
    get() {
        if (_outlined_Layers != null) {
            return _outlined_Layers!!
        }
        _outlined_Layers = materialIcon(name = "Outlined.Layers") {
            materialPath {
                moveTo(11.99f, 18.54f)
                lineToRelative(-7.37f, -5.73f)
                lineTo(3.0f, 14.07f)
                lineToRelative(9.0f, 7.0f)
                lineToRelative(9.0f, -7.0f)
                lineToRelative(-1.63f, -1.27f)
                close()
                moveTo(12.0f, 16.0f)
                lineToRelative(7.36f, -5.73f)
                lineTo(21.0f, 9.0f)
                lineToRelative(-9.0f, -7.0f)
                lineToRelative(-9.0f, 7.0f)
                lineToRelative(1.63f, 1.27f)
                lineTo(12.0f, 16.0f)
                close()
                moveTo(12.0f, 4.53f)
                lineTo(17.74f, 9.0f)
                lineTo(12.0f, 13.47f)
                lineTo(6.26f, 9.0f)
                lineTo(12.0f, 4.53f)
                close()
            }
        }
        return _outlined_Layers!!
    }

private var _outlined_Layers: ImageVector? = null

public val Icons.Outlined.MonitorHeart: ImageVector
    get() {
        if (_outlined_MonitorHeart != null) {
            return _outlined_MonitorHeart!!
        }
        _outlined_MonitorHeart = materialIcon(name = "Outlined.MonitorHeart") {
            materialPath {
                moveTo(20.0f, 4.0f)
                horizontalLineTo(4.0f)
                curveTo(2.9f, 4.0f, 2.0f, 4.9f, 2.0f, 6.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(16.0f)
                verticalLineToRelative(3.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(6.0f)
                curveTo(22.0f, 4.9f, 21.1f, 4.0f, 20.0f, 4.0f)
                close()
            }
            materialPath {
                moveTo(20.0f, 18.0f)
                horizontalLineTo(4.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(3.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineToRelative(-3.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(18.0f)
                close()
            }
            materialPath {
                moveTo(14.89f, 7.55f)
                curveToRelative(-0.34f, -0.68f, -1.45f, -0.68f, -1.79f, 0.0f)
                lineTo(10.0f, 13.76f)
                lineToRelative(-1.11f, -2.21f)
                curveTo(8.72f, 11.21f, 8.38f, 11.0f, 8.0f, 11.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(5.38f)
                lineToRelative(1.72f, 3.45f)
                curveTo(9.28f, 16.79f, 9.62f, 17.0f, 10.0f, 17.0f)
                reflectiveCurveToRelative(0.72f, -0.21f, 0.89f, -0.55f)
                lineTo(14.0f, 10.24f)
                lineToRelative(1.11f, 2.21f)
                curveTo(15.28f, 12.79f, 15.62f, 13.0f, 16.0f, 13.0f)
                horizontalLineToRelative(6.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(-5.38f)
                lineTo(14.89f, 7.55f)
                close()
            }
        }
        return _outlined_MonitorHeart!!
    }

private var _outlined_MonitorHeart: ImageVector? = null

public val Icons.Outlined.NotificationsOff: ImageVector
    get() {
        if (_outlined_NotificationsOff != null) {
            return _outlined_NotificationsOff!!
        }
        _outlined_NotificationsOff = materialIcon(name = "Outlined.NotificationsOff") {
            materialPath {
                moveTo(12.0f, 22.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                horizontalLineToRelative(-4.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                close()
                moveTo(12.0f, 6.5f)
                curveToRelative(2.49f, 0.0f, 4.0f, 2.02f, 4.0f, 4.5f)
                verticalLineToRelative(0.1f)
                lineToRelative(2.0f, 2.0f)
                lineTo(18.0f, 11.0f)
                curveToRelative(0.0f, -3.07f, -1.63f, -5.64f, -4.5f, -6.32f)
                lineTo(13.5f, 4.0f)
                curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
                verticalLineToRelative(0.68f)
                curveToRelative(-0.24f, 0.06f, -0.47f, 0.15f, -0.69f, 0.23f)
                lineToRelative(1.64f, 1.64f)
                curveToRelative(0.18f, -0.02f, 0.36f, -0.05f, 0.55f, -0.05f)
                close()
                moveTo(5.41f, 3.35f)
                lineTo(4.0f, 4.76f)
                lineToRelative(2.81f, 2.81f)
                curveTo(6.29f, 8.57f, 6.0f, 9.74f, 6.0f, 11.0f)
                verticalLineToRelative(5.0f)
                lineToRelative(-2.0f, 2.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(14.24f)
                lineToRelative(1.74f, 1.74f)
                lineToRelative(1.41f, -1.41f)
                lineTo(5.41f, 3.35f)
                close()
                moveTo(16.0f, 17.0f)
                lineTo(8.0f, 17.0f)
                verticalLineToRelative(-6.0f)
                curveToRelative(0.0f, -0.68f, 0.12f, -1.32f, 0.34f, -1.9f)
                lineTo(16.0f, 16.76f)
                lineTo(16.0f, 17.0f)
                close()
            }
        }
        return _outlined_NotificationsOff!!
    }

private var _outlined_NotificationsOff: ImageVector? = null

public val Icons.Outlined.Science: ImageVector
    get() {
        if (_outlined_Science != null) {
            return _outlined_Science!!
        }
        _outlined_Science = materialIcon(name = "Outlined.Science") {
            materialPath {
                moveTo(13.0f, 11.33f)
                lineTo(18.0f, 18.0f)
                horizontalLineTo(6.0f)
                lineToRelative(5.0f, -6.67f)
                verticalLineTo(6.0f)
                horizontalLineToRelative(2.0f)
                moveTo(15.96f, 4.0f)
                horizontalLineTo(8.04f)
                curveTo(7.62f, 4.0f, 7.39f, 4.48f, 7.65f, 4.81f)
                lineTo(9.0f, 6.5f)
                verticalLineToRelative(4.17f)
                lineTo(3.2f, 18.4f)
                curveTo(2.71f, 19.06f, 3.18f, 20.0f, 4.0f, 20.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(0.82f, 0.0f, 1.29f, -0.94f, 0.8f, -1.6f)
                lineTo(15.0f, 10.67f)
                verticalLineTo(6.5f)
                lineToRelative(1.35f, -1.69f)
                curveTo(16.61f, 4.48f, 16.38f, 4.0f, 15.96f, 4.0f)
                lineTo(15.96f, 4.0f)
                close()
            }
        }
        return _outlined_Science!!
    }

private var _outlined_Science: ImageVector? = null

public val Icons.Outlined.Shield: ImageVector
    get() {
        if (_outlined_Shield != null) {
            return _outlined_Shield!!
        }
        _outlined_Shield = materialIcon(name = "Outlined.Shield") {
            materialPath {
                moveTo(12.0f, 2.0f)
                lineTo(4.0f, 5.0f)
                verticalLineToRelative(6.09f)
                curveToRelative(0.0f, 5.05f, 3.41f, 9.76f, 8.0f, 10.91f)
                curveToRelative(4.59f, -1.15f, 8.0f, -5.86f, 8.0f, -10.91f)
                verticalLineTo(5.0f)
                lineTo(12.0f, 2.0f)
                close()
                moveTo(18.0f, 11.09f)
                curveToRelative(0.0f, 4.0f, -2.55f, 7.7f, -6.0f, 8.83f)
                curveToRelative(-3.45f, -1.13f, -6.0f, -4.82f, -6.0f, -8.83f)
                verticalLineToRelative(-4.7f)
                lineToRelative(6.0f, -2.25f)
                lineToRelative(6.0f, 2.25f)
                verticalLineTo(11.09f)
                close()
            }
        }
        return _outlined_Shield!!
    }

private var _outlined_Shield: ImageVector? = null

public val Icons.Outlined.VerifiedUser: ImageVector
    get() {
        if (_outlined_VerifiedUser != null) {
            return _outlined_VerifiedUser!!
        }
        _outlined_VerifiedUser = materialIcon(name = "Outlined.VerifiedUser") {
            materialPath {
                moveTo(12.0f, 1.0f)
                lineTo(3.0f, 5.0f)
                verticalLineToRelative(6.0f)
                curveToRelative(0.0f, 5.55f, 3.84f, 10.74f, 9.0f, 12.0f)
                curveToRelative(5.16f, -1.26f, 9.0f, -6.45f, 9.0f, -12.0f)
                lineTo(21.0f, 5.0f)
                lineToRelative(-9.0f, -4.0f)
                close()
                moveTo(19.0f, 11.0f)
                curveToRelative(0.0f, 4.52f, -2.98f, 8.69f, -7.0f, 9.93f)
                curveToRelative(-4.02f, -1.24f, -7.0f, -5.41f, -7.0f, -9.93f)
                lineTo(5.0f, 6.3f)
                lineToRelative(7.0f, -3.11f)
                lineToRelative(7.0f, 3.11f)
                lineTo(19.0f, 11.0f)
                close()
                moveTo(7.41f, 11.59f)
                lineTo(6.0f, 13.0f)
                lineToRelative(4.0f, 4.0f)
                lineToRelative(8.0f, -8.0f)
                lineToRelative(-1.41f, -1.42f)
                lineTo(10.0f, 14.17f)
                close()
            }
        }
        return _outlined_VerifiedUser!!
    }

private var _outlined_VerifiedUser: ImageVector? = null
