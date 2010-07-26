/*******************************************************************************
 * Copyright (c) 2000, 2008 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.wst.jsdt.core;


/**
 * Represents a package declaration in JavaScript compilation unit.
 * <p>
 * This interface is not intended to be implemented by clients.
 * </p>
 *  
 * <p><b>Note: This Interface only applies to ECMAScript 4 which is not yet supported</b></p>
 *
 * Provisional API: This class/interface is part of an interim API that is still under development and expected to 
 * change significantly before reaching stability. It is being made available at this early stage to solicit feedback 
 * from pioneering adopters on the understanding that any code that uses this API will almost certainly be broken 
 * (repeatedly) as the API evolves.
 */
public interface IPackageDeclaration extends IJavaScriptElement, ISourceReference {
/**
 * Returns the name of the package the statement refers to.
 * This is a handle-only method.
 *
 * @return the name of the package the statement
 */
String getElementName();
}