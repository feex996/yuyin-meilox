package com.ljyh.mei.source.script

import android.content.Context
import com.ljyh.mei.utils.dataStore
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 余音：首次启动时额外预装 GD音乐台 音源（只新增，不替换、不改默认源）。
 * 用户移除后记为 removed，不会自动装回。
 */
internal object GdSourceProvisioner {
    const val GD_ID = "xyz.gdstudio.music"
    private const val MARKER = ".gd-default-v1"
    private const val ASSET = "music-sources/gd-music.meisource"
    private val allowed = setOf("manifest.json", "index.js", "LICENSE", "NOTICE")

    suspend fun ensure(context: Context, root: File) {
        val marker = File(root, MARKER)
        if (marker.exists()) return
        val destination = File(root, GD_ID)
        if (destination.exists()) { mark(root, "external"); return }
        val bytes = context.assets.open(ASSET).use { it.readBytes() }
        val temporary = File(root, ".gd-bundle-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val names = mutableSetOf<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    check(!entry.isDirectory && entry.name in allowed && names.add(entry.name))
                    val content = zip.readBytes()
                    check(content.size <= 262_144)
                    File(temporary, entry.name).writeBytes(content)
                }
            }
            check("manifest.json" in names && "index.js" in names)
            val manifest = PluginManifest.parse(File(temporary, "manifest.json").readText())
            check(manifest.id == GD_ID)
            check(temporary.renameTo(destination))
            // 打开「自动切换播放音源」并保持「优先原始音源」：原有音源（网易云）在前，GD 在后，互为兜底。
            val netease = "script:${BundledSourceProvisioner.NETEASE_ID}:default"
            val gd = "script:$GD_ID:default"
            val hasNetease = File(root, BundledSourceProvisioner.NETEASE_ID).isDirectory
            com.ljyh.mei.source.core.SourcePreferences(context.dataStore).update {
                val order = (it.fallbackOrder + (if (hasNetease) listOf(netease) else emptyList()) + gd).distinct()
                it.copy(autoFallbackEnabled = true, preferOriginalProvider = true, fallbackOrder = order)
            }
            mark(root, "installed")
        } finally { temporary.deleteRecursively() }
    }

    fun removed(root: File) = mark(root, "removed")

    private fun mark(root: File, value: String) {
        val temporary = File(root, "$MARKER.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(File(root, MARKER)))
    }
}
