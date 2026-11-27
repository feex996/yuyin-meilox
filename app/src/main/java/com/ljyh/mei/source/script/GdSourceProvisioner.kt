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
    private const val SEARCH_MARKER = ".gd-search-follow-v1"
    private const val FALLBACK_MARKER = ".fallback-chain-v2"
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
     * 一次性迁移（1.2.0 起）：旧版本里首页「切换音源」只改浏览源，搜索仍走默认的网易云。
     * 若用户之前已在首页选了 GD音乐台、而搜索源还不是 GD，则把搜索源也设为 GD。
     * 不改默认值、不改回退顺序、不碰 autoFallback；没选 GD 的用户不受影响。
     */
    suspend fun syncSearchWithBrowse(context: Context, root: File) {
        val marker = File(root, SEARCH_MARKER)
        if (marker.exists()) return
        if (File(root, GD_ID).isDirectory) {
            com.ljyh.mei.source.core.SourcePreferences(context.dataStore).update {
                if (it.preferredBrowseProvider == GD_ID && it.preferredSearchProvider != GD_ID)
                    it.copy(preferredSearchProvider = GD_ID) else it
            }
        }
        marker.writeText("done")
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

    /**
     * 一次性迁移（余音 yuyin-16 起）：保证播放回退链覆盖内置的网易云、GD音乐台、QQ 音乐，
     * 原始音源放不了时依次换源匹配同一首，而不是停在原地。
     * - 只在已有回退顺序末尾追加缺失的项（已安装且未被用户移除的源），不改变已有顺序；
     * - 不改默认浏览/搜索音源，也不动「优先原始音源」开关；
     * - 打开自动切换：这正是 GD 首次预装时的默认值，播放失败的场景需要它。
     */
    suspend fun ensureFallbackChain(context: Context, root: File) {
        val marker = File(root, FALLBACK_MARKER)
        if (marker.exists()) return
        val netease = File(root, BundledSourceProvisioner.NETEASE_ID).isDirectory
        val gd = File(root, GD_ID).isDirectory
        val qq = File(root, QqSourceProvisioner.QQ_ID).isDirectory
        val additions = listOfNotNull(
            "script:${BundledSourceProvisioner.NETEASE_ID}:default".takeIf { netease },
            "script:$GD_ID:default".takeIf { gd },
            "script:${QqSourceProvisioner.QQ_ID}:default".takeIf { qq },
        )
        if (additions.isNotEmpty()) {
            com.ljyh.mei.source.core.SourcePreferences(context.dataStore).update {
                it.copy(autoFallbackEnabled = true, fallbackOrder = (it.fallbackOrder + additions).distinct())
            }
        }
        marker.writeText("done")
    }

    fun removed(root: File) = mark(root, "removed")

    private fun mark(root: File, value: String) {
        val temporary = File(root, "$MARKER.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(File(root, MARKER)))
    }
}
