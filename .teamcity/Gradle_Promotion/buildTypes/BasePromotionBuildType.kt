/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package Gradle_Promotion.buildTypes

import jetbrains.buildServer.configs.kotlin.v2018_2.BuildType
import jetbrains.buildServer.configs.kotlin.v2018_2.CheckoutMode
import jetbrains.buildServer.configs.kotlin.v2018_2.vcs.GitVcsRoot

open class BasePromotionBuildType(vcsRoot: GitVcsRoot, cleanCheckout: Boolean = true, init: BasePromotionBuildType.() -> Unit = {}) : BuildType() {
    init {
        vcs {
            root(vcsRoot)

            checkoutMode = CheckoutMode.ON_SERVER
            this.cleanCheckout = cleanCheckout
            showDependenciesChanges = true
        }

        requirements {
            contains("teamcity.agent.jvm.os.name", "Linux")
        }
        this.init()
    }
}
