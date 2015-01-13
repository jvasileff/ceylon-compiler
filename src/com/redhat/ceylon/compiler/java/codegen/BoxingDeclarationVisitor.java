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

package com.redhat.ceylon.compiler.java.codegen;

import java.util.HashMap;
import java.util.Map;

import com.redhat.ceylon.compiler.typechecker.model.Class;
import com.redhat.ceylon.compiler.typechecker.model.ClassOrInterface;
import com.redhat.ceylon.compiler.typechecker.model.Declaration;
import com.redhat.ceylon.compiler.typechecker.model.Functional;
import com.redhat.ceylon.compiler.typechecker.model.Method;
import com.redhat.ceylon.compiler.typechecker.model.MethodOrValue;
import com.redhat.ceylon.compiler.typechecker.model.ProducedType;
import com.redhat.ceylon.compiler.typechecker.model.Setter;
import com.redhat.ceylon.compiler.typechecker.model.TypeParameter;
import com.redhat.ceylon.compiler.typechecker.model.TypedDeclaration;
import com.redhat.ceylon.compiler.typechecker.model.Value;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AnyAttribute;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AnyMethod;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AttributeArgument;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AttributeDeclaration;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.AttributeSetterDefinition;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.ForComprehensionClause;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.ForIterator;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.FunctionArgument;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.FunctionalParameterDeclaration;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.LazySpecifierExpression;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.MethodArgument;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.PatternIterator;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.SpecifierStatement;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.ValueIterator;
import com.redhat.ceylon.compiler.typechecker.tree.Tree.Variable;
import com.redhat.ceylon.compiler.typechecker.tree.Visitor;

public abstract class BoxingDeclarationVisitor extends Visitor {

    protected abstract boolean isCeylonBasicType(ProducedType type);
    protected abstract boolean isNull(ProducedType type);
    protected abstract boolean isObject(ProducedType type);
    protected abstract boolean isCallable(ProducedType type);
    protected abstract boolean hasErasure(ProducedType type);
    protected abstract boolean willEraseToObject(ProducedType type);
    protected abstract boolean isRaw(ProducedType type);
    protected abstract boolean isWideningTypedDeclaration(TypedDeclaration typedDeclaration);
    protected abstract boolean hasSubstitutedBounds(ProducedType type);

    /**
     * This is used to keep track of some optimisations we do, such as inlining the following shortcuts:
     * class X() extends T(){ m = function() => e; } and we need to be able to map back the lambda with
     * the method it specifies, for boxing and all
     */
    private Map<Method,Method> optimisedMethodSpecifiersToMethods = new HashMap<Method, Method>();
    
    @Override
    public void visit(FunctionArgument that) {
        super.visit(that);
        that.getDeclarationModel().setUnboxed(false);
    }
    
    @Override
    public void visit(MethodArgument that) {
        super.visit(that);
        that.getDeclarationModel().setUnboxed(false);
    }
    
    @Override
    public void visit(AnyMethod that) {
        super.visit(that);
        visitMethod(that.getDeclarationModel());
    }

    private void visitMethod(Method method) {
        boxMethod(method);
        rawTypedDeclaration(method);
        setErasureState(method);
    }
    
    @Override
    public void visit(FunctionalParameterDeclaration that) {
        if (Strategy.createMethod(that.getParameterModel())) {
            // Box the functional parameter as if it were a method
            visitMethod((Method)that.getParameterModel().getModel());
            // Visit the parameters of the functional parameter
            that.visitChildren(this);
        } else {
            super.visit(that);
        }
    }
    
    private void setErasureState(TypedDeclaration decl) {
        // deal with invalid input
        if(decl == null)
            return;

        ProducedType type = decl.getType();
        if(type != null){
            if(hasErasure(type) || hasSubstitutedBounds(type)){
                decl.setTypeErased(true);
            }
            if(decl.isActual()
                    && decl.getContainer() instanceof ClassOrInterface
                    // make sure we did not lose type information due to non-widening
                    && isWideningTypedDeclaration(decl)){
                // widening means not trusting the type, otherwise we end up thinking that the type is
                // something it's not and regular erasure rules don't apply there
                decl.setUntrustedType(true);
                decl.setTypeErased(true);
            }
        }
    }

    private void rawTypedDeclaration(TypedDeclaration decl) {
        // deal with invalid input
        if(decl == null)
            return;

        ProducedType type = decl.getType();
        if(type != null){
            if(isRaw(type))
                type.setRaw(true);
        }
    }

    private void boxMethod(Method method) {
        // deal with invalid input
        if(method == null)
            return;
        Declaration refined = CodegenUtil.getTopmostRefinedDeclaration(method, optimisedMethodSpecifiersToMethods);
        // deal with invalid input
        if(refined == null
                || (!(refined instanceof Method)))
            return;
        TypedDeclaration refinedMethod = (TypedDeclaration)refined;
        if (method.getName() != null) {
            // A Callable, which never have primitive parameters
            setBoxingState(method, refinedMethod);
        } else {
            // Anonymous methods are always boxed
            method.setUnboxed(false);
        }
    }

