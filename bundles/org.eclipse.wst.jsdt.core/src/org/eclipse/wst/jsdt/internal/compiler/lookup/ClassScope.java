/*******************************************************************************
 * Copyright (c) 2000, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.wst.jsdt.internal.compiler.lookup;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.wst.jsdt.core.compiler.CharOperation;
import org.eclipse.wst.jsdt.core.infer.InferredType;
import org.eclipse.wst.jsdt.internal.compiler.ast.ASTNode;
import org.eclipse.wst.jsdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.wst.jsdt.internal.compiler.ast.AbstractVariableDeclaration;
import org.eclipse.wst.jsdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.wst.jsdt.internal.compiler.ast.QualifiedAllocationExpression;
import org.eclipse.wst.jsdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.wst.jsdt.internal.compiler.ast.TypeParameter;
import org.eclipse.wst.jsdt.internal.compiler.ast.TypeReference;
import org.eclipse.wst.jsdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.wst.jsdt.internal.compiler.env.AccessRestriction;
import org.eclipse.wst.jsdt.internal.compiler.problem.AbortCompilation;
import org.eclipse.wst.jsdt.internal.compiler.problem.ProblemReporter;
import org.eclipse.wst.jsdt.internal.compiler.util.HashtableOfObject;

public class ClassScope extends Scope {

	public TypeDeclaration referenceContext;
	public TypeReference superTypeReference;
	public InferredType inferredType;


	public ClassScope(Scope parent, TypeDeclaration context) {
		super(CLASS_SCOPE, parent);
		this.referenceContext = context;
	}
	public ClassScope(Scope parent, InferredType type) {
		super(CLASS_SCOPE, parent);
		this.inferredType = type;
	}

	void buildAnonymousTypeBinding(SourceTypeBinding enclosingType, ReferenceBinding supertype) {
		LocalTypeBinding anonymousType = buildLocalType(enclosingType, enclosingType.fPackage);
		SourceTypeBinding sourceType = getReferenceBinding();
		if (supertype.isInterface()) {
			sourceType.superclass = getJavaLangObject();
			sourceType.superInterfaces = new ReferenceBinding[] { supertype };
		} else {
			sourceType.superclass = supertype;
			sourceType.superInterfaces = Binding.NO_SUPERINTERFACES;
		}
		connectMemberTypes();
		buildFieldsAndMethods();
		anonymousType.faultInTypesForFieldsAndMethods();
		sourceType.verifyMethods(environment().methodVerifier());
	}

	private void buildFields() {
		if (referenceContext.fields == null) {
			getReferenceBinding().setFields(Binding.NO_FIELDS);
			return;
		}
		// count the number of fields vs. initializers
		FieldDeclaration[] fields = referenceContext.fields;
		int size = fields.length;
		int count = 0;
		for (int i = 0; i < size; i++) {
			switch (fields[i].getKind()) {
				case AbstractVariableDeclaration.FIELD:
				case AbstractVariableDeclaration.ENUM_CONSTANT:
					count++;
			}
		}

		// iterate the field declarations to create the bindings, lose all duplicates
		FieldBinding[] fieldBindings = new FieldBinding[count];
		HashtableOfObject knownFieldNames = new HashtableOfObject(count);
		boolean duplicate = false;
		count = 0;
		for (int i = 0; i < size; i++) {
			FieldDeclaration field = fields[i];
			if (field.getKind() == AbstractVariableDeclaration.INITIALIZER) {
				if (getReferenceBinding().isInterface())
					problemReporter().interfaceCannotHaveInitializers(getReferenceBinding(), field);
			} else {
				//FieldBinding fieldBinding = new FieldBinding(field.name, null, field.modifiers | ExtraCompilerModifiers.AccUnresolved, getReferenceBinding());
				FieldBinding fieldBinding = new FieldBinding(field.binding,  getReferenceBinding());
				fieldBinding.id = count;
				// field's type will be resolved when needed for top level types
				checkAndSetModifiersForField(fieldBinding, field);

				if (knownFieldNames.containsKey(field.name)) {
					duplicate = true;
					FieldBinding previousBinding = (FieldBinding) knownFieldNames.get(field.name);
					if (previousBinding != null) {
						for (int f = 0; f < i; f++) {
							FieldDeclaration previousField = fields[f];
							if (previousField.binding == previousBinding) {
								problemReporter().duplicateFieldInType(getReferenceBinding(), previousField);
								previousField.binding = null;
								break;
							}
						}
					}
					knownFieldNames.put(field.name, null); // ensure that the duplicate field is found & removed
					problemReporter().duplicateFieldInType(getReferenceBinding(), field);
					field.binding = null;
				} else {
					knownFieldNames.put(field.name, fieldBinding);
					// remember that we have seen a field with this name
					fieldBindings[count++] = fieldBinding;
				}
			}
		}
		// remove duplicate fields
		if (duplicate) {
			FieldBinding[] newFieldBindings = new FieldBinding[fieldBindings.length];
			// we know we'll be removing at least 1 duplicate name
			size = count;
			count = 0;
			for (int i = 0; i < size; i++) {
				FieldBinding fieldBinding = fieldBindings[i];
				if (knownFieldNames.get(fieldBinding.name) != null) {
					fieldBinding.id = count;
					newFieldBindings[count++] = fieldBinding;
				}
			}
			fieldBindings = newFieldBindings;
		}
		if (count != fieldBindings.length)
			System.arraycopy(fieldBindings, 0, fieldBindings = new FieldBinding[count], 0, count);
		getReferenceBinding().setFields(fieldBindings);
	}

	void buildFieldsAndMethods() {
		buildFields();
		buildMethods();

		SourceTypeBinding sourceType = getReferenceBinding();
		if (sourceType.isMemberType() && !sourceType.isLocalType())
			 ((MemberTypeBinding) sourceType).checkSyntheticArgsAndFields();

		ReferenceBinding[] memberTypes = sourceType.memberTypes;
		for (int i = 0, length = memberTypes.length; i < length; i++)
			 ((SourceTypeBinding) memberTypes[i]).classScope.buildFieldsAndMethods();
	}
	public SourceTypeBinding getReferenceBinding()
	{
		if (referenceContext!=null)
			return referenceContext.binding;
		else
			return inferredType.binding;
	}

	private LocalTypeBinding buildLocalType(SourceTypeBinding enclosingType, PackageBinding packageBinding) {

		referenceContext.scope = this;
		referenceContext.staticInitializerScope = new MethodScope(this, referenceContext, true);
		referenceContext.initializerScope = new MethodScope(this, referenceContext, false);

		// build the binding or the local type
		LocalTypeBinding localType = new LocalTypeBinding(this, enclosingType, this.innermostSwitchCase());
		referenceContext.binding = localType;
		checkAndSetModifiers();
		buildTypeVariables();

		// Look at member types
		ReferenceBinding[] memberTypeBindings = Binding.NO_MEMBER_TYPES;
		if (referenceContext.memberTypes != null) {
			int size = referenceContext.memberTypes.length;
			memberTypeBindings = new ReferenceBinding[size];
			int count = 0;
			nextMember : for (int i = 0; i < size; i++) {
				TypeDeclaration memberContext = referenceContext.memberTypes[i];
				switch(TypeDeclaration.kind(memberContext.modifiers)) {
					case TypeDeclaration.INTERFACE_DECL :
					case TypeDeclaration.ANNOTATION_TYPE_DECL :
						problemReporter().illegalLocalTypeDeclaration(memberContext);
						continue nextMember;
				}
				ReferenceBinding type = localType;
				// check that the member does not conflict with an enclosing type
				do {
					if (CharOperation.equals(type.sourceName, memberContext.name)) {
						problemReporter().typeCollidesWithEnclosingType(memberContext);
						continue nextMember;
					}
					type = type.enclosingType();
				} while (type != null);
				// check the member type does not conflict with another sibling member type
				for (int j = 0; j < i; j++) {
					if (CharOperation.equals(referenceContext.memberTypes[j].name, memberContext.name)) {
						problemReporter().duplicateNestedType(memberContext);
						continue nextMember;
					}
				}
				ClassScope memberScope = new ClassScope(this, referenceContext.memberTypes[i]);
				LocalTypeBinding memberBinding = memberScope.buildLocalType(localType, packageBinding);
				memberBinding.setAsMemberType();
				memberTypeBindings[count++] = memberBinding;
			}
			if (count != size)
				System.arraycopy(memberTypeBindings, 0, memberTypeBindings = new ReferenceBinding[count], 0, count);
		}
		localType.memberTypes = memberTypeBindings;
		return localType;
	}

	void buildLocalTypeBinding(SourceTypeBinding enclosingType) {

		LocalTypeBinding localType = buildLocalType(enclosingType, enclosingType.fPackage);
		connectTypeHierarchy();
		buildFieldsAndMethods();
		localType.faultInTypesForFieldsAndMethods();

		getReferenceBinding().verifyMethods(environment().methodVerifier());
	}

	private void buildMemberTypes(AccessRestriction accessRestriction) {
	    SourceTypeBinding sourceType = getReferenceBinding();
		ReferenceBinding[] memberTypeBindings = Binding.NO_MEMBER_TYPES;
		if (referenceContext.memberTypes != null) {
			int length = referenceContext.memberTypes.length;
			memberTypeBindings = new ReferenceBinding[length];
			int count = 0;
			nextMember : for (int i = 0; i < length; i++) {
				TypeDeclaration memberContext = referenceContext.memberTypes[i];
				switch(TypeDeclaration.kind(memberContext.modifiers)) {
					case TypeDeclaration.INTERFACE_DECL :
					case TypeDeclaration.ANNOTATION_TYPE_DECL :
						if (sourceType.isNestedType()
								&& sourceType.isClass() // no need to check for enum, since implicitly static
								&& !sourceType.isStatic()) {
							problemReporter().illegalLocalTypeDeclaration(memberContext);
							continue nextMember;
						}
					break;
				}
				ReferenceBinding type = sourceType;
				// check that the member does not conflict with an enclosing type
				do {
					if (CharOperation.equals(type.sourceName, memberContext.name)) {
						problemReporter().typeCollidesWithEnclosingType(memberContext);
						continue nextMember;
					}
					type = type.enclosingType();
				} while (type != null);
				// check that the member type does not conflict with another sibling member type
				for (int j = 0; j < i; j++) {
					if (CharOperation.equals(referenceContext.memberTypes[j].name, memberContext.name)) {
						problemReporter().duplicateNestedType(memberContext);
						continue nextMember;
					}
				}

				ClassScope memberScope = new ClassScope(this, memberContext);
				memberTypeBindings[count++] = memberScope.buildType(sourceType, sourceType.fPackage, accessRestriction);
			}
			if (count != length)
				System.arraycopy(memberTypeBindings, 0, memberTypeBindings = new ReferenceBinding[count], 0, count);
		}
		sourceType.memberTypes = memberTypeBindings;
	}

	private void buildMethods() {
		boolean isEnum = TypeDeclaration.kind(referenceContext.modifiers) == TypeDeclaration.ENUM_DECL;
		if (referenceContext.methods == null && !isEnum) {
			getReferenceBinding().setMethods(Binding.NO_METHODS);
			return;
		}

		// iterate the method declarations to create the bindings
		AbstractMethodDeclaration[] methods = referenceContext.methods;
		int size = methods == null ? 0 : methods.length;
		// look for <clinit> method
		int clinitIndex = -1;
		for (int i = 0; i < size; i++) {
			if (methods[i].isClinit()) {
				clinitIndex = i;
				break;
			}
		}

		int count = isEnum ? 2 : 0; // reserve 2 slots for special enum methods: #values() and #valueOf(String)
		MethodBinding[] methodBindings = new MethodBinding[(clinitIndex == -1 ? size : size - 1) + count];
		// create special methods for enums
	    SourceTypeBinding sourceType = getReferenceBinding();
		if (isEnum) {
			methodBindings[0] = sourceType.addSyntheticEnumMethod(TypeConstants.VALUES); // add <EnumType>[] values()
			methodBindings[1] = sourceType.addSyntheticEnumMethod(TypeConstants.VALUEOF); // add <EnumType> valueOf()
		}
		// create bindings for source methods
		for (int i = 0; i < size; i++) {
			if (i != clinitIndex) {
				MethodScope scope = new MethodScope(this, methods[i], false);
				MethodBinding methodBinding = scope.createMethod(methods[i],methods[i].selector,sourceType,false,false);
				if (methodBinding != null) // is null if binding could not be created
					methodBindings[count++] = methodBinding;
			}
		}
		if (count != methodBindings.length)
			System.arraycopy(methodBindings, 0, methodBindings = new MethodBinding[count], 0, count);
		sourceType.tagBits &= ~TagBits.AreMethodsSorted; // in case some static imports reached already into this type
		sourceType.setMethods(methodBindings);
	}

	SourceTypeBinding buildType(SourceTypeBinding enclosingType, PackageBinding packageBinding, AccessRestriction accessRestriction) {
		// provide the typeDeclaration with needed scopes
		referenceContext.scope = this;
		referenceContext.staticInitializerScope = new MethodScope(this, referenceContext, true);
		referenceContext.initializerScope = new MethodScope(this, referenceContext, false);

		if (enclosingType == null) {
			char[][] className = CharOperation.arrayConcat(packageBinding.compoundName, referenceContext.name);
			if (referenceContext!=null)
				referenceContext.binding= new SourceTypeBinding(className, packageBinding, this);
		} else {
			char[][] className = CharOperation.deepCopy(enclosingType.compoundName);
			className[className.length - 1] =
				CharOperation.concat(className[className.length - 1], referenceContext.name, '$');
			ReferenceBinding existingType = packageBinding.getType0(className[className.length - 1]);
			if (existingType != null)
				// report the error against the parent - its still safe to answer the member type
				this.parent.problemReporter().duplicateNestedType(referenceContext);
			referenceContext.binding = new MemberTypeBinding(className, this, enclosingType);
		}

		SourceTypeBinding sourceType = getReferenceBinding();
		environment().setAccessRestriction(sourceType, accessRestriction);
		sourceType.fPackage.addType(sourceType);
		checkAndSetModifiers();
		buildTypeVariables();
		buildMemberTypes(accessRestriction);
		return sourceType;
	}

	private void buildTypeVariables() {

	    SourceTypeBinding sourceType = getReferenceBinding();
		TypeParameter[] typeParameters = referenceContext.typeParameters;

	    // do not construct type variables if source < 1.5
		if (typeParameters == null || compilerOptions().sourceLevel < ClassFileConstants.JDK1_5) {
		    sourceType.typeVariables = Binding.NO_TYPE_VARIABLES;
		    return;
		}
		sourceType.typeVariables = Binding.NO_TYPE_VARIABLES; // safety

		if (sourceType.id == T_JavaLangObject) { // handle the case of redefining java.lang.Object up front
			problemReporter().objectCannotBeGeneric(referenceContext);
			return;
		}
		sourceType.typeVariables = createTypeVariables(typeParameters, sourceType);
		sourceType.modifiers |= ExtraCompilerModifiers.AccGenericSignature;
	}

	private void checkAndSetModifiers() {
		SourceTypeBinding sourceType = getReferenceBinding();
		int modifiers = sourceType.modifiers;
		if ((modifiers & ExtraCompilerModifiers.AccAlternateModifierProblem) != 0)
			problemReporter().duplicateModifierForType(sourceType);
		ReferenceBinding enclosingType = sourceType.enclosingType();
		boolean isMemberType = sourceType.isMemberType();
		if (isMemberType) {
			modifiers |= (enclosingType.modifiers & (ExtraCompilerModifiers.AccGenericSignature|ClassFileConstants.AccStrictfp));
			// checks for member types before local types to catch local members
			if (enclosingType.isInterface())
				modifiers |= ClassFileConstants.AccPublic;
			if (sourceType.isEnum()) {
				if (!enclosingType.isStatic())
					problemReporter().nonStaticContextForEnumMemberType(sourceType);
				else
					modifiers |= ClassFileConstants.AccStatic;
			}
			if (enclosingType.isViewedAsDeprecated() && !sourceType.isDeprecated())
				modifiers |= ExtraCompilerModifiers.AccDeprecatedImplicitly;
		} else if (sourceType.isLocalType()) {
			if (sourceType.isEnum()) {
				problemReporter().illegalLocalTypeDeclaration(referenceContext);
				sourceType.modifiers = 0;
				return;
			}
			if (sourceType.isAnonymousType()) {
			    modifiers |= ClassFileConstants.AccFinal;
			    // set AccEnum flag for anonymous body of enum constants
			    if (referenceContext.allocation.type == null)
			    	modifiers |= ClassFileConstants.AccEnum;
			}
			Scope scope = this;
			do {
				switch (scope.kind) {
					case METHOD_SCOPE :
						MethodScope methodScope = (MethodScope) scope;
						if (methodScope.isInsideInitializer()) {
							SourceTypeBinding type = ((TypeDeclaration) methodScope.referenceContext).binding;

							// inside field declaration ? check field modifier to see if deprecated
							if (methodScope.initializedField != null) {
									// currently inside this field initialization
								if (methodScope.initializedField.isViewedAsDeprecated() && !sourceType.isDeprecated())
									modifiers |= ExtraCompilerModifiers.AccDeprecatedImplicitly;
							} else {
								if (type.isStrictfp())
									modifiers |= ClassFileConstants.AccStrictfp;
								if (type.isViewedAsDeprecated() && !sourceType.isDeprecated())
									modifiers |= ExtraCompilerModifiers.AccDeprecatedImplicitly;
							}
						} else {
							MethodBinding method = ((AbstractMethodDeclaration) methodScope.referenceContext).binding;
							if (method != null) {
								if (method.isStrictfp())
									modifiers |= ClassFileConstants.AccStrictfp;
								if (method.isViewedAsDeprecated() && !sourceType.isDeprecated())
									modifiers |= ExtraCompilerModifiers.AccDeprecatedImplicitly;
							}
						}
						break;
					case CLASS_SCOPE :
						// local member
						if (enclosingType.isStrictfp())
							modifiers |= ClassFileConstants.AccStrictfp;
						if (enclosingType.isViewedAsDeprecated() && !sourceType.isDeprecated())
							modifiers |= ExtraCompilerModifiers.AccDeprecatedImplicitly;
						break;
				}
				scope = scope.parent;
			} while (scope != null);
		}

		// after this point, tests on the 16 bits reserved.
		int realModifiers = modifiers & ExtraCompilerModifiers.AccJustFlag;

		if ((realModifiers & ClassFileConstants.AccInterface) != 0) { // interface and annotation type
			// detect abnormal cases for interfaces
			if (isMemberType) {
				final int UNEXPECTED_MODIFIERS =
					~(ClassFileConstants.AccPublic | ClassFileConstants.AccPrivate | ClassFileConstants.AccProtected | ClassFileConstants.AccStatic | ClassFileConstants.AccAbstract | ClassFileConstants.AccInterface | ClassFileConstants.AccStrictfp | ClassFileConstants.AccAnnotation);
				if ((realModifiers & UNEXPECTED_MODIFIERS) != 0) {
					if ((realModifiers & ClassFileConstants.AccAnnotation) != 0)
						problemReporter().illegalModifierForAnnotationMemberType(sourceType);
					else
						problemReporter().illegalModifierForMemberInterface(sourceType);
				}
				/*
				} else if (sourceType.isLocalType()) { //interfaces cannot be defined inside a method
					int unexpectedModifiers = ~(AccAbstract | AccInterface | AccStrictfp);
					if ((realModifiers & unexpectedModifiers) != 0)
						problemReporter().illegalModifierForLocalInterface(sourceType);
				*/
			} else {
				final int UNEXPECTED_MODIFIERS = ~(ClassFileConstants.AccPublic | ClassFileConstants.AccAbstract | ClassFileConstants.AccInterface | ClassFileConstants.AccStrictfp | ClassFileConstants.AccAnnotation);
				if ((realModifiers & UNEXPECTED_MODIFIERS) != 0) {
					if ((realModifiers & ClassFileConstants.AccAnnotation) != 0)
						problemReporter().illegalModifierForAnnotationType(sourceType);
					else
						problemReporter().illegalModifierForInterface(sourceType);
				}
			}
			modifiers |= ClassFileConstants.AccAbstract;
		} else if ((realModifiers & ClassFileConstants.AccEnum) != 0) {
			// detect abnormal cases for enums
			if (isMemberType) { // includes member types defined inside local types
				final int UNEXPECTED_MODIFIERS = ~(ClassFileConstants.AccPublic | ClassFileConstants.AccPrivate | ClassFileConstants.AccProtected | ClassFileConstants.AccStatic | ClassFileConstants.AccStrictfp | ClassFileConstants.AccEnum);
				if ((realModifiers & UNEXPECTED_MODIFIERS) != 0)
					problemReporter().illegalModifierForMemberEnum(sourceType);
			} else if (sourceType.isLocalType()) { // each enum constant is an anonymous local type
				final int UNEXPECTED_MODIFIERS = ~(ClassFileConstants.AccStrictfp | ClassFileConstants.AccFinal | ClassFileConstants.AccEnum); // add final since implicitly set for anonymous type
				if ((realModifiers & UNEXPECTED_MODIFIERS) != 0)
					problemReporter().illegalModifierForLocalEnum(sourceType);
			} else {
				final int UNEXPECTED_MODIFIERS = ~(ClassFileConstants.AccPublic | ClassFileConstants.AccStrictfp | ClassFileConstants.AccEnum);
				if ((realModifiers & UNEXPECTED_MODIFIERS) != 0)
					problemReporter().illegalModifierForEnum(sourceType);
			}
			if (!sourceType.isAnonymousType()) {
				checkAbstractEnum: {
					// does define abstract methods ?
					if ((referenceContext.bits & ASTNode.HasAbstractMethods) != 0) {
						modifiers |= ClassFileConstants.AccAbstract;
						break checkAbstractEnum;
					}
					// body of enum constant must implement any inherited abstract methods
					// enum type needs to implement abstract methods if one of its constants does not supply a body
					TypeDeclaration typeDeclaration = this.referenceContext;
					FieldDeclaration[] fields = typeDeclaration.fields;
					int fieldsLength = fields == null ? 0 : fields.length;
					if (fieldsLength == 0) break checkAbstractEnum; // has no constants so must implement the method itself
					AbstractMethodDeclaration[] methods = typeDeclaration.methods;
					int methodsLength = methods == null ? 0 : methods.length;
					// TODO (kent) cannot tell that the superinterfaces are empty or that their methods are implemented
					boolean definesAbstractMethod = typeDeclaration.superInterfaces != null;
					for (int i = 0; i < methodsLength && !definesAbstractMethod; i++)
						definesAbstractMethod = methods[i].isAbstract();
					if (!definesAbstractMethod) break checkAbstractEnum; // all methods have bodies
					boolean needAbstractBit = false;
					for (int i = 0; i < fieldsLength; i++) {
						FieldDeclaration fieldDecl = fields[i];
						if (fieldDecl.getKind() == AbstractVariableDeclaration.ENUM_CONSTANT) {
							if (fieldDecl.initialization instanceof QualifiedAllocationExpression) {
								needAbstractBit = true;
							} else {
								break checkAbstractEnum;
							}
						}
					}
					// tag this enum as abstract since an abstract method must be implemented AND all enum constants define an anonymous body
					// as a result, each of its anonymous constants will see it as abstract and must implement each inherited abstract method
					if (needAbstractBit) {
						modifiers |= ClassFileConstants.AccAbstract;
					}
				}
				// final if no enum constant with anonymous body
				checkFinalEnum: {
					TypeDeclaration typeDeclaration = this.referenceContext;
					FieldDeclaration[] fields = typeDeclaration.fields;
					if (fields != null) {
						for (int i = 0, fieldsLength = fields.length; i < fieldsLength; i++) {
							FieldDeclaration fieldDecl = fields[i];
							if (fieldDecl.getKind() == AbstractVariableDeclaration.ENUM_CONSTANT) {
								if (fieldDecl.initialization instanceof QualifiedAllocationExpression) {
									break checkFinalEnum;
								}
							}
						}
					}
					modifiers |= ClassFileConstants.AccFinal;
				}
			}
		} else {
			// detect abnormal cases for classes
			if (isMemberType) { // includes member types defined inside local types
				final int UNEXPECTED_MODIFIERS = ~(ClassFileConstants.AccPublic | ClassFileConstants.AccPrivate | ClassFileConstants.AccProtected | ClassFileConstants.AccStatic | ClassFileConstants.AccAbstract | ClassFileConstants.AccFinal | ClassFileConstants.AccStrictfp);
				if ((realModifiers & UNEXPECTED_MODIFIERS) != 0)
					problemReporter().illegalModifierForMemberClass(sourceType);
			} else if (sourceType.isLocalType()) {
				final int UNEXPECTED_MODIFIERS = ~(ClassFileConstants.AccAbstract | ClassFileConstants.AccFinal | ClassFileConstants.AccStrictfp);
				if ((realModifiers & UNEXPECTED_MODIFIERS) != 0)
					problemReporter().illegalModifierForLocalClass(sourceType);
			} else {
				final int UNEXPECTED_MODIFIERS = ~(ClassFileConstants.AccPublic | ClassFileConstants.AccAbstract | ClassFileConstants.AccFinal | ClassFileConstants.AccStrictfp);
				if ((realModifiers & UNEXPECTED_MODIFIERS) != 0)
					problemReporter().illegalModifierForClass(sourceType);
			}

			// check that Final and Abstract are not set together
			if ((realModifiers & (ClassFileConstants.AccFinal | ClassFileConstants.AccAbstract)) == (ClassFileConstants.AccFinal | ClassFileConstants.AccAbstract))
				problemReporter().illegalModifierCombinationFinalAbstractForClass(sourceType);
		}

		if (isMemberType) {
			// test visibility modifiers inconsistency, isolate the accessors bits
			if (enclosingType.isInterface()) {
				if ((realModifiers & (ClassFileConstants.AccProtected | ClassFileConstants.AccPrivate)) != 0) {
					problemReporter().illegalVisibilityModifierForInterfaceMemberType(sourceType);

					// need to keep the less restrictive
					if ((realModifiers & ClassFileConstants.AccProtected) != 0)
						modifiers &= ~ClassFileConstants.AccProtected;
					if ((realModifiers & ClassFileConstants.AccPrivate) != 0)
						modifiers &= ~ClassFileConstants.AccPrivate;
				}
			} else {
				int accessorBits = realModifiers & (ClassFileConstants.AccPublic | ClassFileConstants.AccProtected | ClassFileConstants.AccPrivate);
				if ((accessorBits & (accessorBits - 1)) > 1) {
					problemReporter().illegalVisibilityModifierCombinationForMemberType(sourceType);

					// need to keep the less restrictive so disable Protected/Private as necessary
					if ((accessorBits & ClassFileConstants.AccPublic) != 0) {
						if ((accessorBits & ClassFileConstants.AccProtected) != 0)
							modifiers &= ~ClassFileConstants.AccProtected;
						if ((accessorBits & ClassFileConstants.AccPrivate) != 0)
							modifiers &= ~ClassFileConstants.AccPrivate;
					} else if ((accessorBits & ClassFileConstants.AccProtected) != 0 && (accessorBits & ClassFileConstants.AccPrivate) != 0) {
						modifiers &= ~ClassFileConstants.AccPrivate;
					}
				}
			}

			// static modifier test
			if ((realModifiers & ClassFileConstants.AccStatic) == 0) {
				if (enclosingType.isInterface())
					modifiers |= ClassFileConstants.AccStatic;
			} else if (!enclosingType.isStatic()) {
				// error the enclosing type of a static field must be static or a top-level type
				problemReporter().illegalStaticModifierForMemberType(sourceType);
			}
		}

		sourceType.modifiers = modifiers;
	}

	/* This method checks the modifiers of a field.
	*
	* 9.3 & 8.3
	* Need to integrate the check for the final modifiers for nested types
	*
	* Note : A scope is accessible by : fieldBinding.declaringClass.scope
	*/
	private void checkAndSetModifiersForField(FieldBinding fieldBinding, FieldDeclaration fieldDecl) {
		int modifiers = fieldBinding.modifiers;
		final ReferenceBinding declaringClass = fieldBinding.declaringClass;
		if ((modifiers & ExtraCompilerModifiers.AccAlternateModifierProblem) != 0)
			problemReporter().duplicateModifierForField(declaringClass, fieldDecl);

		if (declaringClass.isInterface()) {
			final int IMPLICIT_MODIFIERS = ClassFileConstants.AccPublic | ClassFileConstants.AccStatic | ClassFileConstants.AccFinal;
			// set the modifiers
			modifiers |= IMPLICIT_MODIFIERS;

			// and then check that they are the only ones
			if ((modifiers & ExtraCompilerModifiers.AccJustFlag) != IMPLICIT_MODIFIERS) {
				if ((declaringClass.modifiers  & ClassFileConstants.AccAnnotation) != 0)
					problemReporter().illegalModifierForAnnotationField(fieldDecl);
				else
					problemReporter().illegalModifierForInterfaceField(fieldDecl);
			}
			fieldBinding.modifiers = modifiers;
			return;
		} else if (fieldDecl.getKind() == AbstractVariableDeclaration.ENUM_CONSTANT) {
			// check that they are not modifiers in source
			if ((modifiers & ExtraCompilerModifiers.AccJustFlag) != 0)
				problemReporter().illegalModifierForEnumConstant(declaringClass, fieldDecl);

			// set the modifiers
			final int IMPLICIT_MODIFIERS = ClassFileConstants.AccPublic | ClassFileConstants.AccStatic | ClassFileConstants.AccFinal | ClassFileConstants.AccEnum;
			fieldBinding.modifiers|= IMPLICIT_MODIFIERS;
			return;
		}

		// after this point, tests on the 16 bits reserved.
		int realModifiers = modifiers & ExtraCompilerModifiers.AccJustFlag;
		final int UNEXPECTED_MODIFIERS = ~(ClassFileConstants.AccPublic | ClassFileConstants.AccPrivate | ClassFileConstants.AccProtected | ClassFileConstants.AccFinal | ClassFileConstants.AccStatic | ClassFileConstants.AccTransient | ClassFileConstants.AccVolatile);
		if ((realModifiers & UNEXPECTED_MODIFIERS) != 0) {
			problemReporter().illegalModifierForField(declaringClass, fieldDecl);
			modifiers &= ~ExtraCompilerModifiers.AccJustFlag | ~UNEXPECTED_MODIFIERS;
		}

		int accessorBits = realModifiers & (ClassFileConstants.AccPublic | ClassFileConstants.AccProtected | ClassFileConstants.AccPrivate);
		if ((accessorBits & (accessorBits - 1)) > 1) {
			problemReporter().illegalVisibilityModifierCombinationForField(declaringClass, fieldDecl);

			// need to keep the less restrictive so disable Protected/Private as necessary
			if ((accessorBits & ClassFileConstants.AccPublic) != 0) {
				if ((accessorBits & ClassFileConstants.AccProtected) != 0)
					modifiers &= ~ClassFileConstants.AccProtected;
				if ((accessorBits & ClassFileConstants.AccPrivate) != 0)
					modifiers &= ~ClassFileConstants.AccPrivate;
			} else if ((accessorBits & ClassFileConstants.AccProtected) != 0 && (accessorBits & ClassFileConstants.AccPrivate) != 0) {
				modifiers &= ~ClassFileConstants.AccPrivate;
			}
		}

		if ((realModifiers & (ClassFileConstants.AccFinal | ClassFileConstants.AccVolatile)) == (ClassFileConstants.AccFinal | ClassFileConstants.AccVolatile))
			problemReporter().illegalModifierCombinationFinalVolatileForField(declaringClass, fieldDecl);

		if (fieldDecl.initialization == null && (modifiers & ClassFileConstants.AccFinal) != 0)
			modifiers |= ExtraCompilerModifiers.AccBlankFinal;
		fieldBinding.modifiers = modifiers;
	}

	public void checkParameterizedSuperTypeCollisions() {
		// check for parameterized interface collisions (when different parameterizations occur)
		SourceTypeBinding sourceType = getReferenceBinding();
		ReferenceBinding[] interfaces = sourceType.superInterfaces;
		Map invocations = new HashMap(2);
		ReferenceBinding itsSuperclass = sourceType.isInterface() ? null : sourceType.superclass;
		nextInterface: for (int i = 0, length = interfaces.length; i < length; i++) {
			ReferenceBinding one =  interfaces[i];
			if (one == null) continue nextInterface;
			if (itsSuperclass != null && hasErasedCandidatesCollisions(itsSuperclass, one, invocations, sourceType, referenceContext))
				continue nextInterface;
			nextOtherInterface: for (int j = 0; j < i; j++) {
				ReferenceBinding two = interfaces[j];
				if (two == null) continue nextOtherInterface;
				if (hasErasedCandidatesCollisions(one, two, invocations, sourceType, referenceContext))
					continue nextInterface;
			}
		}

		TypeParameter[] typeParameters = this.referenceContext.typeParameters;
		nextVariable : for (int i = 0, paramLength = typeParameters == null ? 0 : typeParameters.length; i < paramLength; i++) {
			TypeParameter typeParameter = typeParameters[i];
			TypeVariableBinding typeVariable = typeParameter.binding;
			if (typeVariable == null || !typeVariable.isValidBinding()) continue nextVariable;

			TypeReference[] boundRefs = typeParameter.bounds;
			if (boundRefs != null) {
				boolean checkSuperclass = typeVariable.firstBound == typeVariable.superclass;
				for (int j = 0, boundLength = boundRefs.length; j < boundLength; j++) {
					TypeReference typeRef = boundRefs[j];
					TypeBinding superType = typeRef.resolvedType;
					if (superType == null || !superType.isValidBinding()) continue;

					// check against superclass
					if (checkSuperclass)
						if (hasErasedCandidatesCollisions(superType, typeVariable.superclass, invocations, typeVariable, typeRef))
							continue nextVariable;
					// check against superinterfaces
					for (int index = typeVariable.superInterfaces.length; --index >= 0;)
						if (hasErasedCandidatesCollisions(superType, typeVariable.superInterfaces[index], invocations, typeVariable, typeRef))
							continue nextVariable;
				}
			}
		}

		ReferenceBinding[] memberTypes = getReferenceBinding().memberTypes;
		if (memberTypes != null && memberTypes != Binding.NO_MEMBER_TYPES)
			for (int i = 0, size = memberTypes.length; i < size; i++)
				 ((SourceTypeBinding) memberTypes[i]).classScope.checkParameterizedSuperTypeCollisions();
	}

	private void checkForInheritedMemberTypes(SourceTypeBinding sourceType) {
		// search up the hierarchy of the sourceType to see if any superType defines a member type
		// when no member types are defined, tag the sourceType & each superType with the HasNoMemberTypes bit
		// assumes super types have already been checked & tagged
		ReferenceBinding currentType = sourceType;
		ReferenceBinding[] interfacesToVisit = null;
		int nextPosition = 0;
		do {
			if (currentType.hasMemberTypes()) // avoid resolving member types eagerly
				return;

			ReferenceBinding[] itsInterfaces = currentType.superInterfaces();
			// in code assist cases when source types are added late, may not be finished connecting hierarchy
			if (itsInterfaces != null && itsInterfaces != Binding.NO_SUPERINTERFACES) {
				if (interfacesToVisit == null) {
					interfacesToVisit = itsInterfaces;
					nextPosition = interfacesToVisit.length;
				} else {
					int itsLength = itsInterfaces.length;
					if (nextPosition + itsLength >= interfacesToVisit.length)
						System.arraycopy(interfacesToVisit, 0, interfacesToVisit = new ReferenceBinding[nextPosition + itsLength + 5], 0, nextPosition);
					nextInterface : for (int a = 0; a < itsLength; a++) {
						ReferenceBinding next = itsInterfaces[a];
						for (int b = 0; b < nextPosition; b++)
							if (next == interfacesToVisit[b]) continue nextInterface;
						interfacesToVisit[nextPosition++] = next;
					}
				}
			}
			/* BC- Added cycle check BUG 200501 */
		} while (currentType.superclass()!=currentType && (currentType = currentType.superclass()) != null && (currentType.tagBits & TagBits.HasNoMemberTypes) == 0);

		if (interfacesToVisit != null) {
			// contains the interfaces between the sourceType and any superclass, which was tagged as having no member types
			boolean needToTag = false;
			for (int i = 0; i < nextPosition; i++) {
				ReferenceBinding anInterface = interfacesToVisit[i];
				if ((anInterface.tagBits & TagBits.HasNoMemberTypes) == 0) { // skip interface if it already knows it has no member types
					if (anInterface.hasMemberTypes()) // avoid resolving member types eagerly
						return;

					needToTag = true;
					ReferenceBinding[] itsInterfaces = anInterface.superInterfaces();
					if (itsInterfaces != null && itsInterfaces != Binding.NO_SUPERINTERFACES) {
						int itsLength = itsInterfaces.length;
						if (nextPosition + itsLength >= interfacesToVisit.length)
							System.arraycopy(interfacesToVisit, 0, interfacesToVisit = new ReferenceBinding[nextPosition + itsLength + 5], 0, nextPosition);
						nextInterface : for (int a = 0; a < itsLength; a++) {
							ReferenceBinding next = itsInterfaces[a];
							for (int b = 0; b < nextPosition; b++)
								if (next == interfacesToVisit[b]) continue nextInterface;
							interfacesToVisit[nextPosition++] = next;
						}
					}
				}
			}

			if (needToTag) {
				for (int i = 0; i < nextPosition; i++)
					interfacesToVisit[i].tagBits |= TagBits.HasNoMemberTypes;
			}
		}

		// tag the sourceType and all of its superclasses, unless they have already been tagged
		currentType = sourceType;
		do {
			currentType.tagBits |= TagBits.HasNoMemberTypes;
		} while ((currentType = currentType.superclass()) != null && (currentType.tagBits & TagBits.HasNoMemberTypes) == 0);
	}

	// Perform deferred bound checks for parameterized type references (only done after hierarchy is connected)
	public void  checkParameterizedTypeBounds() {
		TypeReference superclass = referenceContext.superclass;
		if (superclass != null)
			superclass.checkBounds(this);

		TypeReference[] superinterfaces = referenceContext.superInterfaces;
		if (superinterfaces != null)
			for (int i = 0, length = superinterfaces.length; i < length; i++)
				superinterfaces[i].checkBounds(this);

		TypeParameter[] typeParameters = referenceContext.typeParameters;
		if (typeParameters != null)
			for (int i = 0, paramLength = typeParameters.length; i < paramLength; i++)
				typeParameters[i].checkBounds(this);

		ReferenceBinding[] memberTypes = getReferenceBinding().memberTypes;
		if (memberTypes != null && memberTypes != Binding.NO_MEMBER_TYPES)
			for (int i = 0, size = memberTypes.length; i < size; i++)
				 ((SourceTypeBinding) memberTypes[i]).classScope.checkParameterizedTypeBounds();
	}

	private void connectMemberTypes() {
		SourceTypeBinding sourceType = getReferenceBinding();
		ReferenceBinding[] memberTypes = sourceType.memberTypes;
		if (memberTypes != null && memberTypes != Binding.NO_MEMBER_TYPES) {
			for (int i = 0, size = memberTypes.length; i < size; i++)
				 ((SourceTypeBinding) memberTypes[i]).classScope.connectTypeHierarchy();
		}
	}
	/*
		Our current belief based on available JCK tests is:
			inherited member types are visible as a potential superclass.
			inherited interfaces are not visible when defining a superinterface.

		Error recovery story:
			ensure the superclass is set to java.lang.Object if a problem is detected
			resolving the superclass.

		Answer false if an error was reported against the sourceType.
	*/
	private boolean connectSuperclass() {
		SourceTypeBinding sourceType = getReferenceBinding();
		if (sourceType.id == T_JavaLangObject) { // handle the case of redefining java.lang.Object up front
			sourceType.superclass = null;
			sourceType.superInterfaces = Binding.NO_SUPERINTERFACES;
			if (!sourceType.isClass())
				problemReporter().objectMustBeClass(sourceType);
//			if (referenceContext.superclass != null || (referenceContext.superInterfaces != null && referenceContext.superInterfaces.length > 0))
//				problemReporter().objectCannotHaveSuperTypes(sourceType);
			return true; // do not propagate Object's hierarchy problems down to every subtype
		}
		if ( (referenceContext!=null && referenceContext.superclass == null) || (inferredType!=null && inferredType.superClass==null)) {
			if (sourceType.isEnum() && compilerOptions().sourceLevel >= ClassFileConstants.JDK1_5) // do not connect if source < 1.5 as enum already got flagged as syntax error
				return connectEnumSuperclass();
			sourceType.superclass = getJavaLangObject();
			return !detectHierarchyCycle(sourceType, sourceType.superclass, null);
		}
		if (referenceContext!=null)
		{
			TypeReference superclassRef = referenceContext.superclass;
			ReferenceBinding superclass = findSupertype(superclassRef);
			if (superclass != null) { // is null if a cycle was detected cycle or a problem
				if (!superclass.isClass()) {
					problemReporter().superclassMustBeAClass(sourceType, superclassRef, superclass);
				} else if (superclass.isFinal()) {
					problemReporter().classExtendFinalClass(sourceType, superclassRef, superclass);
				} else if ((superclass.tagBits & TagBits.HasDirectWildcard) != 0) {
					problemReporter().superTypeCannotUseWildcard(sourceType, superclassRef, superclass);
				} else if (superclass.erasure().id == T_JavaLangEnum) {
					problemReporter().cannotExtendEnum(sourceType, superclassRef, superclass);
				} else {
					// only want to reach here when no errors are reported
					sourceType.superclass = superclass;
					return true;
				}
			}
		}
		else
		{
			ReferenceBinding superclass = findInferredSupertype(inferredType.superClass);
			if (superclass != null) { // is null if a cycle was detected cycle or a problem
				if (!superclass.isClass()) {
					problemReporter().superclassMustBeAClass(sourceType, inferredType, superclass);
//				} else if (superclass.isFinal()) {
//					problemReporter().classExtendFinalClass(sourceType, superclassRef, superclass);
//				} else if ((superclass.tagBits & TagBits.HasDirectWildcard) != 0) {
//					problemReporter().superTypeCannotUseWildcard(sourceType, superclassRef, superclass);
//				} else if (superclass.erasure().id == T_JavaLangEnum) {
//					problemReporter().cannotExtendEnum(sourceType, superclassRef, superclass);
				} else {
					// only want to reach here when no errors are reported
					sourceType.superclass = superclass;
					return true;
				}
			}

		}
		sourceType.tagBits |= TagBits.HierarchyHasProblems;
		sourceType.superclass = getJavaLangObject();
		if ((sourceType.superclass.tagBits & TagBits.BeginHierarchyCheck) == 0)
			detectHierarchyCycle(sourceType, sourceType.superclass, null);
		return false; // reported some error against the source type
	}

	/**
	 *  enum X (implicitly) extends Enum<X>
	 */
	private boolean connectEnumSuperclass() {
		SourceTypeBinding sourceType = getReferenceBinding();
		ReferenceBinding rootEnumType = getJavaLangEnum();
		boolean foundCycle = detectHierarchyCycle(sourceType, rootEnumType, null);
		// arity check for well-known Enum<E>
		TypeVariableBinding[] refTypeVariables = rootEnumType.typeVariables();
		if (refTypeVariables == Binding.NO_TYPE_VARIABLES) { // check generic
			problemReporter().nonGenericTypeCannotBeParameterized(null, rootEnumType, new TypeBinding[]{ sourceType });
			return false; // cannot reach here as AbortCompilation is thrown
		} else if (1 != refTypeVariables.length) { // check arity
			problemReporter().incorrectArityForParameterizedType(null, rootEnumType, new TypeBinding[]{ sourceType });
			return false; // cannot reach here as AbortCompilation is thrown
		}
		// check argument type compatibility
		ParameterizedTypeBinding  superType = environment().createParameterizedType(rootEnumType, new TypeBinding[]{ environment().convertToRawType(sourceType) } , null);
		sourceType.superclass = superType;
		// bound check (in case of bogus definition of Enum type)
		if (refTypeVariables[0].boundCheck(superType, sourceType) != TypeConstants.OK) {
			problemReporter().typeMismatchError(rootEnumType, refTypeVariables[0], sourceType, null);
		}
		return !foundCycle;
	}

	/*
		Our current belief based on available JCK 1.3 tests is:
			inherited member types are visible as a potential superclass.
			inherited interfaces are visible when defining a superinterface.

		Error recovery story:
			ensure the superinterfaces contain only valid visible interfaces.

		Answer false if an error was reported against the sourceType.
	*/
	private boolean connectSuperInterfaces() {
		SourceTypeBinding sourceType = getReferenceBinding();
		sourceType.superInterfaces = Binding.NO_SUPERINTERFACES;
		if (referenceContext!=null && referenceContext.superInterfaces == null) {
			if (sourceType.isAnnotationType() && compilerOptions().sourceLevel >= ClassFileConstants.JDK1_5) { // do not connect if source < 1.5 as annotation already got flagged as syntax error) {
				ReferenceBinding annotationType = getJavaLangAnnotationAnnotation();
				boolean foundCycle = detectHierarchyCycle(sourceType, annotationType, null);
				sourceType.superInterfaces = new ReferenceBinding[] { annotationType };
				return !foundCycle;
			}
			return true;
		}
		if (sourceType.id == T_JavaLangObject) // already handled the case of redefining java.lang.Object
			return true;

		boolean noProblems = true;
//		int length = referenceContext.superInterfaces.length;
//		ReferenceBinding[] interfaceBindings = new ReferenceBinding[length];
//		int count = 0;
//		nextInterface : for (int i = 0; i < length; i++) {
//		    TypeReference superInterfaceRef = referenceContext.superInterfaces[i];
//			ReferenceBinding superInterface = findSupertype(superInterfaceRef);
//			if (superInterface == null) { // detected cycle
//				sourceType.tagBits |= TagBits.HierarchyHasProblems;
//				noProblems = false;
//				continue nextInterface;
//			}
//			superInterfaceRef.resolvedType = superInterface; // hold onto the problem type
//			// check for simple interface collisions
//			// Check for a duplicate interface once the name is resolved, otherwise we may be confused (ie : a.b.I and c.d.I)
//			for (int j = 0; j < i; j++) {
//				if (interfaceBindings[j] == superInterface) {
//					problemReporter().duplicateSuperinterface(sourceType, superInterfaceRef, superInterface);
//					continue nextInterface;
//				}
//			}
//			if (!superInterface.isInterface()) {
//				problemReporter().superinterfaceMustBeAnInterface(sourceType, superInterfaceRef, superInterface);
//				sourceType.tagBits |= TagBits.HierarchyHasProblems;
//				noProblems = false;
//				continue nextInterface;
//			} else if (superInterface.isAnnotationType()){
//				problemReporter().annotationTypeUsedAsSuperinterface(sourceType, superInterfaceRef, superInterface);
//			}
//			if ((superInterface.tagBits & TagBits.HasDirectWildcard) != 0) {
//				problemReporter().superTypeCannotUseWildcard(sourceType, superInterfaceRef, superInterface);
//				sourceType.tagBits |= TagBits.HierarchyHasProblems;
//				noProblems = false;
//				continue nextInterface;
//			}
//			// only want to reach here when no errors are reported
//			interfaceBindings[count++] = superInterface;
//		}
//		// hold onto all correctly resolved superinterfaces
//		if (count > 0) {
//			if (count != length)
//				System.arraycopy(interfaceBindings, 0, interfaceBindings = new ReferenceBinding[count], 0, count);
//			sourceType.superInterfaces = interfaceBindings;
//		}
		return noProblems;
	}


	void connectTypeHierarchy() {
 		SourceTypeBinding sourceType = getReferenceBinding();
		if ((sourceType.tagBits & TagBits.BeginHierarchyCheck) == 0) {
			sourceType.tagBits |= TagBits.BeginHierarchyCheck;
			 boolean noProblems  = connectSuperclass();
			//noProblems &= connectSuperInterfaces();
			sourceType.tagBits |= TagBits.EndHierarchyCheck;
//			noProblems &= connectTypeVariables(referenceContext.typeParameters, false);
			sourceType.tagBits |= TagBits.TypeVariablesAreConnected;
			if (noProblems && sourceType.isHierarchyInconsistent())
				problemReporter().hierarchyHasProblems(sourceType);
		}
		connectMemberTypes();
		LookupEnvironment env = environment();
		try {
			env.missingClassFileLocation = referenceContext;
			checkForInheritedMemberTypes(sourceType);
		} catch (AbortCompilation e) {
			e.updateContext(referenceContext, referenceCompilationUnit().compilationResult);
			throw e;
		} finally {
			env.missingClassFileLocation = null;
		}
	}

	private void connectTypeHierarchyWithoutMembers() {
		// must ensure the imports are resolved
		if (parent instanceof CompilationUnitScope) {
			if (((CompilationUnitScope) parent).imports == null)
				 ((CompilationUnitScope) parent).checkAndSetImports();
		} else if (parent instanceof ClassScope) {
			// ensure that the enclosing type has already been checked
			 ((ClassScope) parent).connectTypeHierarchyWithoutMembers();
		}

		// double check that the hierarchy search has not already begun...
		SourceTypeBinding sourceType = getReferenceBinding();
		if ((sourceType.tagBits & TagBits.BeginHierarchyCheck) != 0)
			return;

		sourceType.tagBits |= TagBits.BeginHierarchyCheck;
		boolean noProblems = connectSuperclass();
		noProblems &= connectSuperInterfaces();
		sourceType.tagBits |= TagBits.EndHierarchyCheck;
//		noProblems &= connectTypeVariables(referenceContext.typeParameters, false);
		sourceType.tagBits |= TagBits.TypeVariablesAreConnected;
		if (noProblems && sourceType.isHierarchyInconsistent())
			problemReporter().hierarchyHasProblems(sourceType);
	}

	public boolean detectHierarchyCycle(TypeBinding superType, TypeReference reference) {
		if (!(superType instanceof ReferenceBinding)) return false;

		if (reference == this.superTypeReference) { // see findSuperType()
			if (superType.isTypeVariable())
				return false; // error case caught in resolveSuperType()
			// abstract class X<K,V> implements java.util.Map<K,V>
			//    static abstract class M<K,V> implements Entry<K,V>
			if (superType.isParameterizedType())
				superType = ((ParameterizedTypeBinding) superType).genericType();
			compilationUnitScope().recordSuperTypeReference(superType); // to record supertypes
			return detectHierarchyCycle(getReferenceBinding(), (ReferenceBinding) superType, reference);
		}

		if ((superType.tagBits & TagBits.BeginHierarchyCheck) == 0 && superType instanceof SourceTypeBinding)
			// ensure if this is a source superclass that it has already been checked
			((SourceTypeBinding) superType).classScope.connectTypeHierarchyWithoutMembers();
		return false;
	}

	// Answer whether a cycle was found between the sourceType & the superType
	private boolean detectHierarchyCycle(SourceTypeBinding sourceType, ReferenceBinding superType, TypeReference reference) {
		if (superType.isRawType())
			superType = ((RawTypeBinding) superType).genericType();
		// by this point the superType must be a binary or source type

		if (sourceType == superType) {
			problemReporter().hierarchyCircularity(sourceType, superType, reference);
			sourceType.tagBits |= TagBits.HierarchyHasProblems;
			return true;
		}

		if (superType.isMemberType()) {
			ReferenceBinding current = superType.enclosingType();
			do {
				if (current.isHierarchyBeingConnected() && current == sourceType) {
					problemReporter().hierarchyCircularity(sourceType, current, reference);
					sourceType.tagBits |= TagBits.HierarchyHasProblems;
					current.tagBits |= TagBits.HierarchyHasProblems;
					return true;
				}
			} while ((current = current.enclosingType()) != null);
		}

		if (superType.isBinaryBinding()) {
			// force its superclass & superinterfaces to be found... 2 possibilities exist - the source type is included in the hierarchy of:
			//		- a binary type... this case MUST be caught & reported here
			//		- another source type... this case is reported against the other source type
			boolean hasCycle = false;
			ReferenceBinding parentType = superType.superclass();
			if (parentType != null) {
				if (sourceType == parentType) {
					problemReporter().hierarchyCircularity(sourceType, superType, reference);
					sourceType.tagBits |= TagBits.HierarchyHasProblems;
					superType.tagBits |= TagBits.HierarchyHasProblems;
					return true;
				}
				if (parentType.isParameterizedType())
					parentType = ((ParameterizedTypeBinding) parentType).genericType();
				hasCycle |= detectHierarchyCycle(sourceType, parentType, reference);
				if ((parentType.tagBits & TagBits.HierarchyHasProblems) != 0) {
					sourceType.tagBits |= TagBits.HierarchyHasProblems;
					parentType.tagBits |= TagBits.HierarchyHasProblems; // propagate down the hierarchy
				}
			}

			ReferenceBinding[] itsInterfaces = superType.superInterfaces();
			if (itsInterfaces != null && itsInterfaces != Binding.NO_SUPERINTERFACES) {
				for (int i = 0, length = itsInterfaces.length; i < length; i++) {
					ReferenceBinding anInterface = itsInterfaces[i];
					if (sourceType == anInterface) {
						problemReporter().hierarchyCircularity(sourceType, superType, reference);
						sourceType.tagBits |= TagBits.HierarchyHasProblems;
						superType.tagBits |= TagBits.HierarchyHasProblems;
						return true;
					}
					if (anInterface.isParameterizedType())
						anInterface = ((ParameterizedTypeBinding) anInterface).genericType();
					hasCycle |= detectHierarchyCycle(sourceType, anInterface, reference);
					if ((anInterface.tagBits & TagBits.HierarchyHasProblems) != 0) {
						sourceType.tagBits |= TagBits.HierarchyHasProblems;
						superType.tagBits |= TagBits.HierarchyHasProblems;
					}
				}
			}
			return hasCycle;
		}

		if (superType.isHierarchyBeingConnected()) {
			org.eclipse.wst.jsdt.internal.compiler.ast.TypeReference ref = ((SourceTypeBinding) superType).classScope.superTypeReference;
			// https://bugs.eclipse.org/bugs/show_bug.cgi?id=133071
			// https://bugs.eclipse.org/bugs/show_bug.cgi?id=121734
			if (ref != null && (ref.resolvedType == null || ((ReferenceBinding) ref.resolvedType).isHierarchyBeingConnected())) {
				problemReporter().hierarchyCircularity(sourceType, superType, reference);
				sourceType.tagBits |= TagBits.HierarchyHasProblems;
				superType.tagBits |= TagBits.HierarchyHasProblems;
				return true;
			}
		}
		if ((superType.tagBits & TagBits.BeginHierarchyCheck) == 0)
			// ensure if this is a source superclass that it has already been checked
			((SourceTypeBinding) superType).classScope.connectTypeHierarchyWithoutMembers();
		if ((superType.tagBits & TagBits.HierarchyHasProblems) != 0)
			sourceType.tagBits |= TagBits.HierarchyHasProblems;
		return false;
	}

	private ReferenceBinding findInferredSupertype(InferredType type) {
		try {
//			typeReference.aboutToResolve(this); // allows us to trap completion & selection nodes
			compilationUnitScope().recordQualifiedReference(new char[][]{type.getName()});
//			this.superTypeReference = typeReference;
			ReferenceBinding superType = type.resolveSuperType(this);
			this.superTypeReference = null;
			return superType;
		} catch (AbortCompilation e) {
			e.updateContext(type, referenceCompilationUnit().compilationResult);
			throw e;
		}
	}


	private ReferenceBinding findSupertype(TypeReference typeReference) {
		CompilationUnitScope unitScope = compilationUnitScope();
		LookupEnvironment env = unitScope.environment;
		try {
			env.missingClassFileLocation = typeReference;
			typeReference.aboutToResolve(this); // allows us to trap completion & selection nodes
			unitScope.recordQualifiedReference(typeReference.getTypeName());
			this.superTypeReference = typeReference;
			ReferenceBinding superType = (ReferenceBinding) typeReference.resolveSuperType(this);
			return superType;
		} catch (AbortCompilation e) {
			SourceTypeBinding sourceType = this.getReferenceBinding();
			if (sourceType.superInterfaces == null)  sourceType.superInterfaces = Binding.NO_SUPERINTERFACES; // be more resilient for hierarchies (144976)
			e.updateContext(typeReference, referenceCompilationUnit().compilationResult);
			throw e;
		} finally {
			env.missingClassFileLocation = null;
			this.superTypeReference = null;
		}
	}

	/* Answer the problem reporter to use for raising new problems.
	*
	* Note that as a side-effect, this updates the current reference context
	* (unit, type or method) in case the problem handler decides it is necessary
	* to abort.
	*/
	public ProblemReporter problemReporter() {
		MethodScope outerMethodScope;
		if ((outerMethodScope = outerMostMethodScope()) == null) {
			ProblemReporter problemReporter = referenceCompilationUnit().problemReporter;
			problemReporter.referenceContext = referenceContext;
			return problemReporter;
		}
		return outerMethodScope.problemReporter();
	}

	/* Answer the reference type of this scope.
	* It is the nearest enclosing type of this scope.
	*/
	public TypeDeclaration referenceType() {
		return referenceContext;
	}

	public String toString() {
		if (referenceContext != null)
			return "--- Class Scope ---\n\n"  //$NON-NLS-1$
							+ getReferenceBinding().toString();
		return "--- Class Scope ---\n\n Binding not initialized" ; //$NON-NLS-1$
	}

	SourceTypeBinding buildInferredType(SourceTypeBinding enclosingType, PackageBinding packageBinding, AccessRestriction accessRestriction) {
		// provide the typeDeclaration with needed scopes
		inferredType.scope = this;

		if (enclosingType == null) {
			char[][] className = CharOperation.arrayConcat(packageBinding.compoundName, inferredType.getName());
			inferredType.binding = new SourceTypeBinding(className, packageBinding, this);

			//@GINO: Anonymous set bits
			if( inferredType.isAnonymous )
				inferredType.binding.tagBits |= TagBits.AnonymousTypeMask;
		} else {
//			char[][] className = CharOperation.deepCopy(enclosingType.compoundName);
//			className[className.length - 1] =
//				CharOperation.concat(className[className.length - 1], referenceContext.name, '$');
//			referenceContext.binding = new MemberTypeBinding(className, this, enclosingType);
		}

		SourceTypeBinding sourceType = inferredType.binding;
		environment().setAccessRestriction(sourceType, accessRestriction);
		environment().defaultPackage.addType(sourceType);
		sourceType.fPackage.addType(sourceType);
		return sourceType;
	}

}