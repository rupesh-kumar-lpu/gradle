/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.composite.internal.plugins;

import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.ProjectPublicationRegistry;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.IncludedBuildState;
import org.gradle.plugin.management.internal.InvalidPluginRequestException;
import org.gradle.plugin.management.internal.PluginRequestInternal;
import org.gradle.plugin.use.PluginId;
import org.gradle.plugin.use.resolve.internal.PluginResolution;
import org.gradle.plugin.use.resolve.internal.PluginResolutionResult;
import org.gradle.plugin.use.resolve.internal.PluginResolveContext;
import org.gradle.plugin.use.resolve.internal.PluginResolver;
import org.gradle.plugin.use.resolve.internal.PluginResolverContributor;
import org.gradle.plugin.use.resolve.internal.local.PluginPublication;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class CompositeBuildPluginResolverContributor implements PluginResolverContributor {

    private static final String SOURCE_DESCRIPTION = "Included Builds";

    private final BuildStateRegistry buildRegistry;
    private final BuildState consumingBuild;
    private final Map<PluginId, PluginResolution> results = new HashMap<>();

    public CompositeBuildPluginResolverContributor(BuildStateRegistry buildRegistry, BuildState consumingBuild) {
        this.buildRegistry = buildRegistry;
        this.consumingBuild = consumingBuild;
    }

    @Override
    public void collectResolversInto(Collection<PluginResolver> resolvers) {
        resolvers.add(new CompositeBuildPluginResolver());
    }

    private class CompositeBuildPluginResolver implements PluginResolver {
        @Override
        public void resolve(PluginRequestInternal pluginRequest, PluginResolutionResult result) throws InvalidPluginRequestException {
            if (buildRegistry.getIncludedBuilds().isEmpty()) {
                return;
            }

            PluginResolution resolution = results.computeIfAbsent(pluginRequest.getId(), this::resolvePluginFromIncludedBuilds);
            if (resolution != null) {
                result.found(SOURCE_DESCRIPTION, resolution);
            } else {
                result.notFound(SOURCE_DESCRIPTION, "None of the included builds contain this plugin");
            }
        }

        private PluginResolution resolvePluginFromIncludedBuilds(PluginId requestedPluginId) {
            for (IncludedBuildState build : buildRegistry.getIncludedBuilds()) {
                if (build == consumingBuild || build.isImplicitBuild()) {
                    // Do not substitute plugins from same build or builds that were not explicitly included
                    continue;
                }
                Optional<PluginResolution> pluginResolution = build.withState(gradleInternal -> resolvePlugin(gradleInternal, requestedPluginId));
                if (pluginResolution.isPresent()) {
                    return pluginResolution.get();
                }
            }
            return null;
        }
    }

    private static Optional<PluginResolution> resolvePlugin(GradleInternal gradle, PluginId requestedPluginId) {
        ProjectPublicationRegistry publicationRegistry = gradle.getServices().get(ProjectPublicationRegistry.class);
        for (ProjectPublicationRegistry.Reference<PluginPublication> reference : publicationRegistry.getPublications(PluginPublication.class)) {
            PluginId pluginId = reference.get().getPluginId();
            if (pluginId.equals(requestedPluginId)) {
                return Optional.of(new LocalPluginResolution(pluginId, reference.getProducingProject()));
            }
        }
        return Optional.empty();
    }

    private static class LocalPluginResolution implements PluginResolution {
        private final PluginId pluginId;
        private final ProjectInternal producingProject;

        LocalPluginResolution(PluginId pluginId, ProjectInternal producingProject) {
            this.pluginId = pluginId;
            this.producingProject = producingProject;
        }

        @Override
        public PluginId getPluginId() {
            return pluginId;
        }

        @Override
        public void execute(PluginResolveContext context) {
            context.addLegacy(pluginId, producingProject.getDependencies().create(producingProject));
        }
    }
}
