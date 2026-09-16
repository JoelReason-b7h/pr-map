package dev.joelreason.prmap

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class MapMeta(
  val pr: Int? = null,
  val title: String? = null,
  val url: String? = null,
  val base: String? = null,
  val head: String? = null,
  /** The commit the change set diverged from, which is what a file diffs against. */
  @SerializedName("merge_base") val mergeBase: String? = null,
  /** The head branch of a pull request, which reads better than its sha. */
  val branch: String? = null,
)

data class MapNode(
  val id: String,
  val name: String,
  val path: String,
  val role: String,
  val changed: Boolean,
  val status: String?,
  val bridge: String?,
  val additions: Int,
  val deletions: Int,
  val line: Int = 1,
  @SerializedName("fan_in") val fanIn: Int = 0,
)

data class MapEdge(val from: String, val to: String, val kind: String)

data class UnlinkedFile(
  val path: String,
  val additions: Int,
  val deletions: Int,
  val status: String?,
  val related: List<String> = emptyList(),
)

data class Topology(
  val meta: MapMeta,
  val nodes: List<MapNode>,
  val edges: List<MapEdge>,
  val unlinked: List<UnlinkedFile> = emptyList(),
) {
  companion object {
    fun parse(json: String): Topology = Gson().fromJson(json, Topology::class.java)
  }
}
