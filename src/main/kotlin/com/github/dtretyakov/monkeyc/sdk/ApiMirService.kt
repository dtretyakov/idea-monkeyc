package com.github.dtretyakov.monkeyc.sdk

import com.github.dtretyakov.monkeyc.project.ConnectIqSdkService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * The parsed Toybox API, kept for as long as the SDK's `api.mir` stays the same.
 *
 * Parsing the whole file costs about seventy milliseconds, so it is done on demand rather than at
 * startup, and then held: navigation, Go to Symbol and the file's own outline all ask for it, and
 * the first of them to run pays. The stamp is the file's timestamp and size together, which moves
 * when the SDK Manager installs a new SDK — the case this has to notice, because the API changes
 * with it.
 */
@Service(Service.Level.APP)
class ApiMirService {

    private class Snapshot(val path: Path?, val stamp: Long, val index: ApiMirIndex?)

    @Volatile
    private var snapshot: Snapshot? = null

    /** The index, or null when no SDK is installed or its `api.mir` cannot be read. */
    fun index(): ApiMirIndex? {
        val path = path() ?: return null
        val stamp = ApiMirIndex.stampOf(path)

        snapshot?.let { if (it.path == path && it.stamp == stamp) return it.index }
        return ApiMirIndex.at(path).also { snapshot = Snapshot(path, stamp, it) }
    }

    fun path(): Path? = ConnectIqSdkService.getInstance().sdk?.let { ApiMirIndex.fileIn(it) }

    /**
     * The file in the VFS, if the platform has seen it.
     *
     * Deliberately without a refresh: every caller is inside a read action, where refreshing is
     * forbidden. The library provider brings the SDK into the VFS when the project opens, and
     * without that this returns null and navigation quietly declines rather than misbehaving.
     */
    fun file(): VirtualFile? = path()?.let { LocalFileSystem.getInstance().findFileByNioFile(it) }

    companion object {
        fun getInstance(): ApiMirService = ApplicationManager.getApplication().service()
    }
}
