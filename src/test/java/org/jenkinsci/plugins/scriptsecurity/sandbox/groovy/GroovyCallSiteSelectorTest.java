/*
 * The MIT License
 *
 * Copyright 2014 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jenkinsci.plugins.scriptsecurity.sandbox.groovy;

import groovy.lang.Binding;
import groovy.lang.GString;
import groovy.lang.Script;
import hudson.EnvVars;
import hudson.model.BooleanParameterValue;
import hudson.model.Hudson;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.StringParameterValue;
import jenkins.model.Jenkins;
import org.apache.commons.io.output.NullOutputStream;
import org.codehaus.groovy.runtime.GStringImpl;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.EnumeratingWhitelist;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.EnumeratingWhitelistTest;
import org.jenkinsci.plugins.scriptsecurity.scripts.ApprovalContext;
import static org.junit.Assert.*;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;

public class GroovyCallSiteSelectorTest {

    @Test public void arrays() throws Exception {
        Method m = EnumeratingWhitelistTest.C.class.getDeclaredMethod("m", Object[].class);
        assertEquals("literal call", m, GroovyCallSiteSelector.method(new EnumeratingWhitelistTest.C(), "m", new Object[] {new Object[] {"a", "b"}}));
        assertEquals("we assume the interceptor has dealt with varargs", null, GroovyCallSiteSelector.method(new EnumeratingWhitelistTest.C(), "m", new Object[] {"a", "b"}));
        assertEquals("array cast", m, GroovyCallSiteSelector.method(new EnumeratingWhitelistTest.C(), "m", new Object[] {new String[] {"a", "b"}}));
    }

    @Test public void overloads() throws Exception {
        PrintWriter receiver = new PrintWriter(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                // Do nothing, we do not care
            }
        });
        assertEquals(PrintWriter.class.getMethod("print", Object.class), GroovyCallSiteSelector.method(receiver, "print", new Object[] {new Object()}));
        assertEquals(PrintWriter.class.getMethod("print", String.class), GroovyCallSiteSelector.method(receiver, "print", new Object[] {"message"}));
        assertEquals(PrintWriter.class.getMethod("print", int.class), GroovyCallSiteSelector.method(receiver, "print", new Object[] {42}));
    }

    @Issue("JENKINS-29541")
    @Test public void methodsOnGString() throws Exception {
        GStringImpl gString = new GStringImpl(new Object[0], new String[] {"x"});
        assertEquals(String.class.getMethod("substring", int.class), GroovyCallSiteSelector.method(gString, "substring", new Object[] {99}));
        assertEquals(GString.class.getMethod("getValues"), GroovyCallSiteSelector.method(gString, "getValues", new Object[0]));
        assertEquals(GString.class.getMethod("getStrings"), GroovyCallSiteSelector.method(gString, "getStrings", new Object[0]));
    }

    @Issue("JENKINS-31701")
    @Test public void primitives() throws Exception {
        assertEquals(Primitives.class.getMethod("m1", long.class), GroovyCallSiteSelector.staticMethod(Primitives.class, "m1", new Object[] {Long.MAX_VALUE}));
        assertEquals(Primitives.class.getMethod("m1", long.class), GroovyCallSiteSelector.staticMethod(Primitives.class, "m1", new Object[] {99}));
        assertEquals(Primitives.class.getMethod("m2", long.class), GroovyCallSiteSelector.staticMethod(Primitives.class, "m2", new Object[] {Long.MAX_VALUE}));
        assertEquals(Primitives.class.getMethod("m2", int.class), GroovyCallSiteSelector.staticMethod(Primitives.class, "m2", new Object[] {99}));
    }
    public static class Primitives {
        public static void m1(long x) {}
        public static void m2(int x) {}
        public static void m2(long x) {}
    }

    @Test public void staticMethodsCannotBeOverridden() throws Exception {
        assertEquals(Jenkins.class.getMethod("getInstance"), GroovyCallSiteSelector.staticMethod(Jenkins.class, "getInstance", new Object[0]));
        assertEquals(Hudson.class.getMethod("getInstance"), GroovyCallSiteSelector.staticMethod(Hudson.class, "getInstance", new Object[0]));
    }

    @Issue("JENKINS-38908")
    @Test public void main() throws Exception {
        Script receiver = (Script) new SecureGroovyScript("def main() {}; this", true, null).configuring(ApprovalContext.create()).evaluate(GroovyCallSiteSelectorTest.class.getClassLoader(), new Binding(), null);
        assertEquals(receiver.getClass().getMethod("main"), GroovyCallSiteSelector.method(receiver, "main", new Object[0]));
        assertEquals(receiver.getClass().getMethod("main", String[].class), GroovyCallSiteSelector.method(receiver, "main", new Object[] {"somearg"}));
    }

    @Issue("JENKINS-45117")
    @Test public void constructorVarargs() throws Exception {
        assertEquals(EnvVars.class.getConstructor(), GroovyCallSiteSelector.constructor(EnvVars.class, new Object[0]));
        assertEquals(EnvVars.class.getConstructor(String[].class), GroovyCallSiteSelector.constructor(EnvVars.class, new Object[]{"x"}));
        List<ParameterValue> params = new ArrayList<>();
        params.add(new StringParameterValue("someParam", "someValue"));
        params.add(new BooleanParameterValue("someBool", true));
        params.add(new StringParameterValue("someOtherParam", "someOtherValue"));
        assertEquals(ParametersAction.class.getConstructor(List.class),
                GroovyCallSiteSelector.constructor(ParametersAction.class, new Object[]{params}));
        assertEquals(ParametersAction.class.getConstructor(ParameterValue[].class),
                GroovyCallSiteSelector.constructor(ParametersAction.class, new Object[]{params.get(0)}));
        assertEquals(ParametersAction.class.getConstructor(ParameterValue[].class),
                GroovyCallSiteSelector.constructor(ParametersAction.class, params.toArray()));
        assertEquals(EnumeratingWhitelist.MethodSignature.class.getConstructor(Class.class, String.class, Class[].class),
                GroovyCallSiteSelector.constructor(EnumeratingWhitelist.MethodSignature.class,
                        new Object[]{String.class, "foo", Integer.class, Float.class}));
    }

    @Issue("JENKINS-47159")
    @Test
    public void varargsFailureCases() throws Exception {
        // If there's a partial match, we should get a ClassCastException
        final ClassCastException e = assertThrows(ClassCastException.class,
                () -> assertNull(GroovyCallSiteSelector.constructor(ParametersAction.class,
                        new Object[]{new BooleanParameterValue("someBool", true), "x"})));
        assertEquals("Cannot cast object 'x' with class 'java.lang.String' to class 'hudson.model.ParameterValue'",
                e.getMessage());
        // If it's a complete non-match, we just shouldn't get a constructor.
        assertNull(GroovyCallSiteSelector.constructor(ParametersAction.class, new Object[]{"a", "b"}));
    }

    @Issue("JENKINS-37257")
    @Test
    public void varargsArrayElementTypeMismatch() throws Exception {
        List<String> l = Arrays.asList("a", "b", "c");
        assertEquals(String.class.getMethod("join", CharSequence.class, Iterable.class),
                GroovyCallSiteSelector.staticMethod(String.class, "join", new Object[]{",", l}));
    }
}
