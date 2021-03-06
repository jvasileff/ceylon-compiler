/*
 * Copyright Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the authors tag. All rights reserved.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License version 2.
 * 
 * This particular file is subject to the "Classpath" exception as provided in the 
 * LICENSE file that accompanied this code.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License,
 * along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package com.redhat.ceylon.compiler.loader;

import com.redhat.ceylon.compiler.typechecker.tree.NaturalVisitor;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.ModuleDescriptor;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.PackageDescriptor;
import com.redhat.ceylon.compiler.typechecker.tree.Util;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

public abstract class SourceDeclarationVisitor extends Visitor implements NaturalVisitor {
    
    public abstract void loadFromSource(ModuleDescriptor that);
    public abstract void loadFromSource(PackageDescriptor that);
    public abstract void loadFromSource(Tree.Declaration decl);
    
    protected boolean isNative(Tree.Declaration decl){
        return Util.hasAnnotation(decl.getAnnotationList(), "native", decl.getUnit());
    }
    
    @Override
    public void visit(Tree.ModuleDescriptor that) {
        loadFromSource(that);
    }
    @Override
    public void visit(Tree.PackageDescriptor that) {
        loadFromSource(that);
    }
    
    @Override
    public void visit(Tree.TypeAliasDeclaration that) {
        loadFromSource(that);
    }

    @Override
    public void visit(Tree.AnyClass that) {
        loadFromSource(that);
    }
    
    @Override
    public void visit(Tree.AnyInterface that) {
        loadFromSource(that);
    }
    
    @Override
    public void visit(Tree.ObjectDefinition that) {
        loadFromSource(that);
    }

    @Override
    public void visit(Tree.AnyMethod that) {
        loadFromSource(that);
    }

    @Override
    public void visit(Tree.AttributeDeclaration that) {
        loadFromSource(that);
    }

    @Override
    public void visit(Tree.AttributeGetterDefinition that) {
        loadFromSource(that);
    }
}