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

package org.eclipse.wst.jsdt.internal.ui.text.spelling;

import java.net.URL;
import java.util.StringTokenizer;

import org.eclipse.core.runtime.Plugin;
import org.eclipse.core.runtime.Preferences.IPropertyChangeListener;
import org.eclipse.core.runtime.Preferences.PropertyChangeEvent;
import org.eclipse.wst.jsdt.core.JavaScriptCore;
import org.eclipse.wst.jsdt.internal.ui.text.spelling.engine.AbstractSpellDictionary;

/**
 * Dictionary for task tags.
 *
 * 
 */
public class TaskTagDictionary extends AbstractSpellDictionary implements IPropertyChangeListener {

	/*
	 * @see org.eclipse.wst.jsdt.internal.ui.text.spelling.engine.AbstractSpellDictionary#getName()
	 */
	protected final URL getURL() {
		return null;
	}

	/*
	 * @see org.eclipse.wst.jsdt.ui.text.spelling.engine.AbstractSpellDictionary#load(java.net.URL)
	 */
	protected synchronized boolean load(final URL url) {

		final Plugin plugin= JavaScriptCore.getPlugin();
		if (plugin != null) {

			plugin.getPluginPreferences().addPropertyChangeListener(this);
			return updateTaskTags();
		}
		return false;
	}

	/*
	 * @see org.eclipse.core.runtime.Preferences.IPropertyChangeListener#propertyChange(org.eclipse.core.runtime.Preferences.PropertyChangeEvent)
	 */
	public void propertyChange(final PropertyChangeEvent event) {

		if (JavaScriptCore.COMPILER_TASK_TAGS.equals(event.getProperty()))
			updateTaskTags();
	}

	/*
	 * @see org.eclipse.wst.jsdt.ui.text.spelling.engine.ISpellDictionary#unload()
	 */
	public synchronized void unload() {

		final Plugin plugin= JavaScriptCore.getPlugin();
		if (plugin != null)
			plugin.getPluginPreferences().removePropertyChangeListener(this);

		super.unload();
	}

	/**
	 * Handles the compiler task tags property change event.
	 * 
	 * @return  <code>true</code> if the task tags got updated
	 */
	protected boolean updateTaskTags() {

		final String tags= JavaScriptCore.getOption(JavaScriptCore.COMPILER_TASK_TAGS);
		if (tags != null) {

			unload();

			final StringTokenizer tokenizer= new StringTokenizer(tags, ","); //$NON-NLS-1$
			while (tokenizer.hasMoreTokens())
				hashWord(tokenizer.nextToken());

			return true;
		}
		return false;
	}
	
	/*
	 * @see org.eclipse.wst.jsdt.internal.ui.text.spelling.engine.AbstractSpellDictionary#stripNonLetters(java.lang.String)
	 * 
	 */
	protected String stripNonLetters(String word) {
		return word;
	}
}