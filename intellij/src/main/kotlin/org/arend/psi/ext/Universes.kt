package org.arend.psi.ext

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.util.elementType
import org.arend.psi.*
import org.arend.psi.ArendElementTypes.*
import org.arend.term.abs.Abstract
import org.arend.term.abs.AbstractExpressionVisitor


private fun <P, R> acceptSet(data: ArendCompositeElement, setElem: PsiElement, pLevel: Abstract.LevelExpression?, visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R =
    visitor.visitUniverse(data, setElem.text.substring("\\Set".length).toIntOrNull(), 0, pLevel, params)

private fun <P, R> acceptUniverse(data: ArendCompositeElement, universeElem: PsiElement, pLevel: Abstract.LevelExpression?, visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R =
    visitor.visitUniverse(data, universeElem.text.substring("\\Type".length).toIntOrNull(), null, pLevel, params)

private fun <P, R> acceptCatUniverse(data: ArendCompositeElement, catElem: PsiElement, pLevel: Abstract.LevelExpression?, visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R =
    visitor.visitCatUniverse(data, catElem.text.substring("\\Set".length).toIntOrNull(), pLevel, params)

private fun <P, R> acceptTruncated(data: ArendCompositeElement, truncatedElem: PsiElement, pLevel: Abstract.LevelExpression?, visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R {
    val uniText = truncatedElem.text
    val index = uniText.indexOf('T')
    val hLevelNum = if (index > 0 && uniText[0] == '\\') uniText.substring(1, index - 1).toIntOrNull() else null
    val pLevelNum = if (hLevelNum != null) uniText.substring(index + "Type".length).toIntOrNull() else null
    return visitor.visitUniverse(data, pLevelNum, hLevelNum, pLevel, params)
}


class ArendSetUniverseAppExpr(node: ASTNode) : ArendAppExpr(node) {
    val maybeAtomLevelExpr: ArendMaybeAtomLevelExpr?
        get() = childOfType()

    override fun <P, R> accept(visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R =
        acceptSet(this, notNullChild(firstRelevantChild), maybeAtomLevelExpr?.atomLevelExpr, visitor, params)
}

class ArendCatUniverseAppExpr(node: ASTNode) : ArendAppExpr(node) {
    val maybeAtomLevelExpr: ArendMaybeAtomLevelExpr?
        get() = childOfType()

    override fun <P, R> accept(visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R =
        acceptCatUniverse(this, notNullChild(firstRelevantChild), maybeAtomLevelExpr?.atomLevelExpr, visitor, params)
}

class ArendTruncatedUniverseAppExpr(node: ASTNode) : ArendAppExpr(node) {
    val maybeAtomLevelExpr: ArendMaybeAtomLevelExpr?
        get() = childOfType()

    override fun <P, R> accept(visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R =
        acceptTruncated(this, notNullChild(firstRelevantChild), maybeAtomLevelExpr?.atomLevelExpr, visitor, params)
}

class ArendUniverseAppExpr(node: ASTNode) : ArendAppExpr(node) {
    override fun <P, R> accept(visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R =
        acceptUniverse(this, notNullChild(firstRelevantChild), childOfType<ArendMaybeAtomLevelExpr>()?.atomLevelExpr, visitor, params)
}

class ArendUniverseAtom(node: ASTNode) : ArendExpr(node), ArendArgument {
    override fun isExplicit(): Boolean = true

    override fun isVariable() = false

    override fun getExpression(): ArendExpr = this

    override fun <P, R> accept(visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R {
        val child = firstRelevantChild
        return when (child.elementType) {
            SET -> acceptSet(this, child!!, null, visitor, params)
            CAT_UNIVERSE -> acceptCatUniverse(this, child!!, null, visitor, params)
            UNIVERSE -> acceptUniverse(this, child!!, null, visitor, params)
            TRUNCATED_UNIVERSE -> acceptTruncated(this, child!!, null, visitor, params)
            else -> error("Incorrect expression: universe")
        }
    }
}
