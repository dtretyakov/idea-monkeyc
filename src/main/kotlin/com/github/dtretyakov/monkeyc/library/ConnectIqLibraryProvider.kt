package com.github.dtretyakov.monkeyc.library

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile

/**
 * Puts the Connect IQ SDK and the project's barrels under External Libraries.
 *
 * Everything a Monkey C project depends on lives outside it: the API is in an SDK the SDK Manager
 * unpacks somewhere in the user's Application Support, and a barrel is a zip built by another
 * project. Until now none of it was visible in the IDE at all — no node in the tree, nothing found
 * by Go to File, nowhere for go-to-definition to land.
 *
 * This is the same mechanism the bundled Node.js plugin uses for Node's core sources, which is the
 * closest precedent the IDE ships: a provider, a `SyntheticLibrary` that is also an
 * `ItemPresentation`, and `AdditionalLibraryRootsListener` when the toolchain is switched.
 */
class ConnectIqLibraryProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> =
        ConnectIqLibraries.getInstance(project).libraries()

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> =
        ConnectIqLibraries.getInstance(project).rootsToWatch()
}
