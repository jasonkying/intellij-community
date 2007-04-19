/*
 * Copyright 2000-2007 JetBrains s.r.o.
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

package org.jetbrains.plugins.groovy.lang.psi.impl.types;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrReferenceElement;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiElementImpl;
import org.jetbrains.plugins.groovy.lang.parser.GroovyElementTypes;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;

/**
 * @author: Dmitry.Krasilschikov
 * @date: 26.03.2007
 */
public class GrReferenceElementImpl extends GroovyPsiElementImpl implements GrReferenceElement {
  public GrReferenceElementImpl(@NotNull ASTNode node) {
    super(node);
  }

  public String toString(){
    return "Class type";
  }

  public GrReferenceElement getQualifier() {
    return (GrReferenceElement) findChildByType(GroovyElementTypes.REFERENCE_ELEMENT);
  }

  @NotNull
  public String getReferenceName() {
    if (findChildByType(GroovyTokenTypes.mIDENT) != null) {
      return findChildByType(GroovyTokenTypes.mIDENT).getText();
    }
    return null;
  }
}
