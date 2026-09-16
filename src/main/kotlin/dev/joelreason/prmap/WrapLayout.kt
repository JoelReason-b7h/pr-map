package dev.joelreason.prmap

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A FlowLayout that reports the height it actually needs once its rows have wrapped.
 *
 * Plain FlowLayout always reports the height of a single row, so in a narrow tool window
 * the wrapped rows fall outside the panel and the controls look as though they have gone
 * missing. Measuring the wrapped rows makes the toolbar grow taller instead.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 6, vgap: Int = 4) : FlowLayout(align, hgap, vgap) {

  override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

  override fun minimumLayoutSize(target: Container): Dimension =
    layoutSize(target, false).also { it.width -= hgap + 1 }

  private fun layoutSize(target: Container, preferred: Boolean): Dimension {
    synchronized(target.treeLock) {
      var targetWidth = target.size.width
      if (targetWidth == 0) targetWidth = Integer.MAX_VALUE

      val insets = target.insets
      val maxWidth = targetWidth - (insets.left + insets.right + hgap * 2)
      val dimension = Dimension(0, 0)
      var rowWidth = 0
      var rowHeight = 0

      fun closeRow() {
        dimension.width = maxOf(dimension.width, rowWidth)
        if (dimension.height > 0) dimension.height += vgap
        dimension.height += rowHeight
        rowWidth = 0
        rowHeight = 0
      }

      for (index in 0 until target.componentCount) {
        val member = target.getComponent(index)
        if (!member.isVisible) continue
        val size = if (preferred) member.preferredSize else member.minimumSize
        if (rowWidth + size.width > maxWidth && rowWidth > 0) closeRow()
        if (rowWidth != 0) rowWidth += hgap
        rowWidth += size.width
        rowHeight = maxOf(rowHeight, size.height)
      }
      closeRow()

      dimension.width += insets.left + insets.right + hgap * 2
      dimension.height += insets.top + insets.bottom + vgap * 2

      // Inside a scroll pane the container reports its full width while laying out, which
      // would collapse the wrap back to one row.
      val scroller = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target)
      if (scroller != null && target.isValid) dimension.width -= hgap + 2
      return dimension
    }
  }
}
