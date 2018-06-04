/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.cargo.runconfig.test

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.runconfig.command.CargoCommandConfiguration
import org.rust.cargo.runconfig.command.CargoCommandConfigurationType
import org.rust.cargo.runconfig.mergeWithDefault
import org.rust.cargo.toolchain.CargoCommandLine
import org.rust.lang.core.psi.RsFunction
import org.rust.lang.core.psi.ext.*

class CargoTestRunConfigurationProducer : RunConfigurationProducer<CargoCommandConfiguration>(CargoCommandConfigurationType()) {

    override fun isConfigurationFromContext(
        configuration: CargoCommandConfiguration,
        context: ConfigurationContext
    ): Boolean {
        val test = findTest(context) ?: return false
        return configuration.canBeFrom(test.cargoCommandLine())
    }

    override fun setupConfigurationFromContext(
        configuration: CargoCommandConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>
    ): Boolean {
        val test = findTest(context) ?: return false
        sourceElement.set(test.sourceElement)
        configuration.name = test.configurationName
        val commandLine = test.cargoCommandLine() ?: return false
        val cmd = commandLine.mergeWithDefault(configuration)
        configuration.setFromCmd(cmd)
        return true
    }

    companion object {
        fun findTest(context: ConfigurationContext): TestConfig? {
            val elements: Array<PsiElement> = LangDataKeys.PSI_ELEMENT_ARRAY.getData(context.dataContext)
                ?: context.location?.psiElement?.let { arrayOf(it) } ?: return null
            return findTest(elements)
        }

        fun findTest(psi: Array<PsiElement>, climbUp: Boolean = true): TestConfig? =
            when (psi.size) {
                0 -> null
                else ->
                    TestConfig.MultipleFileTestConfig.create(psi.mapNotNull {
                        findElement<RsMod>(it, climbUp)
                    }.toTypedArray()) ?:

                    findElement<RsFunction>(psi[0], climbUp)?.let {
                        TestConfig.SingleTestConfig.FunctionTestConfig.create(it)
                    } ?:

                    findElement<RsMod>(psi[0], climbUp)?.let {
                        TestConfig.SingleTestConfig.ModuleTestConfig.create(it)
                    }
            }

        private inline fun <reified T : PsiElement> findElement(base: PsiElement, climbUp: Boolean): T? {
            if (base is T) return base
            if (!climbUp) return null
            return base.ancestorOrSelf()
        }
    }
}



sealed class TestConfig {
    companion object {
        private fun hasTestFunction(mod: RsMod): Boolean =
            mod.processExpandedItems { it is RsFunction && it.isTest }
    }

    sealed class SingleTestConfig : TestConfig() {
        class FunctionTestConfig(
            override val path: String,
            override val target: CargoWorkspace.Target,
            override val sourceElement: RsElement
        ) : SingleTestConfig() {
            companion object {
                fun create(function: RsFunction): FunctionTestConfig? {
                    if (!function.isTest) {
                        return null
                    }
                    val configPath = function.crateRelativePath.configPath() ?: return null
                    val target = function.containingCargoTarget ?: return null
                    return FunctionTestConfig(configPath, target, function)
                }
            }

            override val configurationName: String = "Test $path"
            override val exact = true
        }

        class ModuleTestConfig(
            override val path: String,
            override val target: CargoWorkspace.Target,
            override val sourceElement: RsMod
        ) : SingleTestConfig() {
            companion object {
                fun create(module: RsMod): ModuleTestConfig? {
                    val configPath = module.crateRelativePath.configPath() ?: return null
                    if (!hasTestFunction(module)) {
                        return null
                    }
                    val target = module.containingCargoTarget ?: return null
                    return ModuleTestConfig(configPath, target, module)
                }
            }

            override val configurationName: String
                get() = if (sourceElement.modName == "test" || sourceElement.modName == "tests")
                    "Test ${sourceElement.`super`?.modName}::${sourceElement.modName}"
                else
                    "Test ${sourceElement.modName}"

            override val exact = false
        }

        abstract val target: CargoWorkspace.Target
        override val targets: Array<CargoWorkspace.Target>
            get() = arrayOf(target)
    }

    class MultipleFileTestConfig(
        override val targets: Array<CargoWorkspace.Target>,
        override val sourceElement: RsElement
    ) : TestConfig() {
        companion object {
            fun create(modules: Array<RsMod>): MultipleFileTestConfig? {
                val modulesWithTests = modules
                    .filter { hasTestFunction(it) }
                    .filter { it.containingCargoTarget != null }

                val targets = modulesWithTests
                    .mapNotNull { it.containingCargoTarget }
                    .toTypedArray()
                if (targets.size <= 1) {
                    return null
                }

                // If the selection spans more than one package, bail out.
                val pkgs = targets.map { it.pkg }.distinct()
                if (pkgs.size > 1) {
                    return null
                }

                return MultipleFileTestConfig(targets, modulesWithTests[0])
            }
        }

        override val configurationName: String = "Test multiple selected files"
        override val exact = false
        override val path: String = ""
    }

    abstract val path: String
    abstract val exact: Boolean
    abstract val targets: Array<CargoWorkspace.Target>
    abstract val configurationName: String
    abstract val sourceElement: RsElement

    fun cargoCommandLine(): CargoCommandLine {
        var commandLine = CargoCommandLine.forTargets(targets, "test", listOf(path))
        if (exact) {
            commandLine = commandLine.withDoubleDashFlag("--exact")
        }
        return commandLine
    }
}

// We need to chop off heading colon `::`, since `crateRelativePath`
// always returns fully-qualified path
private fun String?.configPath(): String? = this?.removePrefix("::")
