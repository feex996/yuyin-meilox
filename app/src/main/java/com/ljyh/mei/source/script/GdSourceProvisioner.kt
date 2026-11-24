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
 * App 更新内置了更高版本的 GD 包时，已安装且未被用户移除的 GD 会通过 [ScriptPluginManager] 的安装事务
 * 原地升级（保留启用状态、配置、存储和封面缓存；不改回退顺序等音源设置）。
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
        val temporary = File(root, ".gd-bundle-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            extractBundle(context, temporary)
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

    /**
     * 已安装的 GD 版本低于 App 内置版本、且用户没有主动移除时，把内置包解到 [workDirectory] 下的新目录并返回，
     * 由 [ScriptPluginManager] 用现有安装事务替换；不需要升级时返回 null。
     */
    fun prepareUpgrade(context: Context, root: File, workDirectory: File): Pair<PluginManifest, File>? {
        val marker = File(root, MARKER)
        if (!marker.isFile || marker.readText().trim() == "removed") return null
        val installedDirectory = File(root, GD_ID)
        val installedManifest = File(installedDirectory, "manifest.json")
        if (!installedDirectory.isDirectory || !installedManifest.isFile || !File(installedDirectory, "index.js").isFile) return null
        val installed = runCatching { PluginManifest.parse(installedManifest.readText()) }.getOrNull() ?: return null
        if (installed.id != GD_ID) return null
        val installedVersion = com.ljyh.mei.source.update.SourceVersion.parse(installed.version) ?: return null
        val directory = File(workDirectory, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val bundled = extractBundle(context, directory)
            val bundledVersion = com.ljyh.mei.source.update.SourceVersion.parse(bundled.version)
            if (bundledVersion == null || bundledVersion <= installedVersion || bundled.providerId != installed.providerId) {
                directory.deleteRecursively()
                return null
            }
            return bundled to directory
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun extractBundle(context: Context, target: File): PluginManifest {
        val bytes = context.assets.open(ASSET).use { it.readBytes() }
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(!entry.isDirectory && entry.name in allowed && names.add(entry.name))
                val content = zip.readBytes()
                check(content.size <= 262_144)
                File(target, entry.name).writeBytes(content)
            }
        }
        check("manifest.json" in names && "index.js" in names)
        val manifest = PluginManifest.parse(File(target, "manifest.json").readText())
        check(manifest.id == GD_ID)
        return manifest
    }

    fun removed(root: File) = mark(root, "removed")

    private fun mark(root: File, value: String) {
        val temporary = File(root, "$MARKER.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(File(root, MARKER)))
    }
}