    private void setBoxingState(TypedDeclaration declaration, TypedDeclaration refinedDeclaration) {
        ProducedType type = declaration.getType();
        if(type == null){
            // an error must have already been reported
            return;
        }
        // fetch the real refined declaration if required
        if (Decl.equal(declaration, refinedDeclaration)
                && declaration instanceof MethodOrValue
                && ((MethodOrValue)declaration).isParameter()
                && declaration.getContainer() instanceof Class){
            // maybe it is really inherited from a field?
            MethodOrValue methodOrValueForParam = (MethodOrValue)declaration;
            if(methodOrValueForParam != null){
                // make sure we get the refined version of that member
                refinedDeclaration = (TypedDeclaration) methodOrValueForParam.getRefinedDeclaration();
            }
        }
        
        // inherit underlying type constraints
        if(!Decl.equal(refinedDeclaration, declaration) && type.getUnderlyingType() == null
                && refinedDeclaration.getType() != null)
            type.setUnderlyingType(refinedDeclaration.getType().getUnderlyingType());
        
        // abort if our boxing state has already been set
        if(declaration.getUnboxed() != null)
            return;
        
        // functional parameter return values are always boxed if we're not creating a method for them
        if(declaration instanceof Method 
                && ((Method)declaration).isParameter()
                && !Strategy.createMethod((Method)declaration)){
            declaration.setUnboxed(false);
            return;
        }
        
        if (!Decl.equal(refinedDeclaration, declaration)) {
            // make sure refined declarations have already been set
            if(refinedDeclaration.getUnboxed() == null)
                setBoxingState(refinedDeclaration, refinedDeclaration);
            // inherit
            declaration.setUnboxed(refinedDeclaration.getUnboxed());
        } else if (declaration instanceof Method
                && CodegenUtil.isVoid(declaration.getType())
                && Strategy.useBoxedVoid((Method)declaration)
                && !(refinedDeclaration.getTypeDeclaration() instanceof TypeParameter)
                && !CodegenUtil.isContainerFunctionalParameter(refinedDeclaration)
                && !(refinedDeclaration instanceof Functional && Decl.isMpl((Functional)refinedDeclaration))){
            declaration.setUnboxed(false);
        } else if((isCeylonBasicType(type) || Decl.isUnboxedVoid(declaration))
           && !(refinedDeclaration.getTypeDeclaration() instanceof TypeParameter)
           && (refinedDeclaration.getContainer() instanceof Declaration == false || !CodegenUtil.isContainerFunctionalParameter(refinedDeclaration))
           && !(refinedDeclaration instanceof Functional && Decl.isMpl((Functional)refinedDeclaration))){
            declaration.setUnboxed(true);
        } else if (Decl.isValueParameter(declaration)
                && CodegenUtil.isContainerFunctionalParameter(declaration)
                && Strategy.createMethod((MethodOrValue)declaration.getContainer())) {
            Method functionalParameter = (Method)declaration.getContainer();
            TypedDeclaration refinedFrom = (TypedDeclaration)CodegenUtil.getTopmostRefinedDeclaration(functionalParameter, optimisedMethodSpecifiersToMethods);
            if (Decl.equal(refinedFrom, functionalParameter) ) {
                // Don't consider Anything to be unboxed, since this is a parameter
                // not a method return type (where void would be considered unboxed).
                if (declaration.getUnit().getAnythingDeclaration().getType().isExactly(declaration.getType())
                        || declaration.getUnit().isOptionalType(declaration.getType())) {
                    declaration.setUnboxed(false);
                } else {
                    declaration.setUnboxed(true);
                }
            } else {
                // make sure refined declarations have already been set
                if(refinedFrom.getUnboxed() == null)
                    setBoxingState(refinedFrom, refinedFrom);
                // inherit
                declaration.setUnboxed(refinedFrom.getUnboxed());
            }
        } else {   
            declaration.setUnboxed(false);
        }
    }

    private void boxAttribute(TypedDeclaration declaration) {
        // deal with invalid input
        if(declaration == null)
            return;
        TypedDeclaration refinedDeclaration = null;
        refinedDeclaration = (TypedDeclaration)CodegenUtil.getTopmostRefinedDeclaration(declaration, optimisedMethodSpecifiersToMethods);
        // deal with invalid input
        if(refinedDeclaration == null)
            return;
        setBoxingState(declaration, refinedDeclaration);
    }
    
    @Override
    public void visit(Tree.Parameter that) {
        super.visit(that);
        TypedDeclaration declaration = that.getParameterModel().getModel();
        visitAttributeOrParameter(declaration);
    }
    
