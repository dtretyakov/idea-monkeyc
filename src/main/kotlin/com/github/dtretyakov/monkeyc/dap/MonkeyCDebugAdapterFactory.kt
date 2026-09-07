package com.github.dtretyakov.monkeyc.dap

import com.github.dtretyakov.monkeyc.lang.MonkeyCFileType
import com.github.dtretyakov.monkeyc.run.MonkeyCRunConfiguration
import com.github.dtretyakov.monkeyc.run.MonkeyCSettingsEditor
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptor
import com.redhat.devtools.lsp4ij.dap.descriptors.DebugAdapterDescriptorFactory

class MonkeyCDebugAdapterFactory : DebugAdapterDescriptorFactory() {

    override fun createDebugAdapterDescriptor(
        options: RunConfigurationOptions,
        environment: ExecutionEnvironment,
    ): DebugAdapterDescriptor = MonkeyCDebugAdapterDescriptor(options, environment, serverDefinition)

    override fun isDebuggableFile(file: VirtualFile, project: Project): Boolean =
        file.fileType == MonkeyCFileType

    override fun canRun(executorId: String): Boolean = executorId == DefaultDebugExecutor.EXECUTOR_ID

    override fun getConfigurationEditor(project: Project): SettingsEditor<out RunConfiguration> =
        MonkeyCSettingsEditor(project)

    override fun prepareConfiguration(
        configuration: RunConfiguration,
        file: VirtualFile,
        project: Project,
    ): Boolean = configuration is MonkeyCRunConfiguration

    companion object {
        /** Must match the `id` of the `debugAdapterServer` extension in plugin.xml. */
        const val SERVER_ID = "monkeyc"
    }
}
