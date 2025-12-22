package org.arend.navigation

import com.intellij.ide.projectView.PresentationData
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiElement
import org.arend.psi.ArendFile
import org.arend.psi.ext.ArendClassFieldBase
import org.arend.psi.ext.ArendClassImplement
import org.arend.psi.ext.ArendCompositeElement
import org.arend.psi.ext.ArendConstructor
import org.arend.psi.ext.ArendGroup
import org.arend.psi.ext.PsiReferable

fun getPresentation(psi: ArendCompositeElement): ItemPresentation {
    val location = run {
        val module = psi.containingFile
        "(in ${(module as? ArendFile)?.fullName ?: module.name})"
    }

    val name = presentableName(psi)
    val icon = runReadAction {
        if (psi is ArendGroup || psi is ArendClassFieldBase<*> || psi is ArendClassImplement || psi is ArendConstructor) {
            psi.getIcon(0)
        } else {
            null
        }
    }

    return PresentationData(name, location, icon, null)
}

fun getPresentationForStructure(psi: ArendCompositeElement): ItemPresentation =
        PresentationData(presentableName(psi), null, psi.getIcon(0), null)

private fun presentableName(psi: PsiElement): String? = when (psi) {
    is ArendFile -> psi.fullName
    is PsiReferable -> psi.name
    else -> null
}
