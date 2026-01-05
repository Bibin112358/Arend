package org.arend.psi.ext

import com.intellij.lang.ASTNode
import org.arend.psi.childOfType
import org.arend.psi.childOfTypeStrict
import org.arend.psi.getChildrenOfType
import org.arend.term.abs.AbstractExpressionVisitor


class ArendAtomFieldsAcc(node: ASTNode) : ArendExpr(node) {
    val atom: ArendAtom
        get() = childOfTypeStrict()

    val levels: List<ArendLevelExpr>?
        get() = childOfType<ArendLevelArgs>()?.levelExprList

    val fieldAccList: List<ArendFieldAcc>
        get() = getChildrenOfType()

    val ipName: ArendIPName?
        get() = childOfType()

    override fun <P, R> accept(visitor: AbstractExpressionVisitor<in P, out R>, params: P?): R {
        val levels = levels
        val fieldAccs = fieldAccList
        val ipName = ipName
        return if (levels == null && fieldAccs.isEmpty() && ipName == null) {
            atom.accept(visitor, params)
        } else {
            visitor.visitFieldAccs(this, atom, levels, fieldAccs, ipName, ipName?.referenceName, ipName?.fixity, params)
        }
    }
}