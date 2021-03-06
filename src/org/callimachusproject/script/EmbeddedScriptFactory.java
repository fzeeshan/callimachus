/*
 * Copyright (c) 2011 Talis Inc., Some rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * - Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution. 
 * - Neither the name of the openrdf.org nor the names of its contributors may
 *   be used to endorse or promote products derived from this software without
 *   specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 */
package org.callimachusproject.script;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.commons.pool.BasePoolableObjectFactory;
import org.apache.commons.pool.impl.SoftReferenceObjectPool;
import org.openrdf.repository.object.exceptions.BehaviourException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates a CompiledScript from source code with a given context.
 *
 * @author James Leigh
 **/
public class EmbeddedScriptFactory {
	private static final String BEHAVIOUR = BehaviourException.class.getName();
	private static final String SCRIPT_EXCEPTION = ScriptException.class.getName();
	private static final Pattern KEYWORDS = Pattern
			.compile("(?:var\\s+|\\.)(break|case|catch|const|continue|default|delete|do|else|export|finally|for|function|if|in|instanceof|import|name|new|return|switch|this|throw|try|typeof|var|void|while|with)\b");

	private final Logger logger = LoggerFactory.getLogger(EmbeddedScriptFactory.class);
	private final ClassLoader cl;
	private EmbeddedScriptContext context;

	public EmbeddedScriptFactory(ClassLoader cl, EmbeddedScriptContext context) {
		this.cl = cl;
		this.context = context;
	}

	public EmbeddedScript create(String systemId, Reader in)
			throws IOException, ScriptException {
		try {
			return createCompiledScript(cl, systemId, read(in));
		} finally {
			in.close();
		}
	}

	private String read(Reader in) throws IOException {
		StringWriter out = new StringWriter();
		for (String className : context.getClasses()) {
			warnIfKeywordUsed(className);
			out.write("importClass(Packages.");
			out.write(className);
			out.write("); ");
		}
		out.write("function ");
		out.write(getInvokeName());
		out.write("(");
		out.write(getMessageName());
		for (String name : context.getBindingNames()) {
			out.write(", ");
			out.write(name);
		}
		out.write(") { ");
		out.write("try { ");
		out.write("function proceed(){return ");
		out.write(getMessageName());
		out.write(".proceed();} ");
		out.write("return (function() {");
		int read;
		char[] cbuf = new char[1024];
		while ((read = in.read(cbuf)) >= 0) {
			out.write(cbuf, 0, read);
		}
		out.write("\n\t");
		out.write("}).call(");
		out.write(getMessageName());
		out.write(".target);\n");
		out.write("} catch (e if e instanceof java.lang.Throwable) {\n\t\t");
		out.append("return new Packages.").append(BEHAVIOUR);
		out.append("(e);\n\t");
		out
				.write("} catch (e if e.javaException instanceof java.lang.Throwable) {\n\t\t");
		out.append("return new Packages.").append(BEHAVIOUR);
		out.append("(e.javaException);\n\t");
		out.write("} catch (e if e.message && e.fileName && e.lineNumber) {\n\t\t");
		out.append("return new Packages.").append(SCRIPT_EXCEPTION);
		out.append("(e.message, e.fileName, e.lineNumber);\n\t");
		out.write("} catch (e if e.message && e.filename && e.line) {\n\t\t");
		out.append("return new Packages.").append(SCRIPT_EXCEPTION);
		out.append("(e.message, e.filename, e.line);\n\t");
		out.write("} catch (e if e.message) {\n\t\t");
		out.append("return new Packages.").append(SCRIPT_EXCEPTION);
		out.append("(e.message);\n\t");
		out.write("} catch (e if e.description) {\n\t\t");
		out.append("return new Packages.").append(SCRIPT_EXCEPTION);
		out.append("(e.description);\n\t");
		out.write("}\n");
		out.write("}\n");
		return out.toString();
	}

	private EmbeddedScript createCompiledScript(final ClassLoader cl,
			final String systemId, final String code) throws ScriptException {
		warnIfKeywordUsed(code);
		BasePoolableObjectFactory<Invocable> factory = new BasePoolableObjectFactory<Invocable>() {

			@Override
			public Invocable makeObject() throws ScriptException {
				Thread current = Thread.currentThread();
				ClassLoader previously = current.getContextClassLoader();
				try {
					current.setContextClassLoader(cl);
					ScriptEngineManager man = new ScriptEngineManager();
					ScriptEngine engine = man.getEngineByName("rhino");
					engine.put(ScriptEngine.FILENAME, systemId);
					engine.eval(code);
					return (Invocable) engine;
				} finally {
					current.setContextClassLoader(previously);
				}
			}
		};
		return new EmbeddedScript(new SoftReferenceObjectPool<Invocable>(
				factory), getInvokeName(), context.getBindingNames(), systemId);
	}

	private String getInvokeName() {
		return "_invoke" + Math.abs(hashCode());
	}

	private String getMessageName() {
		return "_msg" + Math.abs(hashCode());
	}

	private void warnIfKeywordUsed(String code) {
		Matcher m = KEYWORDS.matcher(code);
		if (m.find()) {
			logger.warn("{} is a ECMA script keyword", m.group(1));
		}
	}
}
