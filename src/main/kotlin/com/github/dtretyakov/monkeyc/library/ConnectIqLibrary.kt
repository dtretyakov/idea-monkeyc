package com.github.dtretyakov.monkeyc.library

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.NavigatableWithText
import javax.swing.Icon

/**
 * One entry under External Libraries.
 *
 * Two details here are load-bearing and neither of them is obvious from the type:
 *
 * `ItemPresentation` is not optional. `ExternalLibrariesNode.getChildren` checks for it and, when
 * it is missing, drops the library with nothing but a `LOG.warn` — the node simply never appears,
 * which is indistinguishable from the provider never running.
 *
 * The roots go in [getSourceRoots] rather than `getBinaryRoots`. Source roots are registered as
 * `WorkspaceFileKind.EXTERNAL_SOURCE`, which `ProjectFileIndexFacade.isInProjectScope` accepts, so
 * the files turn up in Go to File without the user having to switch to "All Places". Binary roots
 * are `EXTERNAL`, which that predicate rejects.
 *
 * Nothing is needed to make the files read-only: they are outside every content root, so
 * `NonProjectFileWritingAccessProvider` shows the platform's own banner on the first keystroke.
 */
class ConnectIqLibrary(
    private val id: String,
    private val name: String,
    private val location: String?,
    private val icon: Icon,
    private val roots: List<VirtualFile>,
    private val onNavigate: () -> Unit,
    private val navigateText: String,
) : SyntheticLibrary(id, null), ItemPresentation, NavigatableWithText {

    override fun getSourceRoots(): Collection<VirtualFile> = roots

    override fun getPresentableText(): String = name

    override fun getLocationString(): String? = location

    override fun getIcon(unused: Boolean): Icon = icon

    override fun canNavigate(): Boolean = true

    override fun navigate(requestFocus: Boolean) = onNavigate()

    override fun getNavigateActionText(focusEditor: Boolean): String = navigateText

    // Identity is the roots, not the object: the platform compares the libraries it was given last
    // time with the ones it is given now to decide whether to re-index, and a library that is
    // never equal to itself re-indexes the SDK on every model change.
    override fun equals(other: Any?): Boolean =
        other is ConnectIqLibrary && other.id == id && other.name == name &&
            other.location == location && other.roots == roots

    override fun hashCode(): Int = listOf(id, name, location, roots).hashCode()
}
