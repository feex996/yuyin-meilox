package com.ljyh.mei.source.script

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 余音：首次启动时额外预装 QQ 音乐 音源（只新增，不替换、不改默认源）。
 * 用户移除后记为 removed，不会自动装回。
 * App 更新内置了更高版本的 QQ 包时，已安装且未被用户移除的 QQ 会通过 [ScriptPluginManager] 的安装事务
 * 原地升级（保留启用状态、配置、存储和封面缓存；不改回退顺序等音源设置）。
 *
 * 与 GD 预装不同：QQ 音源不自动改写回退顺序/偏好设置——QQ 源需要用户登录账号才能发挥完整能力
 * （VIP 歌曲等），登录入口在音源的账号页；未登录时由用户自行决定是否启用自动切换。
 */
internal object QqSourceProvisioner {
    const val QQ_ID = "org.meilox.qqmusic"
    private const val MARKER = ".qq-default-v1"
    private const val ASSET = "music-sources/qq-music.meisource"
    private val allowed = setOf("manifest.json", "index.js", "LICENSE", "NOTICE")

    suspend fun ensure(context: Context, root: File) {
        val marker = File(root, MARKER)
        if (marker.exists()) return
        val destination = File(root, QQ_ID)
        if (destination.exists()) { mark(root, "external"); return }
        val temporary = File(root, ".qq-bundle-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            extractBundle(context, temporary)
            check(temporary.renameTo(destination))
            mark(root, "installed")
        } finally { temporary.deleteRecursively() }
    }

    /**
     * 已安装的 QQ 版本低于 App 内置版本、且用户没有主动移除时，把内置包解到 [workDirectory] 下的新目录并返回，
     * 由 [ScriptPluginManager] 用现有安装事务替换；不需要升级时返回 null。
     */
    fun prepareUpgrade(context: Context, root: File, workDirectory: File): Pair<PluginManifest, File>? {
        val marker = File(root, MARKER)
        if (!marker.isFile || marker.readText().trim() == "removed") return null
        val installedDirectory = File(root, QQ_ID)
        val installedManifest = File(installedDirectory, "manifest.json")
        if (!installedDirectory.isDirectory || !installedManifest.isFile || !File(installedDirectory, "index.js").isFile) return null
        val installed = runCatching { PluginManifest.parse(installedManifest.readText()) }.getOrNull() ?: return null
        if (installed.id != QQ_ID) return null
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
        check(manifest.id == QQ_ID)
        return manifest
    }

    fun removed(root: File) = mark(root, "removed")

    private fun mark(root: File, value: String) {
        val temporary = File(root, "$MARKER.tmp")
        temporary.writeText(value)
        check(temporary.renameTo(File(root, MARKER)))
    }
}
