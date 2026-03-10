package org.arend.toolWindow.errors

import com.intellij.icons.AllIcons.General.ZoomIn
import com.intellij.icons.AllIcons.General.ZoomOut
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import org.arend.ext.error.GeneralError
import org.arend.injection.InjectedArendEditor
import org.arend.toolWindow.errors.tree.ArendErrorTreeElement
import org.arend.util.ArendBundle

open class ArendMessagesViewEditor(project: Project, treeElement: ArendErrorTreeElement?, private val editorType: EditorType)
    : InjectedArendEditor(project, "Arend Messages", treeElement) {

    override val printOptionKind: PrintOptionKind
        get() = when (treeElement?.highestError?.level) {
            GeneralError.Level.GOAL -> PrintOptionKind.GOAL_PRINT_OPTIONS
            else -> PrintOptionKind.ERROR_PRINT_OPTIONS
        }

    init {
        setupActions()
    }

    fun update(newTreeElement: ArendErrorTreeElement) {
        val current = treeElement
        if (current?.errors == newTreeElement.errors) {
            newTreeElement.enrichNormalizationCache(current)
        }
        treeElement = newTreeElement
        updateErrorText()
    }

    fun updateActionGroup() {
        actionGroup.removeAll()
        setupActions()
    }

    fun clear() {
        clearText()
        actionGroup.removeAll()
        treeElement = null
    }

    fun setupActions() {
        when (editorType) {
            EditorType.GOAL -> {
                actionGroup.add(ActionManager.getInstance().getAction(ArendPinGoalAction.ID))
                actionGroup.add(ActionManager.getInstance().getAction(ArendClearGoalAction.ID))
                actionGroup.addSeparator()
                actionGroup.add(createPrintOptionsActionGroup())
                actionGroup.add(ArendShowImplicitGoalsAction())
                actionGroup.add(EnableWrapAction())
            }
            EditorType.ERROR -> {
                actionGroup.add(ActionManager.getInstance().getAction(ArendPinErrorAction.ID))
                actionGroup.addSeparator()
                actionGroup.add(createPrintOptionsActionGroup())
                actionGroup.add(ArendShowGoalsInErrorsPanelAction())
            }
            EditorType.INFO -> {
                actionGroup.add(object : DumbAwareAction(ArendBundle.message("arend.info.zoom.in.name"), ArendBundle.message("arend.info.zoom.in.description"), ZoomIn) {
                    override fun actionPerformed(e: AnActionEvent) {
                        (this@ArendMessagesViewEditor as? ArendInfoViewEditor)?.zoomIn()
                    }
                })
                actionGroup.add(object : DumbAwareAction(ArendBundle.message("arend.info.zoom.out.name"), ArendBundle.message("arend.info.zoom.out.description"), ZoomOut) {
                    override fun actionPerformed(e: AnActionEvent) {
                        (this@ArendMessagesViewEditor as? ArendInfoViewEditor)?.zoomOut()
                    }
                })
            }
        }
    }

    private fun createPrintOptionsActionGroup(): ArendPrintOptionsActionGroup {
        return ArendPrintOptionsActionGroup(project, printOptionKind) {
            when (printOptionKind) {
                PrintOptionKind.GOAL_PRINT_OPTIONS -> project.service<ArendMessagesService>().updateGoalText()
                else -> project.service<ArendMessagesService>().updateErrorText()
            }
        }
    }
}