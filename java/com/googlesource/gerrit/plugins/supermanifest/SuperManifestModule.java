// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.supermanifest;

import static com.google.gerrit.server.project.BranchResource.BRANCH_KIND;
import static com.google.inject.Scopes.SINGLETON;

import com.google.gerrit.extensions.events.GitReferenceUpdatedListener;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.inject.assistedinject.FactoryModuleBuilder;

public class SuperManifestModule extends RestApiModule {
  SuperManifestModule() {}

  @Override
  protected void configure() {
    DynamicSet.bind(binder(), GitReferenceUpdatedListener.class)
        .to(SuperManifestRefUpdatedListener.class)
        .in(SINGLETON);
    DynamicSet.bind(binder(), LifecycleListener.class)
        .to(SuperManifestRefUpdatedListener.class)
        .in(SINGLETON);
    install(
        new FactoryModuleBuilder()
            .implement(
                SuperManifestRefUpdatedListener.SuperManifestRepoManager.class,
                SuperManifestRefUpdatedListener.GerritSuperManifestRepoManager.class)
            .build(SuperManifestRefUpdatedListener.SuperManifestRepoManager.Factory.class));
    post(BRANCH_KIND, "update_manifest").to(SuperManifestRefUpdatedListener.class).in(SINGLETON);
  }
}
