/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.roots.ui.configuration;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.project.ProjectBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.java.JavaSourceRootType;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * @author nik
 */
public class JavaModuleSourceRootEditHandler extends JavaSourceRootEditHandlerBase {
  public JavaModuleSourceRootEditHandler() {
    super(JavaSourceRootType.SOURCE);
  }

  @NotNull
  @Override
  public String getRootTypeName() {
    return ProjectBundle.message("module.toggle.sources.action");
  }

  @NotNull
  @Override
  public String getRootsGroupTitle() {
    return ProjectBundle.message("module.paths.sources.group");
  }

  @NotNull
  @Override
  public Icon getRootIcon() {
    return AllIcons.Modules.SourceRoot;
  }

  @Override
  public CustomShortcutSet getMarkRootShortcutSet() {
    return new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.ALT_MASK));
  }

  @NotNull
  @Override
  public Color getRootsGroupColor() {
    return ContentRootPanel.SOURCES_COLOR;
  }

  @NotNull
  @Override
  public String getUnmarkRootActionName() {
    return ProjectBundle.message("module.paths.unmark.source.tooltip");
  }
}
