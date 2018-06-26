/*
 * Copyright 2017 the original author or authors.
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
package org.gradle.integtests.resolve

import org.gradle.integtests.fixtures.AbstractDependencyResolutionTest
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.Issue

@Issue("https://github.com/gradle/gradle-private/issues/1341")
@Requires(value = [TestPrecondition.ONLINE, TestPrecondition.JDK8_OR_LATER])
class GradlePluginPortalDependencyResolveIntegrationTest extends AbstractDependencyResolutionTest {

    def gradlePluginPortalRepository = "repositories { gradlePluginPortal() }"
    def pluginClasspathDependency = "org.gradle:gradle-hello-world-plugin:0.2"

    def "buildscript dependencies can be resolved from gradlePluginPortal()"() {
        given:
        buildFile << """
            buildscript {
                $gradlePluginPortalRepository
                dependencies {
                    classpath("$pluginClasspathDependency")
                }
            }
            apply plugin: "org.gradle.hello-world"
        """

        when:
        succeeds "helloWorld"

        then:
        outputContains("Hello World!")
    }

    def "project dependencies can be resolved from gradlePluginPortal()"() {
        given:
        buildFile << """
            plugins {
                id("java")
            }

            $gradlePluginPortalRepository

            dependencies {
                implementation("$pluginClasspathDependency")
            }
            
            task("resolveDependencies") {
                doLast {
                    println(configurations.runtimeClasspath.files)
                }
            } 
        """

        expect:
        succeeds "resolveDependencies"
    }
}
