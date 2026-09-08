package com.github.dtretyakov.monkeyc.lang

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet

/**
 * A parser definition that builds a flat tree: one node per token, under the file.
 *
 * There is no grammar here on purpose. Structure and meaning come from the SDK's language server,
 * which is the compiler's own front end, and a second grammar maintained in this plugin would drift
 * from it with every SDK release. What the platform still needs from a language — a lexer, and the
 * knowledge of which tokens are comments or strings — is exactly what this provides, and it is
 * enough for colouring, commenting, bracket matching and word selection.
 */
abstract class FlatParserDefinition(
    private val fileElementType: IFileElementType,
    private val comments: TokenSet,
    private val strings: TokenSet,
) : ParserDefinition {

    override fun createParser(project: Project?): PsiParser = PsiParser { root, builder ->
        val file = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        file.done(root)
        builder.treeBuilt
    }

    override fun getFileNodeType(): IFileElementType = fileElementType

    override fun getCommentTokens(): TokenSet = comments

    override fun getStringLiteralElements(): TokenSet = strings

    override fun createElement(node: ASTNode): PsiElement = ASTWrapperPsiElement(node)
}

class MonkeyCParserDefinition : FlatParserDefinition(
    MonkeyCTokens.FILE,
    MonkeyCTokens.COMMENTS,
    MonkeyCTokens.STRINGS,
) {
    override fun createLexer(project: Project?): Lexer = MonkeyCLexer()
    override fun createFile(viewProvider: FileViewProvider): PsiFile =
        SourceFile(viewProvider, MonkeyCLanguage, MonkeyCFileType)
}

class JungleParserDefinition : FlatParserDefinition(
    JungleTokens.FILE,
    JungleTokens.COMMENTS,
    JungleTokens.STRINGS,
) {
    override fun createLexer(project: Project?): Lexer = JungleLexer()
    override fun createFile(viewProvider: FileViewProvider): PsiFile =
        SourceFile(viewProvider, JungleLanguage, JungleFileType)
}

class MssParserDefinition : FlatParserDefinition(
    MssTokens.FILE,
    MssTokens.COMMENTS,
    MssTokens.STRINGS,
) {
    override fun createLexer(project: Project?): Lexer = MssLexer()
    override fun createFile(viewProvider: FileViewProvider): PsiFile =
        SourceFile(viewProvider, MssLanguage, MssFileType)
}

/** The PSI file for all three languages; it carries tokens and nothing else. */
class SourceFile(
    viewProvider: FileViewProvider,
    language: Language,
    private val type: FileType,
) : PsiFileBase(viewProvider, language) {
    override fun getFileType(): FileType = type
    override fun toString(): String = "${type.name} file"
}
