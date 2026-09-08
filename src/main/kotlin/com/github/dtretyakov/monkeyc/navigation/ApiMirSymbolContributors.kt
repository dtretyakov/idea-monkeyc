package com.github.dtretyakov.monkeyc.navigation

import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Kind
import com.github.dtretyakov.monkeyc.sdk.ApiMirService
import com.intellij.icons.AllIcons
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import javax.swing.Icon

/**
 * Ctrl+N and Ctrl+Alt+Shift+N reaching into the Toybox API.
 *
 * LSP4IJ already contributes the project's own symbols, over `workspace/symbol`; the server
 * answers that request for the workspace and nothing else, so the API — which is most of what a
 * developer is looking for — has never been findable by name at all.
 */
abstract class ApiMirContributor : ChooseByNameContributorEx {

    protected abstract fun accepts(kind: Kind): Boolean

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
        val index = ApiMirService.getInstance().index() ?: return
        index.all().forEach { if (accepts(it.kind) && !processor.process(it.simpleName)) return }
    }

    override fun processElementsWithName(
        name: String,
        processor: Processor<in NavigationItem>,
        parameters: FindSymbolParameters,
    ) {
        val index = ApiMirService.getInstance().index() ?: return
        val file = ApiMirService.getInstance().file() ?: return

        index.all().forEach { declaration ->
            if (declaration.simpleName != name || !accepts(declaration.kind)) return@forEach
            if (!processor.process(ApiMirSymbol(parameters.project, declaration, file))) return
        }
    }
}

/** Go to Symbol: everything the API declares. */
class ApiMirGotoSymbolContributor : ApiMirContributor() {
    override fun accepts(kind: Kind): Boolean = true
}

/**
 * Go to Class: the things that hold other things.
 *
 * Modules count. Monkey C has no separate namespace concept, and `Toybox.WatchUi` is what a
 * developer types when they want to see what is in it.
 */
class ApiMirGotoClassContributor : ApiMirContributor(), GotoClassContributor {

    override fun accepts(kind: Kind): Boolean = kind == Kind.CLASS || kind == Kind.MODULE

    override fun getQualifiedName(item: NavigationItem): String? = (item as? ApiMirSymbol)?.qualifiedName

    override fun getQualifiedNameSeparator(): String = "."
}

/**
 * One entry in the chooser.
 *
 * A plain `NavigationItem` rather than a PSI element: the list wants a name, a place and a way to
 * open it, and nothing here needs the API file parsed just to be listed.
 */
class ApiMirSymbol(
    private val project: Project,
    private val declaration: ApiMirIndex.Declaration,
    private val file: VirtualFile,
) : NavigationItem {

    val qualifiedName: String get() = declaration.qualifiedName

    override fun getName(): String = declaration.simpleName

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = declaration.simpleName
        override fun getLocationString(): String = declaration.container
        override fun getIcon(unused: Boolean): Icon = iconFor(declaration.kind)
    }

    override fun navigate(requestFocus: Boolean) =
        OpenFileDescriptor(project, file, declaration.offset).navigate(requestFocus)

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    private fun iconFor(kind: Kind): Icon = when (kind) {
        Kind.MODULE -> AllIcons.Nodes.Module
        Kind.CLASS -> AllIcons.Nodes.Class
        Kind.FUNCTION -> AllIcons.Nodes.Method
        Kind.VARIABLE -> AllIcons.Nodes.Field
        Kind.CONSTANT -> AllIcons.Nodes.Constant
        Kind.TYPE -> AllIcons.Nodes.Interface
    }
}
