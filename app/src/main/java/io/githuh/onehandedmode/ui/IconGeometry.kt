package io.githuh.onehandedmode.ui

import android.graphics.Matrix
import android.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath

/**
 * A vector icon flattened into plain Android paths.
 *
 * The surround buttons have to be drawn the instant their window appears, and a Compose
 * composition takes a frame or two to get there. Miuix ships its icons as [ImageVector], so the
 * geometry is walked once and handed to a normal Canvas instead of to a Compose tree.
 */
internal class IconGeometry(
    val paths: List<Path>,
    val viewportWidth: Float,
    val viewportHeight: Float,
)

internal fun ImageVector.toIconGeometry(): IconGeometry {
    val paths = ArrayList<Path>(4)
    collect(root, Matrix(), paths)
    return IconGeometry(
        paths = paths,
        viewportWidth = viewportWidth.coerceAtLeast(1f),
        viewportHeight = viewportHeight.coerceAtLeast(1f),
    )
}

private fun collect(group: VectorGroup, parent: Matrix, out: MutableList<Path>) {
    val matrix = Matrix(parent)
    // Group order matches the rendering of a nested <group>: pivot back, scale, rotate, then
    // pivot forward plus the translation.
    matrix.postTranslate(-group.pivotX, -group.pivotY)
    matrix.postScale(group.scaleX, group.scaleY)
    matrix.postRotate(group.rotation)
    matrix.postTranslate(group.pivotX + group.translationX, group.pivotY + group.translationY)
    for (node in group) {
        when (node) {
            is VectorPath -> {
                val path = buildPath(node.pathData)
                path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                    Path.FillType.EVEN_ODD
                } else {
                    Path.FillType.WINDING
                }
                path.transform(matrix)
                out.add(path)
            }
            is VectorGroup -> collect(node, matrix, out)
        }
    }
}

private fun buildPath(nodes: List<PathNode>): Path {
    val path = Path()
    // Absolute cursor, needed to resolve every relative node and the reflective control points.
    var x = 0f
    var y = 0f
    var startX = 0f
    var startY = 0f
    var lastCubicX = 0f
    var lastCubicY = 0f
    var lastQuadX = 0f
    var lastQuadY = 0f
    var cubic = false
    var quad = false
    for (node in nodes) {
        when (node) {
            is PathNode.MoveTo -> {
                x = node.x; y = node.y
                path.moveTo(x, y)
                startX = x; startY = y
                cubic = false; quad = false
            }
            is PathNode.RelativeMoveTo -> {
                x += node.dx; y += node.dy
                path.moveTo(x, y)
                startX = x; startY = y
                cubic = false; quad = false
            }
            is PathNode.LineTo -> {
                x = node.x; y = node.y
                path.lineTo(x, y)
                cubic = false; quad = false
            }
            is PathNode.RelativeLineTo -> {
                x += node.dx; y += node.dy
                path.lineTo(x, y)
                cubic = false; quad = false
            }
            is PathNode.HorizontalTo -> {
                x = node.x
                path.lineTo(x, y)
                cubic = false; quad = false
            }
            is PathNode.RelativeHorizontalTo -> {
                x += node.dx
                path.lineTo(x, y)
                cubic = false; quad = false
            }
            is PathNode.VerticalTo -> {
                y = node.y
                path.lineTo(x, y)
                cubic = false; quad = false
            }
            is PathNode.RelativeVerticalTo -> {
                y += node.dy
                path.lineTo(x, y)
                cubic = false; quad = false
            }
            is PathNode.CurveTo -> {
                path.cubicTo(node.x1, node.y1, node.x2, node.y2, node.x3, node.y3)
                lastCubicX = node.x2; lastCubicY = node.y2
                x = node.x3; y = node.y3
                cubic = true; quad = false
            }
            is PathNode.RelativeCurveTo -> {
                val x1 = x + node.dx1; val y1 = y + node.dy1
                val x2 = x + node.dx2; val y2 = y + node.dy2
                val x3 = x + node.dx3; val y3 = y + node.dy3
                path.cubicTo(x1, y1, x2, y2, x3, y3)
                lastCubicX = x2; lastCubicY = y2
                x = x3; y = y3
                cubic = true; quad = false
            }
            is PathNode.ReflectiveCurveTo -> {
                val x1 = if (cubic) 2f * x - lastCubicX else x
                val y1 = if (cubic) 2f * y - lastCubicY else y
                path.cubicTo(x1, y1, node.x1, node.y1, node.x2, node.y2)
                lastCubicX = node.x1; lastCubicY = node.y1
                x = node.x2; y = node.y2
                cubic = true; quad = false
            }
            is PathNode.RelativeReflectiveCurveTo -> {
                val x1 = if (cubic) 2f * x - lastCubicX else x
                val y1 = if (cubic) 2f * y - lastCubicY else y
                val c2x = x + node.dx1; val c2y = y + node.dy1
                val x3 = x + node.dx2; val y3 = y + node.dy2
                path.cubicTo(x1, y1, c2x, c2y, x3, y3)
                lastCubicX = c2x; lastCubicY = c2y
                x = x3; y = y3
                cubic = true; quad = false
            }
            is PathNode.QuadTo -> {
                path.quadTo(node.x1, node.y1, node.x2, node.y2)
                lastQuadX = node.x1; lastQuadY = node.y1
                x = node.x2; y = node.y2
                cubic = false; quad = true
            }
            is PathNode.RelativeQuadTo -> {
                val x1 = x + node.dx1; val y1 = y + node.dy1
                val x2 = x + node.dx2; val y2 = y + node.dy2
                path.quadTo(x1, y1, x2, y2)
                lastQuadX = x1; lastQuadY = y1
                x = x2; y = y2
                cubic = false; quad = true
            }
            is PathNode.ReflectiveQuadTo -> {
                val x1 = if (quad) 2f * x - lastQuadX else x
                val y1 = if (quad) 2f * y - lastQuadY else y
                path.quadTo(x1, y1, node.x, node.y)
                lastQuadX = x1; lastQuadY = y1
                x = node.x; y = node.y
                cubic = false; quad = true
            }
            is PathNode.RelativeReflectiveQuadTo -> {
                val x1 = if (quad) 2f * x - lastQuadX else x
                val y1 = if (quad) 2f * y - lastQuadY else y
                val x2 = x + node.dx; val y2 = y + node.dy
                path.quadTo(x1, y1, x2, y2)
                lastQuadX = x1; lastQuadY = y1
                x = x2; y = y2
                cubic = false; quad = true
            }
            is PathNode.ArcTo -> {
                // Elliptical arcs do not appear in the icon set this module draws; a straight
                // line keeps the outline closed instead of dropping the sub-path entirely.
                x = node.arcStartX; y = node.arcStartY
                path.lineTo(x, y)
                cubic = false; quad = false
            }
            is PathNode.RelativeArcTo -> {
                x += node.arcStartDx; y += node.arcStartDy
                path.lineTo(x, y)
                cubic = false; quad = false
            }
            PathNode.Close -> {
                path.close()
                x = startX; y = startY
                cubic = false; quad = false
            }
        }
    }
    return path
}
