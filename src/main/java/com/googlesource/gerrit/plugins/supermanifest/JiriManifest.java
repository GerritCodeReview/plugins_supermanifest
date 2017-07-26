// Copyright (C) 2017 Google Inc
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

import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import org.apache.commons.lang.StringUtils;

class LocalImport {
  @XmlAttribute(required = true)
  private String file;

  /*
   * For debugging purpose
   */
  @Override
  public String toString() {
    return file;
  }

  /** @return the file */
  public String getFile() {
    return file;
  }
}

class Import {
  // Don't need all fields, as we don't use it
}

class Imports {
  @XmlElement(name = "import")
  private Import[] imports;

  @XmlElement(name = "localimport")
  private LocalImport[] localImports;

  public Imports() {
    imports = new Import[0];
    localImports = new LocalImport[0];
  }

  /*
   * For debugging purpose
   */
  @Override
  public String toString() {
    StringBuffer buf = new StringBuffer("");
    if (imports.length > 0) {
      buf.append("import: " + imports.length + "\n");
    }
    if (localImports.length > 0) {
      buf.append("localImports:\n");
      for (LocalImport l : localImports) {
        buf.append(StringUtil.addTab(l.toString()));
      }
    }
    return buf.toString();
  }

  /** @return the imports */
  public Import[] getImports() {
    return imports;
  }

  /** @return the localImports */
  public LocalImport[] getLocalImports() {
    return localImports;
  }
}

class StringUtil {
  public static String addTab(String str) {
    StringBuffer buf = new StringBuffer("");
    String arr[] = str.split("\n");
    for (String s : arr) {
      if (!s.trim().isEmpty()) {
        buf.append("\t" + s + " \n");
      }
    }
    return buf.toString();
  }

  public static String stripAndaddCharsAtEnd(String str, String chs) {
    StringUtils.stripEnd(str, chs);
    return str + chs;
  }
}

@XmlRootElement(name="manifest")
class JiriManifest {
  @XmlElement public Imports imports;

  @XmlElement public JiriProjects projects;

  public JiriManifest() {
    imports = new Imports();
    projects = new JiriProjects();
  }

  /*
   * For debugging purpose
   */
  @Override
  public String toString() {
    StringBuffer buf = new StringBuffer("\nmanifest:\n");
    buf.append(StringUtil.addTab(imports.toString()));
    buf.append(StringUtil.addTab(projects.toString()));
    return buf.toString();
  }
}
