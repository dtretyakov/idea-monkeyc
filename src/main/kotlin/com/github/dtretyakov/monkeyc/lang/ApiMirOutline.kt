package com.github.dtretyakov.monkeyc.lang

import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Declaration
import com.github.dtretyakov.monkeyc.sdk.ApiMirIndex.Kind
import com.github.dtretyakov.monkeyc.sdk.ApiMirService
import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.ASTNode
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import javax.swing.Icon

/**
 * Making `api.mir` a thing you can read.
 *
 * It is one file of twenty-seven thousand lines, and opened as text that is what it is. The index
 * already knows every declaration in it and where each one's body ends, so the same data gives the
 * file an outline and a set of fold regions — and the SDK in the project tree stops being a wall
 * of text and becomes the browsable API it describes.
 *
 * Only for this language: the project's own Monkey C gets both from the language server through
 * LSP4IJ, which knows far more about it than an indentation scanner ever could.
 */
class ApiMirStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        if (psiFile.fileType != ApiMirFileType) return null
        val index = ApiMirService.getInstance().index(psiFile.project) ?: return null

        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: com.intellij.openapi.editor.Editor?): StructureViewModel =
                ApiMirStructureViewModel(psiFile, index)
        }
    }
}

private class ApiMirStructureViewModel(file: PsiFile, index: ApiMirIndex) : StructureViewModelBase(
    file,
    ApiMirRoot(file, index),
),
    StructureViewModel.ElementInfoProvider {

    init {
        withSorters(Sorter.ALPHA_SORTER)
    }

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        element is ApiMirNode && element.declaration.kind !in HOLDERS
}

/** The file itself: the top-level modules hang off it. */
private class ApiMirRoot(private val file: PsiFile, private val index: ApiMirIndex) : StructureViewTreeElement {

    override fun getValue(): Any = file

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = file.name
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean): Icon = AllIcons.Nodes.Package
    }

    /**
     * `Toybox` is skipped as a level rather than shown.
     *
     * Everything in the file is inside it, so a tree whose only root is `Toybox` costs the reader
     * a click and tells them nothing. Anything that somehow sits outside it is still listed, since
     * silently dropping declarations is worse than an odd-looking row.
     */
    override fun getChildren(): Array<TreeElement> =
        (index.membersOf(ROOT_MODULE) + index.membersOf("").filterNot { it.qualifiedName == ROOT_MODULE })
            .map { ApiMirNode(file, index, it) }
            .toTypedArray()

    override fun navigate(requestFocus: Boolean) = Unit
    override fun canNavigate(): Boolean = false
    override fun canNavigateToSource(): Boolean = false
}

/** One declaration; a module or a class also carries what is inside it. */
private class ApiMirNode(
    private val file: PsiFile,
    private val index: ApiMirIndex,
    val declaration: Declaration,
) : StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = declaration

    override fun getAlphaSortKey(): String = declaration.simpleName

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = declaration.simpleName
        override fun getLocationString(): String? = null
        override fun getIcon(unused: Boolean): Icon = iconFor(declaration.kind)
    }

    override fun getChildren(): Array<TreeElement> =
        if (declaration.kind in HOLDERS) {
            index.membersOf(declaration.qualifiedName).map { ApiMirNode(file, index, it) }.toTypedArray()
        } else {
            TreeElement.EMPTY_ARRAY
        }

    override fun navigate(requestFocus: Boolean) =
        OpenFileDescriptor(file.project, file.virtualFile, declaration.offset).navigate(requestFocus)

    override fun canNavigate(): Boolean = file.virtualFile != null

    override fun canNavigateToSource(): Boolean = canNavigate()
}

/** A fold region for every module and class body, so the file can be read as an outline. */
class ApiMirFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (root.containingFile?.fileType != ApiMirFileType) return FoldingDescriptor.EMPTY_ARRAY
        val index = ApiMirService.getInstance().index(root.project) ?: return FoldingDescriptor.EMPTY_ARRAY

        val node = root.node ?: return FoldingDescriptor.EMPTY_ARRAY
        val length = document.textLength

        // The index was built from the file on disk. If the document in front of the user is
        // shorter, they are looking at something else and folding it would be nonsense.
        fun range(from: Int, to: Int): TextRange? = if (to in (from + 1)..length) TextRange(from, to) else null

        val bodies = index.all()
            .filter { it.kind in HOLDERS }
            .mapNotNull { declaration ->
                range(declaration.offset + declaration.simpleName.length, declaration.endOffset)
                    // No folding group: a group ties regions that fold together, and each of
                    // these is its own.
                    ?.let { FoldingDescriptor(node, it, null, " { … }") }
            }

        // The bookkeeping line above each declaration, out of the way from the start. It points
        // at Garmin's own `.mb` sources, which the SDK does not ship, so there is nothing behind
        // it to go and read.
        val annotations = index.all()
            .mapNotNull { it.annotation }
            .distinct()
            .mapNotNull { at ->
                range(at.first, at.last)?.let {
                    FoldingDescriptor(node, it, null, "[…]", true, emptySet())
                }
            }

        return (bodies + annotations).toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = " { … }"

    /**
     * Left expanded, and Collapse All is one keystroke away.
     *
     * Every region here hangs off the same root node, so this is one answer for all of them: it
     * cannot collapse the modules and leave a method the user has just arrived at open.
     */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    override fun isDumbAware(): Boolean = true
}

private const val ROOT_MODULE = "Toybox"

private val HOLDERS = setOf(Kind.MODULE, Kind.CLASS)

private fun iconFor(kind: Kind): Icon = when (kind) {
    Kind.MODULE -> AllIcons.Nodes.Module
    Kind.CLASS -> AllIcons.Nodes.Class
    Kind.FUNCTION -> AllIcons.Nodes.Method
    Kind.VARIABLE -> AllIcons.Nodes.Field
    Kind.CONSTANT -> AllIcons.Nodes.Constant
    Kind.TYPE -> AllIcons.Nodes.Interface
}
