package org.arend.documentation

import com.intellij.platform.backend.documentation.DocumentationLinkHandler
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.LinkResolveResult
import com.intellij.ui.JBColor.foreground

class ArendDocumentationLinkHandler : DocumentationLinkHandler {
    override fun resolveLink(target: DocumentationTarget, url: String): LinkResolveResult? {
        if (target !is ArendDocumentationTarget) return null
        val element = target.element
        
        if (url.contains(ACTION_PREFIX)) {
            ArendDocumentationGenerator.showInCefBrowser(url.substringAfter(ACTION_PREFIX), getHtmlRgbFormat(foreground().rgb))
            return null
        }
        
        val resolved = ArendDocumentationGenerator.getDocumentationElementForLink(url, element)
            ?: return null
        
        return LinkResolveResult.resolvedTarget(ArendDocumentationTarget(resolved, element))
    }
}
