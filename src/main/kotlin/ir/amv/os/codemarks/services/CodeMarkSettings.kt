package ir.amv.os.codemarks.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.concurrent.ConcurrentHashMap
import com.intellij.openapi.components.service

@State(
    name = "CodeMarkSettings",
    storages = [Storage("codemarks.xml")]
)
class CodeMarkSettings : PersistentStateComponent<CodeMarkSettings> {
    var fileTypePatterns: MutableSet<String> = mutableSetOf("*")
    var lastScanState: MutableMap<String, Long> = ConcurrentHashMap()
    
    override fun getState(): CodeMarkSettings = this
    
    override fun loadState(state: CodeMarkSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
    
    companion object {
        private val DEFAULT_FILE_TYPE_PATTERNS = listOf(
            "/**/src/*/java/**/*.java",
            "/**/src/*/kotlin/**/*.kt",
            "*.xml",
            "*.{yaml,yml}",
            "*.{js,jsx,ts,tsx}",  // JavaScript and TypeScript
            "*.{py,pyi}",         // Python
            "*.{go}",             // Go
            "*.{rb}",             // Ruby
            "*.{php}",            // PHP
            "*.{c,cpp,h,hpp}",    // C/C++
            "*.{cs}",             // C#
            "*.{swift}",          // Swift
            "*.{rs}",             // Rust
            "*.{scala}",          // Scala
            "*.{groovy}",         // Groovy
            "*.{gradle}",         // Gradle
            "*.{properties}",     // Properties files
            "*.{ini,cfg,conf}",   // Configuration files
            "*.{md,markdown}",    // Markdown
            "*.{sql}",            // SQL
            "*.{sh,bash}",        // Shell scripts
            "*.{ps1}",            // PowerShell
            "*.{bat,cmd}",        // Batch files
            "*.{json}",           // JSON
            "*.{html,htm}",       // HTML
            "*.{css,scss,less}",  // CSS and preprocessors
            "*.{vue}",            // Vue.js
            "*.{svelte}",         // Svelte
            "*.{astro}"           // Astro
        )
        
        fun getInstance(project: Project): CodeMarkSettings = project.service<CodeMarkSettings>()
    }
} 