    @Override
    public void visit(Tree.ValueParameterDeclaration that) {
        super.visit(that);
    }
    
    @Override
    public void visit(AnyAttribute that) {
        super.visit(that);
        TypedDeclaration declaration = that.getDeclarationModel();
        visitAttributeOrParameter(declaration);
    }
    
    private void visitAttributeOrParameter(TypedDeclaration declaration) {
        boxAttribute(declaration);
        rawTypedDeclaration(declaration);
        setErasureState(declaration);
    }
    
    @Override
    public void visit(AttributeDeclaration that) {
        if(that.getSpecifierOrInitializerExpression() != null
                && that.getDeclarationModel() != null
                && that.getType() instanceof Tree.ValueModifier
                && that.getDeclarationModel().getType().equals(that.getSpecifierOrInitializerExpression().getExpression().getTypeModel())){
            that.getDeclarationModel().setType(that.getDeclarationModel().getType().withoutUnderlyingType());
        }
        super.visit(that);
    }

    @Override
    public void visit(AttributeArgument that) {
        super.visit(that);
        boxAttribute(that.getDeclarationModel());
    }

    @Override
    public void visit(AttributeSetterDefinition that) {
        super.visit(that);
        Setter declarationModel = that.getDeclarationModel();
        // deal with invalid input
        if(declarationModel == null)
            return;
        TypedDeclaration declaration = declarationModel.getParameter().getModel();
        boxAttribute(declaration);
    }

    @Override
    public void visit(Variable that) {
        super.visit(that);
        TypedDeclaration declaration = that.getDeclarationModel();
        // deal with invalid input
        if(declaration == null)
            return;
        setBoxingState(declaration, declaration);
        rawTypedDeclaration(declaration);
        setErasureState(declaration);
    }

    @Override
    public void visit(SpecifierStatement that) {
        TypedDeclaration declaration = that.getDeclaration();
        Method optimisedDeclaration = null;
        // make sure we detect the shortcut refinement inlining cases
        if(declaration instanceof Method){
            if(that.getSpecifierExpression() != null
                    && that.getSpecifierExpression() instanceof LazySpecifierExpression == false){
                Tree.Term term = Decl.unwrapExpressionsUntilTerm(that.getSpecifierExpression().getExpression());
                if(term != null
                        && term instanceof Tree.FunctionArgument){
                    optimisedDeclaration = ((Tree.FunctionArgument)term).getDeclarationModel();
                    this.optimisedMethodSpecifiersToMethods.put(optimisedDeclaration, (Method) declaration);
                }
            }
        }
        try{
            super.visit(that);
        }finally{
            if(optimisedDeclaration != null)
                this.optimisedMethodSpecifiersToMethods.remove(optimisedDeclaration);
        }
        if(declaration == null)
            return;
        if(declaration instanceof Method){
            visitMethod((Method) declaration);
        }else if(declaration instanceof Value)
            visitAttributeOrParameter(declaration);
    }

    @Override
    public void visit(ForComprehensionClause that) {
        super.visit(that);
        // sort of a hack, because normal visiting rules would declare iterator variables to be potentially
        // unboxed, but the implementation always boxes them for now, so override it after we visit the comprehension
        ForIterator iter = that.getForIterator();
        if (iter instanceof ValueIterator) {
            ((ValueIterator) iter).getVariable().getDeclarationModel().setUnboxed(false);
        } else if (iter instanceof PatternIterator) {
            boxPattern(((PatternIterator) iter).getPattern());
        }
    }
    
    private void boxPattern(Tree.Pattern pattern) {
        if (pattern instanceof Tree.KeyValuePattern) {
            boxPattern(((Tree.KeyValuePattern)pattern).getKey());
            boxPattern(((Tree.KeyValuePattern)pattern).getValue());
        } else if (pattern instanceof Tree.TuplePattern) {
            for (Tree.Pattern p : ((Tree.TuplePattern)pattern).getPatterns()) {
                boxPattern(p);
            }
        } else if (pattern instanceof Tree.VariablePattern) {
            ((Tree.VariablePattern)pattern).getVariable().getDeclarationModel().setUnboxed(false);
        } else {
            throw BugException.unhandledCase(pattern);
        }
    }
    
    @Override
    public void visit(Tree.TypeParameterDeclaration that) {
        super.visit(that);
        TypeParameter typeParameter = that.getDeclarationModel();
        if(typeParameter != null){
            visitTypeParameter(typeParameter);
        }
    }
    
    private void visitTypeParameter(TypeParameter typeParameter) {
        if(typeParameter.hasNonErasedBounds() != null)
            return;
        for(ProducedType pt : typeParameter.getSatisfiedTypes()){
            if(!willEraseToObject(pt)){
                typeParameter.setNonErasedBounds(true);
                return;
            }
        }
        typeParameter.setNonErasedBounds(false);
        return;
    }

}
